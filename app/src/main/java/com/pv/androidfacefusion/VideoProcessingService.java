package com.pv.androidfacefusion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;

public class VideoProcessingService extends Service {

    public static final String ACTION_PROGRESS = "com.pv.androidfacefusion.VIDEO_PROGRESS";
    public static final String ACTION_COMPLETE = "com.pv.androidfacefusion.VIDEO_COMPLETE";
    public static final String ACTION_ERROR = "com.pv.androidfacefusion.VIDEO_ERROR";

    public static final String EXTRA_PERCENT = "percent";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_OUTPUT_PATH = "output_path";
    public static final String EXTRA_ERROR = "error";

    private static final String CHANNEL_ID = "video_swap_channel";
    private static final int NOTIF_ID = 1001;

    private static float[] pendingSourceEmbedding;
    private static Uri pendingVideoUri;
    private static int pendingMaxDim = 720;
    private static int pendingFps = 24;

    public static void setJobData(float[] embedding, Uri videoUri, int maxDimension, int fps) {
        pendingSourceEmbedding = embedding;
        pendingVideoUri = videoUri;
        pendingMaxDim = maxDimension;
        pendingFps = fps;
    }

    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Starting video face swap...", 0));

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FaceFusion::VideoSwapWakeLock");
            wakeLock.acquire(60 * 60 * 1000L); // Max 1 hour
        }

        new Thread(() -> {
            FaceDetector detector = null;
            FaceEmbedder embedder = null;
            FaceSwapper swapper = null;
            try {
                if (pendingSourceEmbedding == null || pendingVideoUri == null) {
                    throw new Exception("Missing video or source face data.");
                }

                detector = new FaceDetector(this);
                detector.initialize();
                embedder = new FaceEmbedder(this);
                embedder.initialize();
                swapper = new FaceSwapper(this);
                swapper.initialize();

                FaceFusionProcessor processor = new FaceFusionProcessor(detector, embedder, swapper);

                File outDir = new File(getCacheDir(), "video_results");
                if (!outDir.exists()) outDir.mkdirs();
                File outFile = new File(outDir, "swapped_video_" + System.currentTimeMillis() + ".mp4");

                VideoFaceSwapper.swapVideo(
                        this,
                        pendingVideoUri,
                        pendingSourceEmbedding,
                        processor,
                        pendingMaxDim,
                        pendingFps,
                        outFile,
                        (percent, currentFrame, totalFrames) -> {
                            String status = "Frame " + currentFrame + " / " + totalFrames + " (" + percent + "%)";
                            updateNotification(status, percent);
                            Intent pIntent = new Intent(ACTION_PROGRESS);
                            pIntent.setPackage(getPackageName());
                            pIntent.putExtra(EXTRA_PERCENT, percent);
                            pIntent.putExtra(EXTRA_STATUS, status);
                            sendBroadcast(pIntent);
                        }
                );

                updateNotification("Video Face Swap Complete!", 100);
                Intent cIntent = new Intent(ACTION_COMPLETE);
                cIntent.setPackage(getPackageName());
                cIntent.putExtra(EXTRA_OUTPUT_PATH, outFile.getAbsolutePath());
                sendBroadcast(cIntent);

            } catch (Exception e) {
                e.printStackTrace();
                Intent eIntent = new Intent(ACTION_ERROR);
                eIntent.setPackage(getPackageName());
                eIntent.putExtra(EXTRA_ERROR, e.getMessage());
                sendBroadcast(eIntent);
            } finally {
                if (detector != null) detector.close();
                if (embedder != null) embedder.close();
                if (swapper != null) swapper.close();
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                stopForeground(true);
                stopSelf();
            }
        }).start();

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Video Face Swap", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, int progress) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Face Fusion Video")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setProgress(100, progress, progress == 0)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text, int progress) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIF_ID, buildNotification(text, progress));
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
