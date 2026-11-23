package com.pv.androidfacefusion;

import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.MediaController;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class VideoPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_PATH = "video_path";

    private VideoView videoView;
    private ScaleGestureDetector scaleDetector;
    private float scaleFactor = 1.0f;
    private float lastTouchX, lastTouchY;
    private float posX = 0f, posY = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        videoView = new VideoView(this);
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        );
        root.addView(videoView, videoParams);

        ImageButton btnClose = new ImageButton(this);
        btnClose.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        btnClose.setBackgroundColor(0x66000000);
        btnClose.setColorFilter(0xFFFFFFFF);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(120, 120, Gravity.TOP | Gravity.END);
        closeParams.setMargins(24, 48, 24, 24);
        btnClose.setOnClickListener(v -> finish());
        root.addView(btnClose, closeParams);

        setContentView(root);

        String path = getIntent().getStringExtra(EXTRA_VIDEO_PATH);
        if (path != null) {
            MediaController controller = new MediaController(this);
            controller.setAnchorView(videoView);
            videoView.setMediaController(controller);
            videoView.setVideoURI(Uri.fromFile(new File(path)));
            videoView.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                videoView.start();
            });
        }

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleFactor *= detector.getScaleFactor();
                scaleFactor = Math.max(1.0f, Math.min(scaleFactor, 5.0f));
                videoView.setScaleX(scaleFactor);
                videoView.setScaleY(scaleFactor);
                if (scaleFactor == 1.0f) {
                    posX = 0f;
                    posY = 0f;
                    videoView.setTranslationX(0f);
                    videoView.setTranslationY(0f);
                }
                return true;
            }
        });

        root.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            if (scaleFactor > 1.0f) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!scaleDetector.isInProgress()) {
                            posX += event.getX() - lastTouchX;
                            posY += event.getY() - lastTouchY;
                            videoView.setTranslationX(posX);
                            videoView.setTranslationY(posY);
                            lastTouchX = event.getX();
                            lastTouchY = event.getY();
                        }
                        break;
                }
            }
            return true;
        });
    }
}
