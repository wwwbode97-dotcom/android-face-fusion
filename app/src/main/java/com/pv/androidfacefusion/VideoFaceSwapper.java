package com.pv.androidfacefusion;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.nio.ByteBuffer;

public class VideoFaceSwapper {

    public interface ProgressListener {
        void onProgress(int percent, int currentFrame, int totalFrames);
    }

    public interface CancelChecker {
        boolean isCancelled();
    }

    @SuppressLint("WrongConstant")
    public static boolean swapVideo(
            Context context,
            Uri videoUri,
            float[] sourceEmbedding,
            FaceFusionProcessor processor,
            int maxDimension,
            int targetFps,
            File outputFile,
            ProgressListener listener,
            CancelChecker cancelChecker
    ) throws Exception {

        // Lower thread priority to reduce overheating and keep UI responsive
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        MediaExtractor audioExtractor = new MediaExtractor();
        boolean muxerStarted = false;
        boolean cancelled = false;

        try {
            retriever.setDataSource(context, videoUri);

            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);

            long durationMs = Long.parseLong(durationStr != null ? durationStr : "0");
            int origW = Integer.parseInt(widthStr != null ? widthStr : "720");
            int origH = Integer.parseInt(heightStr != null ? heightStr : "1280");
            int rotation = Integer.parseInt(rotStr != null ? rotStr : "0");

            if (rotation == 90 || rotation == 270) {
                int tmp = origW;
                origW = origH;
                origH = tmp;
            }

            float scale = Math.min(1.0f, (float) maxDimension / Math.max(origW, origH));
            int outW = ((int) (origW * scale)) & ~1;
            int outH = ((int) (origH * scale)) & ~1;
            if (outW < 16) outW = 16;
            if (outH < 16) outH = 16;

            int totalFrames = Math.max(1, (int) ((durationMs / 1000.0) * targetFps));
            long frameIntervalUs = 1_000_000L / targetFps;

            // Optimized H.264 encoder settings (lighter bitrate to reduce heat)
            MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, outW * outH * 2);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            audioExtractor.setDataSource(context, videoUri, null);
            int audioSourceTrack = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
                MediaFormat fmt = audioExtractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioSourceTrack = i;
                    audioFormat = fmt;
                    break;
                }
            }

            int muxerVideoTrack = -1;
            int muxerAudioTrack = -1;
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            // Reuse pixel and YUV buffers across frames to reduce GC and heat
            int[] argbBuffer = new int[outW * outH];
            byte[] yuvBuffer = new byte[outW * outH * 3 / 2];

            for (int frameIdx = 0; frameIdx < totalFrames; frameIdx++) {
                if (cancelChecker != null && cancelChecker.isCancelled()) {
                    cancelled = true;
                    break;
                }

                long timeUs = frameIdx * frameIntervalUs;
                Bitmap rawFrame = null;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    rawFrame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, outW, outH);
                } else {
                    Bitmap full = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (full != null) {
                        rawFrame = Bitmap.createScaledBitmap(full, outW, outH, true);
                        if (rawFrame != full) full.recycle();
                    }
                }

                if (rawFrame == null) continue;

                if (rawFrame.getConfig() != Bitmap.Config.ARGB_8888) {
                    Bitmap argb = rawFrame.copy(Bitmap.Config.ARGB_8888, false);
                    rawFrame.recycle();
                    rawFrame = argb;
                }

                Bitmap swappedFrame = processor.processVideoFrame(sourceEmbedding, rawFrame);
                rawFrame.recycle();

                if (swappedFrame.getWidth() != outW || swappedFrame.getHeight() != outH) {
                    Bitmap resized = Bitmap.createScaledBitmap(swappedFrame, outW, outH, true);
                    swappedFrame.recycle();
                    swappedFrame = resized;
                }

                bitmapToNV12Fast(swappedFrame, outW, outH, argbBuffer, yuvBuffer);
                swappedFrame.recycle();

                int inputIndex = encoder.dequeueInputBuffer(10000);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        inputBuffer.put(yuvBuffer);
                    }
                    encoder.queueInputBuffer(inputIndex, 0, yuvBuffer.length, timeUs, 0);
                }

                while (true) {
                    int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0);
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxerVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                        if (audioFormat != null) {
                            muxerAudioTrack = muxer.addTrack(audioFormat);
                        }
                        muxer.start();
                        muxerStarted = true;
                    } else if (outputIndex >= 0) {
                        ByteBuffer encodedData = encoder.getOutputBuffer(outputIndex);
                        if (encodedData != null && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo);
                        }
                        encoder.releaseOutputBuffer(outputIndex, false);
                    } else {
                        break;
                    }
                }

                if (listener != null) {
                    int pct = (int) (((frameIdx + 1) * 100L) / totalFrames);
                    listener.onProgress(pct, frameIdx + 1, totalFrames);
                }
            }

            if (!cancelled) {
                int eosIndex = encoder.dequeueInputBuffer(10000);
                if (eosIndex >= 0) {
                    encoder.queueInputBuffer(eosIndex, 0, 0, totalFrames * frameIntervalUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }

                while (true) {
                    int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                    if (outputIndex >= 0) {
                        ByteBuffer encodedData = encoder.getOutputBuffer(outputIndex);
                        if (encodedData != null && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo);
                        }
                        encoder.releaseOutputBuffer(outputIndex, false);
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    }
                }

                if (muxerStarted && muxerAudioTrack >= 0) {
                    audioExtractor.selectTrack(audioSourceTrack);
                    ByteBuffer audioBuf = ByteBuffer.allocate(256 * 1024);
                    MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
                    while (true) {
                        if (cancelChecker != null && cancelChecker.isCancelled()) {
                            cancelled = true;
                            break;
                        }
                        int sampleSize = audioExtractor.readSampleData(audioBuf, 0);
                        if (sampleSize < 0) break;
                        audioInfo.offset = 0;
                        audioInfo.size = sampleSize;
                        audioInfo.presentationTimeUs = audioExtractor.getSampleTime();
                        audioInfo.flags = (audioExtractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo);
                        audioExtractor.advance();
                    }
                }
            }
        } finally {
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
            try { retriever.release(); } catch (Exception ignored) {}
            try { audioExtractor.release(); } catch (Exception ignored) {}
            try { if (muxer != null) { if (muxerStarted) muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
            if (cancelled && outputFile.exists()) {
                outputFile.delete();
            }
        }

        return !cancelled;
    }

    private static void bitmapToNV12Fast(Bitmap bitmap, int width, int height, int[] argb, byte[] yuv) {
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        int yIndex = 0;
        int uvIndex = width * height;
        int index = 0;

        for (int j = 0; j < height; j++) {
            boolean evenRow = (j & 1) == 0;
            for (int i = 0; i < width; i++) {
                int pixel = argb[index++];
                int R = (pixel >> 16) & 0xff;
                int G = (pixel >> 8) & 0xff;
                int B = pixel & 0xff;

                int Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                yuv[yIndex++] = (byte) (Y < 0 ? 0 : (Y > 255 ? 255 : Y));

                if (evenRow && (i & 1) == 0 && uvIndex + 1 < yuv.length) {
                    int U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                    int V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;
                    yuv[uvIndex++] = (byte) (U < 0 ? 0 : (U > 255 ? 255 : U));
                    yuv[uvIndex++] = (byte) (V < 0 ? 0 : (V > 255 ? 255 : V));
                }
            }
        }
    }
}
