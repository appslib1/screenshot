package com.screenshot_capture.screenshot_photo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;

public class CropActivity extends AppCompatActivity {

    private CropOverlayView cropView;
    private File currentImageFile;
    private Bitmap sourceBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        Toolbar toolbar = findViewById(R.id.toolbar);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cropRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
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
        currentImageFile = new File(imgPath);
        sourceBitmap = BitmapFactory.decodeFile(imgPath);
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
        if (currentImageFile == null) return;
        Bitmap cropped = cropView.getCroppedBitmap();
        if (cropped == null) {
            Toast.makeText(this, R.string.cropInvalidArea, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Bitmap.CompressFormat format = currentImageFile.getName().toLowerCase().endsWith(".png")
                    ? Bitmap.CompressFormat.PNG
                    : Bitmap.CompressFormat.JPEG;
            FileOutputStream out = new FileOutputStream(currentImageFile);
            cropped.compress(format, 100, out);
            out.flush();
            out.close();
            Toast.makeText(this, R.string.imageCropped, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, R.string.cropError, Toast.LENGTH_SHORT).show();
        }
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
