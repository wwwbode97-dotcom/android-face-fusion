package com.pv.androidfacefusion;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.FutureTarget;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SavedFacesAdapter.OnFaceActionListener {

    private static final String TAG = "MainActivity";

    private enum ImagePickerTarget {
        STANDARD_SOURCE,
        STANDARD_TARGET,
        ADD_SAVED_FACE,
        LIBRARY_TARGET,
        VIDEO_SOURCE
    }

    // Mode Switcher Views
    private MaterialButtonToggleGroup modeToggleGroup;
    private MaterialButton btnModeStandard, btnModeLibrary, btnModeVideo;
    private LinearLayout standardSwapContainer, librarySwapContainer, videoSwapContainer;

    // Standard Swap Views
    private ImageView sourceImageView, resultImageView;
    private FaceOverlayImageView targetImageView;
    private HorizontalScrollView targetFaceChipScrollView;
    private ChipGroup targetFaceChipGroup;
    private TextView targetFaceStatusText;
    private List<FaceDetector.Face> targetFacesList = new ArrayList<>();
    private MaterialButton btnSelectSourceLocal, btnSelectSourceUrl;
    private MaterialButton btnSelectTargetLocal, btnSelectTargetUrl;
    private MaterialButton btnProcess, btnReset;

    // Library Views
    private MaterialButton btnAddSavedFace;
    private TextView emptyLibraryText;
    private RecyclerView savedFacesRecyclerView;
    private SavedFacesAdapter savedFacesAdapter;
    private FaceLibraryManager libraryManager;

    private FaceOverlayImageView libraryTargetImageView;
    private MaterialButton btnSelectLibraryTargetLocal, btnSelectLibraryTargetUrl;
    private TextView libraryTargetStatusText;
    private RecyclerView targetMappingRecyclerView;
    private TargetFaceMappingAdapter targetMappingAdapter;
    private MaterialButton btnLibraryProcess, btnLibraryReset;

    // Video Swap Views
    private ImageView videoSourceImageView;
    private MaterialButton btnSelectVideoSourceFace, btnSelectTargetVideo, btnProcessVideo;
    private TextView selectedVideoInfoText, videoProgressStatusText;
    private ChipGroup videoResolutionGroup, videoFpsGroup;
    private MaterialCardView videoProgressCard;
    private LinearProgressIndicator videoProgressBar;
    private Bitmap videoSourceBitmap;
    private Uri selectedVideoUri;
    private String resultVideoPath;

    // Shared Result & Overlay Views
    private VideoView resultVideoView;
    private MaterialButton btnSaveResult, btnShareResult, btnFullscreenResult;
    private LinearProgressIndicator progressBar;
    private MaterialCardView resultCard;
    private FrameLayout downloadOverlay;
    private TextView overlayTitle, overlayStatus, overlayPercent;
    private LinearProgressIndicator overlayProgress;

    // Bitmaps
    private Bitmap sourceBitmap, targetBitmap, libraryTargetBitmap, resultBitmap;
    private List<FaceDetector.Face> libraryTargetFacesList = new ArrayList<>();

    // Models & Execution
    private FaceDetector faceDetector;
    private FaceEmbedder faceEmbedder;
    private FaceSwapper faceSwapper;
    private FaceFusionProcessor processor;
    private ExecutorService executorService;

    private ImagePickerTarget currentImageTarget = ImagePickerTarget.STANDARD_SOURCE;
    private ActivityResultLauncher<Intent> imagePickerLauncher;
    private ActivityResultLauncher<Intent> videoPickerLauncher;
    private ActivityResultLauncher<String> permissionLauncher;

    private final BroadcastReceiver videoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (VideoProcessingService.ACTION_PROGRESS.equals(action)) {
                int percent = intent.getIntExtra(VideoProcessingService.EXTRA_PERCENT, 0);
                String status = intent.getStringExtra(VideoProcessingService.EXTRA_STATUS);
                videoProgressCard.setVisibility(View.VISIBLE);
                videoProgressStatusText.setText(status);
                videoProgressBar.setProgressCompat(percent, true);
            } else if (VideoProcessingService.ACTION_COMPLETE.equals(action)) {
                videoProgressCard.setVisibility(View.GONE);
                btnProcessVideo.setEnabled(true);
                resultVideoPath = intent.getStringExtra(VideoProcessingService.EXTRA_OUTPUT_PATH);
                resultBitmap = null;
                showVideoResult(resultVideoPath);
                Toast.makeText(MainActivity.this, "Video face swap completed!", Toast.LENGTH_LONG).show();
            } else if (VideoProcessingService.ACTION_ERROR.equals(action)) {
                videoProgressCard.setVisibility(View.GONE);
                btnProcessVideo.setEnabled(true);
                String err = intent.getStringExtra(VideoProcessingService.EXTRA_ERROR);
                showError("Video processing failed: " + err);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        libraryManager = new FaceLibraryManager(this);

        initViews();
        initModels();
        setupListeners();
        requestPermissions();
        loadSavedFaces();
        registerVideoReceiver();
    }

    private void registerVideoReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(VideoProcessingService.ACTION_PROGRESS);
        filter.addAction(VideoProcessingService.ACTION_COMPLETE);
        filter.addAction(VideoProcessingService.ACTION_ERROR);
        ContextCompat.registerReceiver(this, videoReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void initViews() {
        modeToggleGroup = findViewById(R.id.modeToggleGroup);
        btnModeStandard = findViewById(R.id.btnModeStandard);
        btnModeLibrary = findViewById(R.id.btnModeLibrary);
        btnModeVideo = findViewById(R.id.btnModeVideo);
        standardSwapContainer = findViewById(R.id.standardSwapContainer);
        librarySwapContainer = findViewById(R.id.librarySwapContainer);
        videoSwapContainer = findViewById(R.id.videoSwapContainer);

        sourceImageView = findViewById(R.id.sourceImageView);
        targetImageView = findViewById(R.id.targetImageView);
        targetFaceChipScrollView = findViewById(R.id.targetFaceChipScrollView);
        targetFaceChipGroup = findViewById(R.id.targetFaceChipGroup);
        targetFaceStatusText = findViewById(R.id.targetFaceStatusText);
        btnSelectSourceLocal = findViewById(R.id.btnSelectSourceLocal);
        btnSelectSourceUrl = findViewById(R.id.btnSelectSourceUrl);
        btnSelectTargetLocal = findViewById(R.id.btnSelectTargetLocal);
        btnSelectTargetUrl = findViewById(R.id.btnSelectTargetUrl);
        btnProcess = findViewById(R.id.btnProcess);
        btnReset = findViewById(R.id.btnReset);

        btnAddSavedFace = findViewById(R.id.btnAddSavedFace);
        emptyLibraryText = findViewById(R.id.emptyLibraryText);
        savedFacesRecyclerView = findViewById(R.id.savedFacesRecyclerView);
        savedFacesRecyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        savedFacesAdapter = new SavedFacesAdapter(this, libraryManager, this);
        savedFacesRecyclerView.setAdapter(savedFacesAdapter);

        libraryTargetImageView = findViewById(R.id.libraryTargetImageView);
        btnSelectLibraryTargetLocal = findViewById(R.id.btnSelectLibraryTargetLocal);
        btnSelectLibraryTargetUrl = findViewById(R.id.btnSelectLibraryTargetUrl);
        libraryTargetStatusText = findViewById(R.id.libraryTargetStatusText);
        targetMappingRecyclerView = findViewById(R.id.targetMappingRecyclerView);
        targetMappingRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        targetMappingAdapter = new TargetFaceMappingAdapter(this);
        targetMappingRecyclerView.setAdapter(targetMappingAdapter);
        btnLibraryProcess = findViewById(R.id.btnLibraryProcess);
        btnLibraryReset = findViewById(R.id.btnLibraryReset);

        // Video Views
        videoSourceImageView = findViewById(R.id.videoSourceImageView);
        btnSelectVideoSourceFace = findViewById(R.id.btnSelectVideoSourceFace);
        btnSelectTargetVideo = findViewById(R.id.btnSelectTargetVideo);
        btnProcessVideo = findViewById(R.id.btnProcessVideo);
        selectedVideoInfoText = findViewById(R.id.selectedVideoInfoText);
        videoResolutionGroup = findViewById(R.id.videoResolutionGroup);
        videoFpsGroup = findViewById(R.id.videoFpsGroup);
        videoProgressCard = findViewById(R.id.videoProgressCard);
        videoProgressStatusText = findViewById(R.id.videoProgressStatusText);
        videoProgressBar = findViewById(R.id.videoProgressBar);

        // Result Views (Share button hidden)
        resultImageView = findViewById(R.id.resultImageView);
        resultVideoView = findViewById(R.id.resultVideoView);
        btnSaveResult = findViewById(R.id.btnSaveResult);
        btnShareResult = findViewById(R.id.btnShareResult);
        if (btnShareResult != null) {
            btnShareResult.setVisibility(View.GONE);
        }
        btnFullscreenResult = findViewById(R.id.btnFullscreenResult);
        progressBar = findViewById(R.id.progressBar);
        resultCard = findViewById(R.id.resultCard);

        downloadOverlay = findViewById(R.id.downloadOverlay);
        overlayTitle = findViewById(R.id.overlayTitle);
        overlayStatus = findViewById(R.id.overlayStatus);
        overlayPercent = findViewById(R.id.overlayPercent);
        overlayProgress = findViewById(R.id.overlayProgress);

        executorService = Executors.newSingleThreadExecutor();

        imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        loadImageFromUri(imageUri, currentImageTarget);
                    }
                }
            }
        );

        videoPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        selectedVideoUri = uri;
                        selectedVideoInfoText.setText("Selected video ready: " + uri.getLastPathSegment());
                    }
                }
            }
        );

        permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {}
        );
    }

    private void loadSavedFaces() {
        executorService.execute(() -> {
            List<SavedFace> savedFaces = libraryManager.getSavedFaces();
            runOnUiThread(() -> {
                savedFacesAdapter.setSavedFaces(savedFaces);
                if (savedFaces.isEmpty()) {
                    emptyLibraryText.setVisibility(View.VISIBLE);
                    savedFacesRecyclerView.setVisibility(View.GONE);
                } else {
                    emptyLibraryText.setVisibility(View.GONE);
                    savedFacesRecyclerView.setVisibility(View.VISIBLE);
                }
                if (libraryTargetBitmap != null && !libraryTargetFacesList.isEmpty()) {
                    targetMappingAdapter.updateSavedFaces(savedFaces);
                }
            });
        });
    }

    private void showOverlay(String title, String status) {
        downloadOverlay.setVisibility(View.VISIBLE);
        overlayTitle.setText(title);
        overlayStatus.setText(status);
        overlayPercent.setText("");
        overlayProgress.setIndeterminate(true);
    }

    private void updateOverlay(String status, int progress) {
        overlayStatus.setText(status);
        if (progress >= 0) {
            overlayProgress.setIndeterminate(false);
            overlayProgress.setProgressCompat(progress, true);
            overlayPercent.setText(progress + "%");
        } else {
            overlayProgress.setIndeterminate(true);
            overlayPercent.setText("");
        }
    }

    private void hideOverlay() {
        downloadOverlay.setVisibility(View.GONE);
    }

    private void initModels() {
        btnProcess.setEnabled(false);
        btnLibraryProcess.setEnabled(false);
        btnProcessVideo.setEnabled(false);

        executorService.execute(() -> {
            try {
                ModelDownloader downloader = new ModelDownloader(this);
                boolean needsDownload = !downloader.areAllModelsDownloaded();

                runOnUiThread(() -> {
                    if (needsDownload) {
                        showOverlay("Setting up AI Models", "Preparing download...");
                    } else {
                        showOverlay("Loading AI Models", "Initializing...");
                    }
                });

                if (needsDownload) {
                    downloader.setCallback(new ModelDownloader.DownloadCallback() {
                        @Override
                        public void onProgress(String modelName, int progress) {
                            String displayName = getModelDisplayName(modelName);
                            runOnUiThread(() -> updateOverlay("Downloading " + displayName, progress));
                        }

                        @Override
                        public void onComplete(String modelName) {
                            String displayName = getModelDisplayName(modelName);
                            runOnUiThread(() -> updateOverlay(displayName + " downloaded", 100));
                        }

                        @Override
                        public void onError(String modelName, String error) {
                            Log.e(TAG, "Download error: " + modelName + " - " + error);
                        }
                    });
                }

                runOnUiThread(() -> updateOverlay(needsDownload ? "Downloading Face Detector (~16 MB)" : "Loading Face Detector", -1));
                faceDetector = new FaceDetector(this);
                faceDetector.initialize();

                runOnUiThread(() -> updateOverlay(needsDownload ? "Downloading Face Embedder (~166 MB)" : "Loading Face Embedder", -1));
                faceEmbedder = new FaceEmbedder(this);
                faceEmbedder.initialize();

                runOnUiThread(() -> updateOverlay(needsDownload ? "Downloading Face Swapper (~553 MB)" : "Loading Face Swapper", -1));
                faceSwapper = new FaceSwapper(this);
                faceSwapper.initialize();

                processor = new FaceFusionProcessor(faceDetector, faceEmbedder, faceSwapper);

                runOnUiThread(() -> {
                    hideOverlay();
                    btnProcess.setEnabled(true);
                    btnLibraryProcess.setEnabled(true);
                    btnProcessVideo.setEnabled(true);
                    Toast.makeText(this, "All models loaded!", Toast.LENGTH_SHORT).show();
                    if (targetBitmap != null) detectTargetFacesAsync(targetBitmap);
                    if (libraryTargetBitmap != null) detectLibraryTargetFacesAsync(libraryTargetBitmap);
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    hideOverlay();
                    showError("Failed to load models: " + e.getMessage());
                });
            }
        });
    }

    private String getModelDisplayName(String modelName) {
        switch (modelName) {
            case "det_10g.onnx": return "Face Detector";
            case "w600k_r50.onnx": return "Face Embedder";
            case "inswapper_128.onnx": return "Face Swapper";
            default: return modelName;
        }
    }

    private void setupListeners() {
        modeToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            standardSwapContainer.setVisibility(checkedId == R.id.btnModeStandard ? View.VISIBLE : View.GONE);
            librarySwapContainer.setVisibility(checkedId == R.id.btnModeLibrary ? View.VISIBLE : View.GONE);
            videoSwapContainer.setVisibility(checkedId == R.id.btnModeVideo ? View.VISIBLE : View.GONE);
            if (checkedId == R.id.btnModeLibrary) loadSavedFaces();
        });

        btnSelectSourceLocal.setOnClickListener(v -> {
            currentImageTarget = ImagePickerTarget.STANDARD_SOURCE;
            openImagePicker();
        });

        btnSelectSourceUrl.setOnClickListener(v -> {
            currentImageTarget = ImagePickerTarget.STANDARD_SOURCE;
            showUrlInputDialog();
        });

        btnSelectTargetLocal.setOnClickListener(v -> {
            currentImageTarget = ImagePickerTarget.STANDARD_TARGET;
            openImagePicker();
        });

        btnSelectTargetUrl.setOnClickListener(v -> {
            currentImageTarget = ImagePickerTarget.STANDARD_TARGET;
            showUrlInputDialog();
        });

        btnProcess.setOnClickListener(v -> processFaceFusion());
        btnReset.setOnClickListener(v -> resetStandardSession());

        btnAddSavedFace.setOnClickListener(v -> showAddSavedFaceSourceDialog());

        btnSelectLibraryTargetLocal.setOnClickListener(v -> {
            currentImageTarget = ImagePickerTarget.LIBRARY_TARGET;
            openImagePicker();
        });

        btnSelectLibraryTargetUrl.setOnClickListener(v -> {
            currentImageTarget = ImagePickerTarget.LIBRARY_TARGET;
            showUrlInputDialog();
        });

        btnLibraryProcess.setOnClickListener(v -> processLibraryFaceFusion());
        btnLibraryReset.setOnClickListener(v -> resetLibrarySession());

        // Video Listeners
        btnSelectVideoSourceFace.setOnClickListener(v -> {
            currentImageTarget = ImagePickerTarget.VIDEO_SOURCE;
            openImagePicker();
        });

        btnSelectTargetVideo.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            intent.setType("video/*");
            videoPickerLauncher.launch(intent);
        });

        btnProcessVideo.setOnClickListener(v -> startBackgroundVideoSwap());

        btnSaveResult.setOnClickListener(v -> saveResult());
        btnFullscreenResult.setOnClickListener(v -> openFullscreenResult());

        sourceImageView.setOnClickListener(v -> openImagePreview(sourceBitmap));
        resultImageView.setOnClickListener(v -> openImagePreview(resultBitmap));
        targetImageView.setOnFaceSelectedListener(selectedIndices -> syncChipsWithOverlay());
    }

    private void startBackgroundVideoSwap() {
        if (videoSourceBitmap == null || selectedVideoUri == null) {
            Toast.makeText(this, "Please select both a source face and a target video", Toast.LENGTH_SHORT).show();
            return;
        }
        if (processor == null) {
            Toast.makeText(this, "Models are still loading", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        int maxDim = 720;
        int resId = videoResolutionGroup.getCheckedChipId();
        if (resId == R.id.chipRes480) maxDim = 480;
        else if (resId == R.id.chipRes1080) maxDim = 1080;

        int fps = 24;
        int fpsId = videoFpsGroup.getCheckedChipId();
        if (fpsId == R.id.chipFps15) fps = 15;
        else if (fpsId == R.id.chipFps30) fps = 30;

        final int finalMaxDim = maxDim;
        final int finalFps = fps;

        btnProcessVideo.setEnabled(false);
        resultCard.setVisibility(View.GONE);
        videoProgressCard.setVisibility(View.VISIBLE);
        videoProgressStatusText.setText("Extracting source face...");
        videoProgressBar.setIndeterminate(true);

        executorService.execute(() -> {
            try {
                float[] embedding = processor.extractSourceEmbedding(videoSourceBitmap);
                runOnUiThread(() -> {
                    videoProgressBar.setIndeterminate(false);
                    videoProgressBar.setProgressCompat(0, false);
                    VideoProcessingService.setJobData(embedding, selectedVideoUri, finalMaxDim, finalFps);
                    Intent serviceIntent = new Intent(this, VideoProcessingService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    Toast.makeText(this, "Processing in background! You can minimize the app.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    videoProgressCard.setVisibility(View.GONE);
                    btnProcessVideo.setEnabled(true);
                    showError("Failed to extract source face: " + e.getMessage());
                });
            }
        });
    }

    private void showImageResult(Bitmap bitmap) {
        resultBitmap = bitmap;
        resultVideoPath = null;
        resultVideoView.setVisibility(View.GONE);
        resultImageView.setVisibility(View.VISIBLE);
        resultImageView.setImageBitmap(bitmap);
        resultCard.setVisibility(View.VISIBLE);
    }

    private void showVideoResult(String path) {
        if (path == null) return;
        resultImageView.setVisibility(View.GONE);
        resultVideoView.setVisibility(View.VISIBLE);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(resultVideoView);
        resultVideoView.setMediaController(controller);
        resultVideoView.setVideoURI(Uri.fromFile(new File(path)));
        resultVideoView.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            resultVideoView.start();
        });
        resultCard.setVisibility(View.VISIBLE);
    }

    private void openFullscreenResult() {
        if (resultVideoPath != null) {
            Intent intent = new Intent(this, VideoPreviewActivity.class);
            intent.putExtra(VideoPreviewActivity.EXTRA_VIDEO_PATH, resultVideoPath);
            startActivity(intent);
        } else if (resultBitmap != null) {
            openImagePreview(resultBitmap);
        }
    }

    private void openImagePreview(Bitmap bitmap) {
        if (bitmap == null) return;
        ImagePreviewActivity.setPendingBitmap(bitmap);
        startActivity(new Intent(this, ImagePreviewActivity.class));
    }

    private void resetStandardSession() {
        if (sourceBitmap != null) { sourceBitmap.recycle(); sourceBitmap = null; }
        if (targetBitmap != null) { targetBitmap.recycle(); targetBitmap = null; }
        if (resultBitmap != null) { resultBitmap.recycle(); resultBitmap = null; }
        resultVideoPath = null;

        sourceImageView.setImageDrawable(null);
        targetImageView.setImageDrawable(null);
        targetImageView.setFaces(null);
        targetFacesList.clear();

        targetFaceChipGroup.removeAllViews();
        targetFaceStatusText.setVisibility(View.GONE);
        targetFaceChipScrollView.setVisibility(View.GONE);

        resultImageView.setImageDrawable(null);
        resultCard.setVisibility(View.GONE);
    }

    private void resetLibrarySession() {
        if (libraryTargetBitmap != null) { libraryTargetBitmap.recycle(); libraryTargetBitmap = null; }
        if (resultBitmap != null) { resultBitmap.recycle(); resultBitmap = null; }
        resultVideoPath = null;

        libraryTargetImageView.setImageDrawable(null);
        libraryTargetImageView.setFaces(null);
        libraryTargetFacesList.clear();

        libraryTargetStatusText.setVisibility(View.GONE);
        targetMappingRecyclerView.setVisibility(View.GONE);
        targetMappingAdapter.setData(null, null, null);

        resultImageView.setImageDrawable(null);
        resultCard.setVisibility(View.GONE);
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        imagePickerLauncher.launch(intent);
    }

    private void showAddSavedFaceSourceDialog() {
        String[] options = {"Local Gallery Image", "Image URL"};
        new AlertDialog.Builder(this)
            .setTitle("Add Face to Library")
            .setItems(options, (dialog, which) -> {
                currentImageTarget = ImagePickerTarget.ADD_SAVED_FACE;
                if (which == 0) openImagePicker();
                else showUrlInputDialog();
            })
            .show();
    }

    private void showUrlInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Image URL");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://example.com/image.jpg");
        builder.setView(input);

        builder.setPositiveButton("Load", (dialog, which) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty()) loadImageFromUrl(url, currentImageTarget);
            else Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void loadImageFromUri(Uri uri, ImagePickerTarget target) {
        executorService.execute(() -> {
            try {
                InputStream inputStream = getContentResolver().openInputStream(uri);
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                inputStream.close();

                if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                    Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    bitmap.recycle();
                    bitmap = converted;
                }

                if (bitmap.getWidth() > 1024 || bitmap.getHeight() > 1024) {
                    bitmap = ImageUtils.resizeImage(bitmap, 1024);
                }

                Bitmap finalBitmap = bitmap;
                runOnUiThread(() -> handleLoadedImage(finalBitmap, target));
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> showError("Failed to load image: " + e.getMessage()));
            }
        });
    }

    private void loadImageFromUrl(String url, ImagePickerTarget target) {
        progressBar.setVisibility(View.VISIBLE);

        executorService.execute(() -> {
            try {
                FutureTarget<Bitmap> futureTarget = Glide.with(this)
                    .asBitmap()
                    .load(url)
                    .submit();

                Bitmap bitmap = futureTarget.get();

                if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                    Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    bitmap.recycle();
                    bitmap = converted;
                }

                if (bitmap.getWidth() > 1024 || bitmap.getHeight() > 1024) {
                    bitmap = ImageUtils.resizeImage(bitmap, 1024);
                }

                Bitmap finalBitmap = bitmap;
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    handleLoadedImage(finalBitmap, target);
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showError("Failed to load image from URL: " + e.getMessage());
                });
            }
        });
    }

    private void handleLoadedImage(Bitmap bitmap, ImagePickerTarget target) {
        switch (target) {
            case STANDARD_SOURCE:
                sourceBitmap = bitmap;
                sourceImageView.setImageBitmap(bitmap);
                break;
            case STANDARD_TARGET:
                targetBitmap = bitmap;
                targetImageView.setImageBitmap(bitmap);
                detectTargetFacesAsync(bitmap);
                break;
            case ADD_SAVED_FACE:
                processAddFaceToLibraryAsync(bitmap);
                break;
            case LIBRARY_TARGET:
                libraryTargetBitmap = bitmap;
                libraryTargetImageView.setImageBitmap(bitmap);
                detectLibraryTargetFacesAsync(bitmap);
                break;
            case VIDEO_SOURCE:
                videoSourceBitmap = bitmap;
                videoSourceImageView.setImageBitmap(bitmap);
                break;
        }
    }

    private void processAddFaceToLibraryAsync(Bitmap bitmap) {
        if (faceDetector == null || faceEmbedder == null) {
            Toast.makeText(this, "AI models are still loading, please wait", Toast.LENGTH_SHORT).show();
            return;
        }

        showOverlay("Adding Face to Library", "Detecting face and extracting features...");

        executorService.execute(() -> {
            try {
                List<FaceDetector.Face> faces = faceDetector.detectFaces(bitmap);
                if (faces.isEmpty()) {
                    runOnUiThread(() -> {
                        hideOverlay();
                        showError("No face detected in the selected image.");
                    });
                    return;
                }

                if (faces.size() == 1) {
                    extractAndPromptSaveFace(bitmap, faces.get(0));
                } else {
                    runOnUiThread(() -> {
                        hideOverlay();
                        showMultiFaceSelectionDialogForLibrary(bitmap, faces);
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    hideOverlay();
                    showError("Failed to process face: " + e.getMessage());
                });
            }
        });
    }

    private void showMultiFaceSelectionDialogForLibrary(Bitmap bitmap, List<FaceDetector.Face> faces) {
        String[] items = new String[faces.size()];
        for (int i = 0; i < faces.size(); i++) {
            items[i] = "Face " + (i + 1) + " (Confidence: " + Math.round(faces.get(i).score * 100) + "%)";
        }

        new AlertDialog.Builder(this)
            .setTitle("Select Face to Save")
            .setItems(items, (dialog, which) -> {
                showOverlay("Adding Face to Library", "Extracting face features...");
                executorService.execute(() -> {
                    try {
                        extractAndPromptSaveFace(bitmap, faces.get(which));
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            hideOverlay();
                            showError("Failed to extract face: " + e.getMessage());
                        });
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void extractAndPromptSaveFace(Bitmap bitmap, FaceDetector.Face face) throws Exception {
        Bitmap alignedFace = ImageUtils.alignFace(bitmap, face.landmarks, 112);
        float[] embedding = faceEmbedder.getEmbedding(alignedFace);
        alignedFace.recycle();

        Bitmap faceCrop = cropFaceCrop(bitmap, face.bbox);

        runOnUiThread(() -> {
            hideOverlay();
            promptFaceNameAndSave(faceCrop, embedding);
        });
    }

    private Bitmap cropFaceCrop(Bitmap src, RectF bbox) {
        try {
            int left = Math.max(0, (int) bbox.left);
            int top = Math.max(0, (int) bbox.top);
            int right = Math.min(src.getWidth(), (int) bbox.right);
            int bottom = Math.min(src.getHeight(), (int) bbox.bottom);
            int width = right - left;
            int height = bottom - top;
            if (width > 0 && height > 0) {
                return Bitmap.createBitmap(src, left, top, width, height);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return src;
    }

    private void promptFaceNameAndSave(Bitmap faceCrop, float[] embedding) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Name Saved Face");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint("e.g. John, Mom, Actor");
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) name = "Face " + (savedFacesAdapter.getItemCount() + 1);
            final String finalName = name;
            executorService.execute(() -> {
                libraryManager.saveFace(finalName, faceCrop, embedding);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Saved face '" + finalName + "'!", Toast.LENGTH_SHORT).show();
                    loadSavedFaces();
                });
            });
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    @Override
    public void onEditFaceName(SavedFace face) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Saved Face");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(face.getName());
        builder.setView(input);

        builder.setPositiveButton("Update", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                executorService.execute(() -> {
                    libraryManager.updateFaceName(face.getId(), newName);
                    runOnUiThread(this::loadSavedFaces);
                });
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    @Override
    public void onDeleteFace(SavedFace face) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Face")
            .setMessage("Are you sure you want to delete '" + face.getName() + "'?")
            .setPositiveButton("Delete", (dialog, which) -> {
                executorService.execute(() -> {
                    libraryManager.deleteFace(face.getId());
                    runOnUiThread(this::loadSavedFaces);
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void detectTargetFacesAsync(Bitmap bitmap) {
        if (faceDetector == null || bitmap == null) return;
        executorService.execute(() -> {
            try {
                List<FaceDetector.Face> faces = faceDetector.detectFaces(bitmap);
                runOnUiThread(() -> updateTargetFaceSelectionUI(faces));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (targetFaceStatusText != null) targetFaceStatusText.setVisibility(View.GONE);
                    if (targetFaceChipScrollView != null) targetFaceChipScrollView.setVisibility(View.GONE);
                });
            }
        });
    }

    private boolean isSyncingChips = false;

    private void updateTargetFaceSelectionUI(List<FaceDetector.Face> faces) {
        targetFacesList = (faces != null) ? faces : new ArrayList<>();
        targetImageView.setFaces(targetFacesList);

        targetFaceChipGroup.removeAllViews();
        targetFaceChipGroup.setOnCheckedStateChangeListener(null);

        if (targetFacesList.isEmpty()) {
            targetFaceStatusText.setText("No face detected in target image");
            targetFaceStatusText.setVisibility(View.VISIBLE);
            targetFaceChipScrollView.setVisibility(View.GONE);
            return;
        }

        targetFaceStatusText.setVisibility(View.VISIBLE);

        if (targetFacesList.size() == 1) {
            targetFaceStatusText.setText("1 face detected in target image:");
            targetFaceChipScrollView.setVisibility(View.VISIBLE);
            Chip chip = createFaceChip("Face 1", 0);
            chip.setChecked(true);
            targetFaceChipGroup.addView(chip);
        } else {
            targetFaceStatusText.setText(targetFacesList.size() + " faces detected:");
            targetFaceChipScrollView.setVisibility(View.VISIBLE);
            Chip allChip = createFaceChip("Select All (" + targetFacesList.size() + ")", -1);
            allChip.setChecked(true);
            targetFaceChipGroup.addView(allChip);
            for (int i = 0; i < targetFacesList.size(); i++) {
                Chip chip = createFaceChip("Face " + (i + 1), i);
                chip.setChecked(true);
                targetFaceChipGroup.addView(chip);
            }
        }

        for (int i = 0; i < targetFaceChipGroup.getChildCount(); i++) {
            View child = targetFaceChipGroup.getChildAt(i);
            if (child instanceof Chip && child.getTag() instanceof Integer) {
                Chip chip = (Chip) child;
                int index = (Integer) chip.getTag();
                chip.setOnClickListener(v -> {
                    if (isSyncingChips) return;
                    if (index == -1) {
                        if (chip.isChecked()) targetImageView.setSelectedFaceIndex(-1);
                        else targetImageView.setSelectedFaceIndices(new HashSet<>());
                    } else {
                        targetImageView.toggleFaceIndex(index);
                    }
                    syncChipsWithOverlay();
                });
            }
        }
    }

    private Chip createFaceChip(String label, int index) {
        Chip chip = new Chip(this);
        chip.setText(label);
        chip.setCheckable(true);
        chip.setClickable(true);
        chip.setTag(index);
        chip.setId(View.generateViewId());
        return chip;
    }

    private void syncChipsWithOverlay() {
        if (targetFaceChipGroup == null || targetFacesList == null) return;
        isSyncingChips = true;
        Set<Integer> selected = targetImageView.getSelectedFaceIndices();
        boolean allSelected = (selected.size() == targetFacesList.size() && !targetFacesList.isEmpty());

        for (int i = 0; i < targetFaceChipGroup.getChildCount(); i++) {
            View child = targetFaceChipGroup.getChildAt(i);
            if (child instanceof Chip && child.getTag() instanceof Integer) {
                Chip chip = (Chip) child;
                int tag = (Integer) chip.getTag();
                chip.setChecked(tag == -1 ? allSelected : selected.contains(tag));
            }
        }
        isSyncingChips = false;
    }

    private void detectLibraryTargetFacesAsync(Bitmap bitmap) {
        if (faceDetector == null || bitmap == null) return;
        executorService.execute(() -> {
            try {
                List<FaceDetector.Face> faces = faceDetector.detectFaces(bitmap);
                libraryTargetFacesList = (faces != null) ? faces : new ArrayList<>();
                List<SavedFace> savedFaces = libraryManager.getSavedFaces();

                runOnUiThread(() -> {
                    libraryTargetImageView.setFaces(libraryTargetFacesList);
                    if (libraryTargetFacesList.isEmpty()) {
                        libraryTargetStatusText.setText("No face detected in target image");
                        libraryTargetStatusText.setVisibility(View.VISIBLE);
                        targetMappingRecyclerView.setVisibility(View.GONE);
                    } else {
                        libraryTargetStatusText.setText(libraryTargetFacesList.size() + " face(s) detected:");
                        libraryTargetStatusText.setVisibility(View.VISIBLE);
                        targetMappingRecyclerView.setVisibility(View.VISIBLE);
                        targetMappingAdapter.setData(bitmap, libraryTargetFacesList, savedFaces);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    libraryTargetStatusText.setVisibility(View.GONE);
                    targetMappingRecyclerView.setVisibility(View.GONE);
                });
            }
        });
    }

    private void processFaceFusion() {
        if (sourceBitmap == null || targetBitmap == null) {
            Toast.makeText(this, "Please select both source and target images", Toast.LENGTH_SHORT).show();
            return;
        }
        if (processor == null) return;

        final Set<Integer> selectedFaceIndices = targetImageView.getSelectedFaceIndices();
        if (selectedFaceIndices == null || selectedFaceIndices.isEmpty()) {
            Toast.makeText(this, "Please select at least one target face", Toast.LENGTH_SHORT).show();
            return;
        }

        btnProcess.setEnabled(false);
        resultCard.setVisibility(View.GONE);
        showOverlay("Swapping Faces", "Processing face swap...");

        executorService.execute(() -> {
            try {
                Bitmap result = processor.processFaceFusion(sourceBitmap, targetBitmap, selectedFaceIndices);
                runOnUiThread(() -> {
                    hideOverlay();
                    btnProcess.setEnabled(true);
                    showImageResult(result);
                    Toast.makeText(this, "Face swap completed!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideOverlay();
                    btnProcess.setEnabled(true);
                    showError("Face fusion failed: " + e.getMessage());
                });
            }
        });
    }

    private void processLibraryFaceFusion() {
        if (libraryTargetBitmap == null || processor == null) return;

        Map<Integer, SavedFace> selectedMapping = targetMappingAdapter.getSelectedMapping();
        if (selectedMapping.isEmpty()) {
            Toast.makeText(this, "Please select at least one face from your library", Toast.LENGTH_LONG).show();
            return;
        }

        Map<Integer, float[]> embeddingMap = new HashMap<>();
        for (Map.Entry<Integer, SavedFace> entry : selectedMapping.entrySet()) {
            if (entry.getValue() != null) {
                embeddingMap.put(entry.getKey(), entry.getValue().getEmbedding());
            }
        }

        btnLibraryProcess.setEnabled(false);
        resultCard.setVisibility(View.GONE);
        showOverlay("Swapping Faces with Library", "Processing...");

        executorService.execute(() -> {
            try {
                Bitmap result = processor.processFaceFusionWithMapping(libraryTargetBitmap, embeddingMap);
                runOnUiThread(() -> {
                    hideOverlay();
                    btnLibraryProcess.setEnabled(true);
                    showImageResult(result);
                    Toast.makeText(this, "Library swap completed!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideOverlay();
                    btnLibraryProcess.setEnabled(true);
                    showError("Library swap failed: " + e.getMessage());
                });
            }
        });
    }

    private void saveResult() {
        if (resultVideoPath != null) {
            executorService.execute(() -> {
                try {
                    String fileName = "face_fusion_video_" + System.currentTimeMillis() + ".mp4";
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                    values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES);

                    Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (InputStream in = new FileInputStream(new File(resultVideoPath));
                             OutputStream out = getContentResolver().openOutputStream(uri)) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                        }
                        runOnUiThread(() -> Toast.makeText(this, "Video saved to Movies folder!", Toast.LENGTH_LONG).show());
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> showError("Failed to save video: " + e.getMessage()));
                }
            });
            return;
        }

        if (resultBitmap == null) return;
        executorService.execute(() -> {
            try {
                String fileName = "face_fusion_" + System.currentTimeMillis() + ".jpg";
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
                    out.close();
                    runOnUiThread(() -> Toast.makeText(this, "Image saved to Pictures folder", Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> showError("Failed to save image: " + e.getMessage()));
            }
        });
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.INTERNET);
        }
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(videoReceiver); } catch (Exception ignored) {}
        executorService.shutdown();
        if (faceDetector != null) faceDetector.close();
        if (faceEmbedder != null) faceEmbedder.close();
        if (faceSwapper != null) faceSwapper.close();
    }
}
