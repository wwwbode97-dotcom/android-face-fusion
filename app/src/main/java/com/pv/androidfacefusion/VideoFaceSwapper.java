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
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

public class VideoFaceSwapper {
    private static final String TAG = "VideoFaceSwapper";

    public interface ProgressListener {
        void onProgress(int percent, int currentFrame, int totalFrames);
    }

    @SuppressLint("WrongConstant")
    public static void swapVideo(
            Context context,
            Uri videoUri,
            float[] sourceEmbedding,
            FaceFusionProcessor processor,
            int maxDimension,
            int targetFps,
            File outputFile,
            ProgressListener listener
    ) throws Exception {

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
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

        // Calculate scaled even dimensions (required by H.264 encoder)
        float scale = Math.min(1.0f, (float) maxDimension / Math.max(origW, origH));
        int outW = ((int) (origW * scale)) & ~1;
        int outH = ((int) (origH * scale)) & ~1;
        if (outW < 16) outW = 16;
        if (outH < 16) outH = 16;

        int totalFrames = Math.max(1, (int) ((durationMs / 1000.0) * targetFps));
        long frameIntervalUs = 1_000_000L / targetFps;

        // Setup Video Encoder (H.264)
        MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, outW * outH * 4);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        MediaMuxer muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Check if source video has an audio track
        MediaExtractor audioExtractor = new MediaExtractor();
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
        boolean muxerStarted = false;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        for (int frameIdx = 0; frameIdx < totalFrames; frameIdx++) {
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

            byte[] yuvData = bitmapToNV12(swappedFrame, outW, outH);
            swappedFrame.recycle();

            int inputIndex = encoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(yuvData);
                }
                encoder.queueInputBuffer(inputIndex, 0, yuvData.length, timeUs, 0);
            }

            // Drain encoder
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

        // Signal End of Stream
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

        // Copy Original Audio Track
        if (muxerStarted && muxerAudioTrack >= 0) {
            audioExtractor.selectTrack(audioSourceTrack);
            ByteBuffer audioBuf = ByteBuffer.allocate(256 * 1024);
            MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
            while (true) {
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

        encoder.stop();
        encoder.release();
        retriever.release();
        audioExtractor.release();
        if (muxerStarted) {
            muxer.stop();
        }
        muxer.release();
    }

    private static byte[] bitmapToNV12(Bitmap bitmap, int width, int height) {
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        byte[] yuv = new byte[width * height * 3 / 2];

        int yIndex = 0;
        int uvIndex = width * height;
        int index = 0;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int R = (argb[index] & 0xff0000) >> 16;
                int G = (argb[index] & 0xff00) >> 8;
                int B = (argb[index] & 0xff);

                int Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                yuv[yIndex++] = (byte) Math.max(0, Math.min(255, Y));

                if (j % 2 == 0 && i % 2 == 0 && uvIndex + 1 < yuv.length) {
                    int U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                    int V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;
                    yuv[uvIndex++] = (byte) Math.max(0, Math.min(255, U));
                    yuv[uvIndex++] = (byte) Math.max(0, Math.min(255, V));
                }
                index++;
            }
        }
        return yuv;
    }
}
