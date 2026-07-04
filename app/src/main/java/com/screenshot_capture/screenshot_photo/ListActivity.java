package com.screenshot_capture.screenshot_photo;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.widget.FrameLayout;
import android.widget.GridView;
import java.util.ArrayList;

public class ListActivity extends AppCompatActivity {
    private GridView gridView;
    private MediaAdapter mediaAdapter;
    private ArrayList<String> mediaList = new ArrayList<>();

    // Code de requête pour le suivi du résultat
    private static final int REQUEST_CODE_VIEW_IMAGE = 1001;
    // Code de requête pour la permission de lecture média
    private static final int REQUEST_CODE_READ_MEDIA = 2001;
    private FrameLayout adContainerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        adContainerView = findViewById(R.id.ad_view_container);

        // Configuration de la Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        this.gridView = findViewById(R.id.gridView);
        this.gridView.setEmptyView(findViewById(R.id.emptyView));

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(bars.left, bars.top, bars.right, 0);
            gridView.setPadding(bars.left, 0, bars.right, 0);
            adContainerView.setPadding(bars.left, 0, bars.right, bars.bottom);
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

        // Clic sur un élément de la grille
        this.gridView.setOnItemClickListener((adapterView, view, position, id) -> openImage(position));

        // Permission requise pour lire les captures de l'appareil (dossier public « Screenshots »).
        // On charge d'abord les captures de l'appli, puis on recharge en fusionnant après octroi.
        if (!ScreenshotLoader.hasReadPermission(this)) {
            AppOpenAdManager.disableNext(); // le dialogue de permission met l'appli en arrière-plan
            ActivityCompat.requestPermissions(this,
                    new String[]{ScreenshotLoader.requiredPermission()}, REQUEST_CODE_READ_MEDIA);
        }

        // Chargement initial des médias
        loadMedia();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_READ_MEDIA
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission accordée → on recharge en incluant les captures de l'appareil.
            loadMedia();
        }
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
        startActivityForResult(intent, REQUEST_CODE_VIEW_IMAGE);
    }

    private void loadMedia() {
        // Fusion des captures de l'appli (cache) + du dossier « Screenshots » de l'appareil,
        // triées par date décroissante.
        this.mediaList.clear();
        this.mediaList.addAll(ScreenshotLoader.loadAll(this));
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

    // 🛡️ Bannière partagée préchargée : elle suit l'Activity au premier plan (attach/detach sans destroy)
    @Override
    protected void onPause() {
        BannerAdManager.getInstance().hide();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BannerAdManager.getInstance().showIn(adContainerView);
    }
}