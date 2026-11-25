package com.pv.androidfacefusion;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
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

        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        boolean cancelled = false;

        try {
            // 1. Read basic video metadata once
            retriever.setDataSource(context, videoUri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            retriever.release();
            retriever = null;

            long durationMs = Long.parseLong(durationStr != null ? durationStr : "0");
            int origW = Integer.parseInt(widthStr != null ? widthStr : "720");
            int origH = Integer.parseInt(heightStr != null ? heightStr : "1280");
            int rotation = Integer.parseInt(rotStr != null ? rotStr : "0");

            int displayW = (rotation == 90 || rotation == 270) ? origH : origW;
            int displayH = (rotation == 90 || rotation == 270) ? origW : origH;

            float scale = Math.min(1.0f, (float) maxDimension / Math.max(displayW, displayH));
            int outW = ((int) (displayW * scale)) & ~1;
            int outH = ((int) (displayH * scale)) & ~1;
            if (outW < 16) outW = 16;
            if (outH < 16) outH = 16;

            int totalFrames = Math.max(1, (int) ((durationMs / 1000.0) * targetFps));
            long frameIntervalUs = 1_000_000L / targetFps;

            // 2. Setup Hardware Video Decoder
            videoExtractor.setDataSource(context, videoUri, null);
            int videoTrackIndex = -1;
            MediaFormat inputVideoFormat = null;
            for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                MediaFormat fmt = videoExtractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    inputVideoFormat = fmt;
                    break;
                }
            }
            if (videoTrackIndex < 0 || inputVideoFormat == null) {
                throw new Exception("No video track found");
            }
            videoExtractor.selectTrack(videoTrackIndex);
            inputVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            String inputMime = inputVideoFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(inputMime);
            decoder.configure(inputVideoFormat, null, null, 0);
            decoder.start();

            // 3. Setup H.264 Video Encoder
            MediaFormat outFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
            outFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            outFormat.setInteger(MediaFormat.KEY_BIT_RATE, outW * outH * 2);
            outFormat.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);
            outFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // 4. Find Audio Track (if exists)
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
            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

            int[] argbBuffer = new int[outW * outH];
            byte[] yuvBuffer = new byte[outW * outH * 3 / 2];

            boolean inputDone = false;
            boolean outputDone = false;
            long nextTargetTimeUs = 0;
            int processedFrames = 0;

            // 5. Sequential Decode -> Swap -> Encode Loop
            while (!outputDone) {
                if (cancelChecker != null && cancelChecker.isCancelled()) {
                    cancelled = true;
                    break;
                }

                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        int sampleSize = videoExtractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = videoExtractor.getSampleTime();
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                            videoExtractor.advance();
                        }
                    }
                }

                int outIdx = decoder.dequeueOutputBuffer(decInfo, 10000);
                if (outIdx >= 0) {
                    boolean isEos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    long ptsUs = decInfo.presentationTimeUs;

                    if (decInfo.size > 0 && ptsUs >= nextTargetTimeUs) {
                        Image image = decoder.getOutputImage(outIdx);
                        Bitmap rawFrame = null;
                        if (image != null) {
                            rawFrame = yuvImageToBitmap(image, rotation, outW, outH);
                            image.close();
                        }
                        decoder.releaseOutputBuffer(outIdx, false);

                        if (rawFrame != null) {
                            Bitmap swappedFrame = processor.processVideoFrame(sourceEmbedding, rawFrame);
                            rawFrame.recycle();

                            if (swappedFrame.getWidth() != outW || swappedFrame.getHeight() != outH) {
                                Bitmap resized = Bitmap.createScaledBitmap(swappedFrame, outW, outH, true);
                                swappedFrame.recycle();
                                swappedFrame = resized;
                            }

                            bitmapToNV12Fast(swappedFrame, outW, outH, argbBuffer, yuvBuffer);
                            swappedFrame.recycle();

                            int encInIdx = encoder.dequeueInputBuffer(10000);
                            if (encInIdx >= 0) {
                                ByteBuffer encInBuf = encoder.getInputBuffer(encInIdx);
                                if (encInBuf != null) {
                                    encInBuf.clear();
                                    encInBuf.put(yuvBuffer);
                                }
                                encoder.queueInputBuffer(encInIdx, 0, yuvBuffer.length, ptsUs, 0);
                            }

                            while (true) {
                                int encOutIdx = encoder.dequeueOutputBuffer(encInfo, 0);
                                if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    muxerVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                                    if (audioFormat != null) {
                                        muxerAudioTrack = muxer.addTrack(audioFormat);
                                    }
                                    muxer.start();
                                    muxerStarted = true;
                                } else if (encOutIdx >= 0) {
                                    ByteBuffer encodedData = encoder.getOutputBuffer(encOutIdx);
                                    if (encodedData != null && (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encInfo.size > 0 && muxerStarted) {
                                        encodedData.position(encInfo.offset);
                                        encodedData.limit(encInfo.offset + encInfo.size);
                                        muxer.writeSampleData(muxerVideoTrack, encodedData, encInfo);
                                    }
                                    encoder.releaseOutputBuffer(encOutIdx, false);
                                } else {
                                    break;
                                }
                            }

                            processedFrames++;
                            nextTargetTimeUs = ptsUs + frameIntervalUs;
                            if (listener != null) {
                                int pct = Math.min(99, (int) ((processedFrames * 100L) / totalFrames));
                                listener.onProgress(pct, processedFrames, totalFrames);
                            }
                        }
                    } else {
                        // Skip intermediate frame quickly
                        decoder.releaseOutputBuffer(outIdx, false);
                    }

                    if (isEos) {
                        outputDone = true;
                    }
                }
            }

            // 6. Finish Encoder & Copy Audio
            if (!cancelled) {
                int eosIndex = encoder.dequeueInputBuffer(10000);
                if (eosIndex >= 0) {
                    encoder.queueInputBuffer(eosIndex, 0, 0, nextTargetTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }

                while (true) {
                    int encOutIdx = encoder.dequeueOutputBuffer(encInfo, 10000);
                    if (encOutIdx >= 0) {
                        ByteBuffer encodedData = encoder.getOutputBuffer(encOutIdx);
                        if (encodedData != null && (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encInfo.size > 0 && muxerStarted) {
                            encodedData.position(encInfo.offset);
                            encodedData.limit(encInfo.offset + encInfo.size);
                            muxer.writeSampleData(muxerVideoTrack, encodedData, encInfo);
                        }
                        encoder.releaseOutputBuffer(encOutIdx, false);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    } else if (encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
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
            try { if (retriever != null) retriever.release(); } catch (Exception ignored) {}
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
            try { videoExtractor.release(); } catch (Exception ignored) {}
            try { audioExtractor.release(); } catch (Exception ignored) {}
            try { if (muxer != null) { if (muxerStarted) muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
            if (cancelled && outputFile.exists()) {
                outputFile.delete();
            }
        }

        return !cancelled;
    }

    private static Bitmap yuvImageToBitmap(Image image, int rotation, int outW, int outH) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        int[] pixels = new int[width * height];
        for (int j = 0; j < height; j++) {
            int yRow = j * yRowStride;
            int uvRow = (j >> 1) * uvRowStride;
            for (int i = 0; i < width; i++) {
                int y = (yBuf.get(yRow + i) & 0xff) - 16;
                if (y < 0) y = 0;
                int uvIdx = uvRow + (i >> 1) * uvPixelStride;
                int u = (uBuf.get(uvIdx) & 0xff) - 128;
                int v = (vBuf.get(uvIdx) & 0xff) - 128;

                int y1192 = 1192 * y;
                int r = (y1192 + 1634 * v) >> 10;
                int g = (y1192 - 833 * v - 400 * u) >> 10;
                int b = (y1192 + 2066 * u) >> 10;

                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);

                pixels[j * width + i] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, width, height, matrix, true);
            bmp.recycle();
            bmp = rotated;
        }
        if (bmp.getWidth() != outW || bmp.getHeight() != outH) {
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, outW, outH, true);
            bmp.recycle();
            bmp = scaled;
        }
        return bmp;
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

                if (evenRow && (i & 1) == 0 && uv
