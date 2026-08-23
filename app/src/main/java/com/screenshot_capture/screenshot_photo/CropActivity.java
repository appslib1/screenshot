package com.screenshot_capture.screenshot_photo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class CropActivity extends AppCompatActivity {

    private CropOverlayView cropView;
    /** Chemin de fichier legacy (cache privé) ; {@code null} si l'item est une URI MediaStore. */
    private File currentImageFile;
    /** URI MediaStore de l'item à réécrire ; {@code null} si l'item est un fichier legacy. */
    private Uri currentImageUri;
    private Bitmap sourceBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        Toolbar toolbar = findViewById(R.id.toolbar);
        LinearLayout cropBottomBar = findViewById(R.id.cropBottomBar);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cropRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            cropBottomBar.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom);
            return insets;
        });
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.cropImage);
        }

        cropView = findViewById(R.id.cropView);

        String imgPath = getIntent().getStringExtra("img_uri");
        if (imgPath == null) {
            finish();
            return;
        }

        if (imgPath.startsWith("content://")) {
            currentImageUri = Uri.parse(imgPath);
            try (InputStream in = getContentResolver().openInputStream(currentImageUri)) {
                sourceBitmap = BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                sourceBitmap = null;
            }
        } else {
            currentImageFile = new File(imgPath);
            sourceBitmap = BitmapFactory.decodeFile(imgPath);
        }

        if (sourceBitmap == null) {
            Toast.makeText(this, R.string.cropError, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        cropView.setBitmap(sourceBitmap);

        findViewById(R.id.resetBtn).setOnClickListener(v -> cropView.resetCrop());
        findViewById(R.id.saveBtn).setOnClickListener(v -> saveCroppedImage());
    }

    private void saveCroppedImage() {
        if (currentImageFile == null && currentImageUri == null) return;
        Bitmap cropped = cropView.getCroppedBitmap();
        if (cropped == null) {
            Toast.makeText(this, R.string.cropInvalidArea, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Bitmap.CompressFormat format = isPngTarget()
                    ? Bitmap.CompressFormat.PNG
                    : Bitmap.CompressFormat.JPEG;
            try (OutputStream out = openTargetOutputStream()) {
                if (out == null) throw new java.io.IOException("null OutputStream");
                cropped.compress(format, 100, out);
                out.flush();
            }
            Toast.makeText(this, R.string.imageCropped, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, R.string.cropError, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isPngTarget() {
        // Nos captures sont toujours en PNG ; pour un fichier legacy on garde l'extension.
        if (currentImageFile != null) {
            return currentImageFile.getName().toLowerCase().endsWith(".png");
        }
        return true;
    }

    private OutputStream openTargetOutputStream() throws Exception {
        if (currentImageUri != null) {
            // "w" tronque le contenu existant avant réécriture.
            return getContentResolver().openOutputStream(currentImageUri, "w");
        }
        return new FileOutputStream(currentImageFile);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sourceBitmap != null && !sourceBitmap.isRecycled()) {
            sourceBitmap.recycle();
        }
    }
}
