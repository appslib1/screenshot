package com.screenshot_capture.screenshot_photo;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SingleActivity extends AppCompatActivity {

    private ImageButton homeBtn, browseBtn, shareBtn, cropBtn, deleteBtn;
    private ViewPager2 viewPager;
    private ScreenshotPagerAdapter pagerAdapter;
    private final List<String> mediaList = new ArrayList<>();
    private int currentIndex = 0;
    private String fromList;
    private static final int REQUEST_CROP = 1001;
    private FrameLayout adContainerView;
    private AdView adView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_single);
        adContainerView = findViewById(R.id.ad_view_container);
        loadBanner();

        // Configuration Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        LinearLayout bottomBar = findViewById(R.id.bottomBar);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            bottomBar.setPadding(systemBars.left, 0, systemBars.right, 0);
            adContainerView.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom);
            return insets;
        });

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        // Préchargement de l'interstitiel pour qu'il soit prêt au clic sur « browse » (idempotent).
        InterstitialAdManager.getInstance().preload(getApplicationContext(), null);

        // Chargement de la liste des médias pour le swipe
        loadMediaList();

        // Récupération des données de l'Intent
        String imgUriPath = getIntent().getStringExtra("img_uri");
        fromList = getIntent().getStringExtra("fromList");

        if (imgUriPath != null) {
            currentIndex = mediaList.indexOf(imgUriPath);
            if (currentIndex == -1) currentIndex = 0;
        }

        // Initialisation des vues
        viewPager = findViewById(R.id.viewPager);
        homeBtn = findViewById(R.id.homeBtn);
        browseBtn = findViewById(R.id.browseBtn);
        shareBtn = findViewById(R.id.shareBtn);
        cropBtn = findViewById(R.id.cropBtn);
        deleteBtn = findViewById(R.id.deleteBtn);

        // Pager pour le swipe horizontal avec effet de glissement
        pagerAdapter = new ScreenshotPagerAdapter(mediaList);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setCurrentItem(currentIndex, false);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentIndex = position;
                updateActionButtons();
            }
        });

        // Listeners des boutons
        homeBtn.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        browseBtn.setOnClickListener(v -> {
            // Interstitial sur ouverture de la liste (transition naturelle entre activités).
            // Le manager throttle/skip en interne et rappelle toujours le callback → on navigue dans tous les cas.
            Intent intent = new Intent(this, ListActivity.class);
            InterstitialAdManager.getInstance().showWithSafetyLoader(this, () -> startActivity(intent));
        });
        shareBtn.setOnClickListener(v -> shareImage());
        cropBtn.setOnClickListener(v -> cropImage());
        deleteBtn.setOnClickListener(v -> deleteImageDialog());

        // État initial des boutons crop/suppression (masqués pour une capture externe).
        updateActionButtons();
    }

    /** Item courant : chemin de fichier (capture appli) ou URI content:// (capture appareil). */
    private String getCurrentItem() {
        if (currentIndex >= 0 && currentIndex < mediaList.size()) {
            return mediaList.get(currentIndex);
        }
        return null;
    }

    /** Fichier courant, uniquement si l'item est une capture modifiable de l'appli ; sinon null. */
    private File getCurrentFile() {
        String item = getCurrentItem();
        return ScreenshotLoader.isEditable(item) ? new File(item) : null;
    }

    /** Crop et suppression ne sont possibles que sur les captures de l'appli (fichiers). */
    private void updateActionButtons() {
        boolean editable = ScreenshotLoader.isEditable(getCurrentItem());
        cropBtn.setVisibility(editable ? View.VISIBLE : View.GONE);
        deleteBtn.setVisibility(editable ? View.VISIBLE : View.GONE);
    }

    private void loadMediaList() {
        // Fusion des captures de l'appli (cache) + du dossier « Screenshots » de l'appareil.
        mediaList.clear();
        mediaList.addAll(ScreenshotLoader.loadAll(this));
    }

    private void shareImage() {
        String item = getCurrentItem();
        if (item == null) return;
        try {
            Uri uri;
            if (ScreenshotLoader.isEditable(item)) {
                // Capture de l'appli : fichier privé → partagé via FileProvider.
                File file = new File(item);
                if (!file.exists()) return;
                uri = FileProvider.getUriForFile(this, getPackageName() + ".fileProvider", file);
            } else {
                // Capture de l'appareil : déjà une URI content:// partageable telle quelle.
                uri = Uri.parse(item);
            }
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            AppOpenAdManager.disableNext();
            startActivity(Intent.createChooser(intent, getString(R.string.shareImg)));
        } catch (Exception e) {
            Toast.makeText(this, "Error sharing image", Toast.LENGTH_SHORT).show();
            Log.e("ShareError", e.getMessage());
        }
    }

    private void cropImage() {
        // Crop réservé aux captures de l'appli (réécriture du fichier).
        File file = getCurrentFile();
        if (file == null || !file.exists()) return;
        Intent intent = new Intent(this, CropActivity.class);
        intent.putExtra("img_uri", file.getAbsolutePath());
        startActivityForResult(intent, REQUEST_CROP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CROP && resultCode == RESULT_OK) {
            // The file was overwritten with the cropped image — reload the current page.
            pagerAdapter.notifyItemChanged(currentIndex);
        }
    }

    private void deleteImageDialog() {
        // Suppression réservée aux captures de l'appli (fichiers dont l'appli est propriétaire).
        File file = getCurrentFile();
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

    private void loadBanner() {
        if (adContainerView == null) return;
        adView = new AdView(this);
        adView.setAdUnitId(getString(R.string.banner));
        adView.setAdSize(getAdSize());
        adContainerView.removeAllViews();
        adContainerView.addView(adView);
        adView.loadAd(new AdRequest.Builder().build());
    }

    private AdSize getAdSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int adWidth = (int) (dm.widthPixels / dm.density);
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidth);
    }

    @Override
    protected void onPause() {
        if (adView != null) adView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adView != null) adView.resume();
    }

    @Override
    protected void onDestroy() {
        if (adView != null) {
            adView.destroy();
            adView = null;
        }
        super.onDestroy();
    }

}
