package com.screenshot_capture.screenshot_photo;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SingleActivity extends AppCompatActivity {

    private ImageButton imageView, homeBtn, browseBtn, shareBtn, deleteBtn;
    private File currentImageFile;
    private GestureDetector gestureDetector;
    private List<String> mediaList = new ArrayList<>();
    private int currentIndex = 0;
    private String fromList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("SingleActivity", "onCreate img_uri=" + getIntent().getStringExtra("img_uri"));
        setContentView(R.layout.activity_single);

        // Configuration Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        // Chargement de la liste des médias pour le swipe
        loadMediaList();

        // Récupération des données de l'Intent
        String imgUriPath = getIntent().getStringExtra("img_uri");
        fromList = getIntent().getStringExtra("fromList");

        if (imgUriPath != null) {
            currentImageFile = new File(imgUriPath);
            // Trouver l'index actuel dans la liste
            currentIndex = mediaList.indexOf(imgUriPath);
            if (currentIndex == -1) currentIndex = 0;
        }

        // Initialisation des vues
        imageView = findViewById(R.id.imageView);
        homeBtn = findViewById(R.id.homeBtn);
        browseBtn = findViewById(R.id.browseBtn);
        shareBtn = findViewById(R.id.shareBtn);
        deleteBtn = findViewById(R.id.deleteBtn);

        // Affichage de l'image
        updateImageDisplay();

        // Détection du Swipe
        gestureDetector = new GestureDetector(this, new GestureListener());
        imageView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

        // Listeners des boutons
        homeBtn.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        browseBtn.setOnClickListener(v -> startActivity(new Intent(this, ListActivity.class)));
        shareBtn.setOnClickListener(v -> shareImage(currentImageFile));
        deleteBtn.setOnClickListener(v -> deleteImageDialog(currentImageFile));
    }

    private void loadMediaList() {
        File folder = new File(getCacheDir(), "Screenshots");
        File[] files = folder.listFiles();
        if (files != null && files.length > 0) {
            // Tri par date décroissante
            Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
            for (File file : files) {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".png")) {
                    mediaList.add(file.getAbsolutePath());
                }
            }
        }
    }

    private void updateImageDisplay() {
        if (currentImageFile != null && currentImageFile.exists()) {
            imageView.setImageBitmap(BitmapFactory.decodeFile(currentImageFile.getAbsolutePath()));
        }
    }

    private void shareImage(File file) {
        if (file == null || !file.exists()) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileProvider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.shareImg)));
        } catch (Exception e) {
            Toast.makeText(this, "Error sharing image", Toast.LENGTH_SHORT).show();
            Log.e("ShareError", e.getMessage());
        }
    }

    private void deleteImageDialog(File file) {
        if (file == null || !file.exists()) return;

        new AlertDialog.Builder(this)
                .setTitle(R.string.deleteImage)
                .setMessage(R.string.areYouSure)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    if (file.delete()) {
                        Intent intent = new Intent();
                        intent.putExtra("imageDeleted", true);
                        intent.putExtra("deletedImagePath", file.getAbsolutePath());
                        setResult(RESULT_OK, intent);
                        finish();
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.rate_us) {
            new RatingModal(this).openRatingDialog();
        } else if (id == R.id.share) {
            new RatingModal(this).shareApp();
        }
        return super.onOptionsItemSelected(item);
    }

    private void showImageAtIndex(int index) {
        if (index >= 0 && index < mediaList.size()) {
            currentIndex = index;
            currentImageFile = new File(mediaList.get(currentIndex));
            updateImageDisplay();
        }
    }

    // Classe interne pour gérer le balayage (Swipe)
    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 100;
        private static final int SWIPE_VELOCITY_THRESHOLD = 100;

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            float diffX = e2.getX() - e1.getX();
            float diffY = e2.getY() - e1.getY();

            if (Math.abs(diffX) > Math.abs(diffY)) {
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        // Swipe à droite -> Image précédente
                        int nextIndex = (currentIndex > 0) ? currentIndex - 1 : mediaList.size() - 1;
                        showImageAtIndex(nextIndex);
                    } else {
                        // Swipe à gauche -> Image suivante
                        int nextIndex = (currentIndex < mediaList.size() - 1) ? currentIndex + 1 : 0;
                        showImageAtIndex(nextIndex);
                    }
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onDown(MotionEvent e) {
            return true; // Obligatoire pour détecter le fling
        }
    }
}