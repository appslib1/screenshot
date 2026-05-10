package com.screenshot_capture.screenshot_photo;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.GridView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

public class ListActivity extends AppCompatActivity {
    private GridView gridView;
    private MediaAdapter mediaAdapter;
    private ArrayList<String> mediaList = new ArrayList<>();

    // Code de requête pour le suivi du résultat
    private static final int REQUEST_CODE_VIEW_IMAGE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        // Configuration de la Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        this.gridView = findViewById(R.id.gridView);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(bars.left, bars.top, bars.right, 0);
            gridView.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        // Initialisation de la grille
        this.mediaAdapter = new MediaAdapter(this, this.mediaList);
        this.gridView.setAdapter(this.mediaAdapter);

        // Chargement initial des médias
        loadMedia();

        // Clic sur un élément de la grille
        this.gridView.setOnItemClickListener((adapterView, view, position, id) -> openImage(position));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        int id = menuItem.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }

        if (id == R.id.rate_us) {
            new RatingModal(this).openRatingDialog();
            return true;
        } else if (id == R.id.share) {
            new RatingModal(this).shareApp();
            return true;
        }

        return super.onOptionsItemSelected(menuItem);
    }

    private void openImage(int position) {
        Intent intent = new Intent(this, SingleActivity.class);
        intent.putExtra("img_uri", this.mediaList.get(position));
        intent.putExtra("fromList", "yes");
        // Utilisation du code de requête défini
        startActivityForResult(intent, REQUEST_CODE_VIEW_IMAGE);
    }

    private void loadMedia() {
        // Dossier source (Screenshots dans le cache de l'app)
        File directory = new File(getCacheDir(), "Screenshots");

        if (!directory.exists()) {
            directory.mkdirs();
        }

        this.mediaList.clear();
        File[] files = directory.listFiles();

        if (files != null && files.length > 0) {
            // Trier par date de modification (décroissant : plus récent d'abord)
            Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

            for (File file : files) {
                String fileName = file.getName().toLowerCase();
                if (fileName.endsWith(".jpg") || fileName.endsWith(".png")) {
                    this.mediaList.add(file.getAbsolutePath());
                }
            }
        }
        this.mediaAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_VIEW_IMAGE && resultCode == RESULT_OK && data != null) {
            boolean isDeleted = data.getBooleanExtra("imageDeleted", false);
            String path = data.getStringExtra("deletedImagePath");

            if (isDeleted && path != null) {
                this.mediaList.remove(path);
                this.mediaAdapter.notifyDataSetChanged();
            }
        }
    }
}