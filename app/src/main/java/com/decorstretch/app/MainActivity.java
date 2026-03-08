package com.decorstretch.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.decorstretch.app.databinding.ActivityMainBinding;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private Bitmap originalBitmap;
    private Bitmap stretchedBitmap;
    private Uri cameraImageUri;
    private boolean showingOriginal = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Activity Result Launchers ──────────────────────────────────────────────

    private final ActivityResultLauncher<Intent> cameraLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && cameraImageUri != null) {
                loadBitmapFromUri(cameraImageUri);
            }
        });

    private final ActivityResultLauncher<Intent> galleryLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) loadBitmapFromUri(uri);
            }
        });

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), perms -> {
            boolean allGranted = true;
            for (Boolean granted : perms.values()) {
                if (!granted) { allGranted = false; break; }
            }
            if (allGranted) showSourceChooser();
            else Toast.makeText(this, "Permissions are required to select an image", Toast.LENGTH_LONG).show();
        });

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnSelectImage.setOnClickListener(v -> checkPermissionsAndProceed());
        binding.btnApplyStretch.setOnClickListener(v -> applyDecorrelationStretch());
        binding.btnSave.setOnClickListener(v -> saveImage());
        binding.btnToggle.setOnClickListener(v -> togglePreview());

        updateUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private void checkPermissionsAndProceed() {
        String[] perms = getRequiredPermissions();
        boolean allGranted = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) showSourceChooser();
        else permissionLauncher.launch(perms);
    }

    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES};
        } else {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE};
        }
    }

    // ── Source Chooser ────────────────────────────────────────────────────────

    private void showSourceChooser() {
        new AlertDialog.Builder(this)
            .setTitle("Select Image Source")
            .setItems(new String[]{"📷  Camera", "🖼  Gallery"}, (dialog, which) -> {
                if (which == 0) openCamera();
                else openGallery();
            })
            .show();
    }

    private void openCamera() {
        try {
            File photoFile = createImageFile();
            cameraImageUri = FileProvider.getUriForFile(this,
                getApplicationContext().getPackageName() + ".fileprovider", photoFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
            cameraLauncher.launch(intent);
        } catch (IOException e) {
            Toast.makeText(this, "Could not create image file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        galleryLauncher.launch(intent);
    }

    private File createImageFile() throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile("IMG_" + timestamp + "_", ".jpg", storageDir);
    }

    // ── Image Loading ─────────────────────────────────────────────────────────

    private void loadBitmapFromUri(Uri uri) {
        setProcessing(true, "Loading image…");
        executor.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                // Decode with sub-sampling if the image is very large
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opts);

                int maxDim = 2048;
                int sample = 1;
                while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2;

                opts.inJustDecodeBounds = false;
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

                try (InputStream is2 = getContentResolver().openInputStream(uri)) {
                    Bitmap bmp = BitmapFactory.decodeStream(is2, null, opts);
                    mainHandler.post(() -> {
                        originalBitmap = bmp;
                        stretchedBitmap = null;
                        showingOriginal = true;
                        binding.imageView.setImageBitmap(originalBitmap);
                        binding.tvStatus.setText("Image loaded: " + bmp.getWidth() + " × " + bmp.getHeight() + " px");
                        setProcessing(false, null);
                        updateUI();
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "Failed to load image: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    setProcessing(false, null);
                });
            }
        });
    }

    // ── Decorrelation Stretch ─────────────────────────────────────────────────

    private void applyDecorrelationStretch() {
        if (originalBitmap == null) return;
        setProcessing(true, "Applying decorrelation stretch…");

        executor.execute(() -> {
            Bitmap result = DecorrelationStretch.apply(originalBitmap, (percent, message) ->
                mainHandler.post(() -> {
                    binding.progressBar.setProgress(percent);
                    binding.tvStatus.setText(message);
                }));

            mainHandler.post(() -> {
                stretchedBitmap = result;
                showingOriginal = false;
                binding.imageView.setImageBitmap(stretchedBitmap);
                binding.tvStatus.setText("Decorrelation stretch applied ✓");
                setProcessing(false, null);
                updateUI();
            });
        });
    }

    // ── Save Image ────────────────────────────────────────────────────────────

    private void saveImage() {
        if (stretchedBitmap == null) return;
        setProcessing(true, "Saving…");

        executor.execute(() -> {
            try {
                String filename = "DecorStretch_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";

                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
                }

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Could not create media URI");

                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    stretchedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, os);
                }

                mainHandler.post(() -> {
                    Toast.makeText(this, "Saved to Pictures/" + filename, Toast.LENGTH_LONG).show();
                    binding.tvStatus.setText("Image saved to gallery ✓");
                    setProcessing(false, null);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    setProcessing(false, null);
                });
            }
        });
    }

    // ── Toggle Preview ────────────────────────────────────────────────────────

    private void togglePreview() {
        if (originalBitmap == null || stretchedBitmap == null) return;
        showingOriginal = !showingOriginal;
        binding.imageView.setImageBitmap(showingOriginal ? originalBitmap : stretchedBitmap);
        binding.btnToggle.setText(showingOriginal ? "Show Result" : "Show Original");
        binding.tvStatus.setText(showingOriginal ? "Showing original" : "Showing stretched result");
    }

    // ── UI Helpers ────────────────────────────────────────────────────────────

    private void setProcessing(boolean processing, String message) {
        binding.progressBar.setVisibility(processing ? View.VISIBLE : View.GONE);
        if (!processing) binding.progressBar.setProgress(0);
        binding.btnSelectImage.setEnabled(!processing);
        binding.btnApplyStretch.setEnabled(!processing && originalBitmap != null);
        binding.btnSave.setEnabled(!processing && stretchedBitmap != null);
        binding.btnToggle.setEnabled(!processing && originalBitmap != null && stretchedBitmap != null);
        if (message != null) binding.tvStatus.setText(message);
    }

    private void updateUI() {
        boolean hasOriginal = originalBitmap != null;
        boolean hasResult = stretchedBitmap != null;
        binding.btnApplyStretch.setEnabled(hasOriginal);
        binding.btnSave.setEnabled(hasResult);
        binding.btnToggle.setEnabled(hasOriginal && hasResult);
        binding.btnToggle.setText("Show Original");
        if (!hasOriginal) {
            binding.tvStatus.setText("Select or capture an image to begin");
        }
    }
}
