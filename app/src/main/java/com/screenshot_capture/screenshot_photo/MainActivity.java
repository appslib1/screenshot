package com.screenshot_capture.screenshot_photo;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;

public class MainActivity extends AppCompatActivity {
    static final String CHANNEL_ID = "screenshot_silent_v1";
    static final int NOTIFICATION_ID = 101;
    private static final int REQ_NOTIF = 100;
    private static final int REQ_STORAGE = 102;
    private static final String KEY_BTN = "btn";
    private static final String KEY_NOTIFICATION = "notification";
    // Un flag d'onboarding par mode : les 2 flows (notif vs overlay) ont des étapes et consents
    // différents, l'utilisateur mérite une explication dédiée la première fois qu'il bascule.
    private static final String KEY_ONBOARDED_NOTIF = "onboarded_notif_v1";
    private static final String KEY_ONBOARDED_OVERLAY = "onboarded_overlay_v1";
    private static final String PREFS_NAME = "app_settings";
    private static final String PREF_NAME_AD = "adPrefs";

    private Button launch, browse, settings, exit;
    private SharedPreferences prefs;
    private ActivityResultLauncher<Intent> overlayLauncher;
    private FrameLayout adContainerView;
    private AdView adView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        adContainerView = findViewById(R.id.ad_view_container);
        loadBanner();

        this.prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar);
        LinearLayout contentArea = findViewById(R.id.contentArea);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(bars.left, bars.top, bars.right, 0);
            contentArea.setPadding(bars.left, 0, bars.right, 0);
            adContainerView.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.shortAppName);
        }
        createNotificationChannel();

        this.overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleOverlayResult()
        );

        launch = findViewById(R.id.launch);
        browse = findViewById(R.id.browse);
        settings = findViewById(R.id.settings);
        exit = findViewById(R.id.exit);

        launch.setOnClickListener(v -> turnOn());
        browse.setOnClickListener(v -> browse());

        settings.setOnClickListener(v -> {
            SharedPreferences adPrefs = getSharedPreferences(PREF_NAME_AD, MODE_PRIVATE);
            int count = adPrefs.getInt("ad_click_count", 0);
            adPrefs.edit().putInt("ad_click_count", count + 1).apply();
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        });

        exit.setOnClickListener(v -> moveTaskToBack(true));
    }

    private void turnOn() {
        // Sur API 23-28, WRITE_EXTERNAL_STORAGE est une permission runtime et saveToPublicPictures()
        // en a besoin pour écrire dans Pictures/Screenshots. Sans ça, chaque capture échoue
        // silencieusement (toast « Capture failed ») — bug historique sur Yoga Tab 3, Galaxy S4-S6…
        // Pré-M : install-time (toujours grantée). Q+ : MediaStore, pas besoin.
        if (needsLegacyStoragePermission() && !hasLegacyStoragePermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
            return;
        }

        // Overlay (bouton flottant) actif par défaut à l'install : plus visible et plus
        // intuitif qu'une notif discrète pour un nouvel utilisateur. L'utilisateur peut
        // toujours basculer vers le mode notification depuis Settings.
        boolean showBtn = prefs.getBoolean(KEY_BTN, true);
        boolean showNotif = prefs.getBoolean(KEY_NOTIFICATION, false);

        // Première activation : on explique ce qui va se passer avant que l'app ne parte en arrière-plan.
        // Sans ça, l'utilisateur tape « Turn on », voit l'app se fermer et croit que c'est cassé → uninstall.
        // Le flag n'est persisté qu'une fois le flow réellement allé jusqu'au bout (perm accordée +
        // session démarrée). Comme ça, si l'utilisateur refuse la perm derrière le dialog, il reverra
        // l'explication au prochain « Turn on » plutôt que d'arriver direct sur un prompt système sans contexte.
        String onboardKey = showNotif ? KEY_ONBOARDED_NOTIF : KEY_ONBOARDED_OVERLAY;
        if ((showBtn || showNotif) && !prefs.getBoolean(onboardKey, false)) {
            showOnboardingDialog(showNotif, () -> performTurnOn(showNotif, showBtn));
            return;
        }
        performTurnOn(showNotif, showBtn);
    }

    private void performTurnOn(boolean showNotif, boolean showBtn) {
        if (showNotif) {
            if (hasNotificationPermission()) {
                postCaptureNotification();
                markOnboarded(KEY_ONBOARDED_NOTIF);
                moveTaskToBack(true);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(this,
                        new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            } else {
                postCaptureNotification();
                markOnboarded(KEY_ONBOARDED_NOTIF);
                moveTaskToBack(true);
            }
        } else if (showBtn) {
            launchFloatingButtonFlow();
        }
    }

    private void markOnboarded(String key) {
        prefs.edit().putBoolean(key, true).apply();
    }

    private void showOnboardingDialog(boolean notifMode, Runnable then) {
        int body = notifMode ? R.string.onboardingNotif : R.string.onboardingOverlay;
        new AlertDialog.Builder(this)
                .setTitle(R.string.onboardingTitle)
                .setMessage(body)
                .setPositiveButton(R.string.gotIt, (d, w) -> then.run())
                // Pas annulable : on veut que l'utilisateur lise avant de perdre l'app en bg.
                .setCancelable(false)
                .show();
    }

    private static boolean needsLegacyStoragePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
    }

    private boolean hasLegacyStoragePermission() {
        return ActivityCompat.checkSelfPermission(this,
                "android.permission.WRITE_EXTERNAL_STORAGE") == 0;
    }

    private void launchFloatingButtonFlow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            FloatingButton.show(this);
            markOnboarded(KEY_ONBOARDED_OVERLAY);
            moveTaskToBack(true);
        } else {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            overlayLauncher.launch(intent);
        }
    }

    private void handleOverlayResult() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlayRequired, Toast.LENGTH_SHORT).show();
        } else {
            FloatingButton.show(this);
            markOnboarded(KEY_ONBOARDED_OVERLAY);
            moveTaskToBack(true);
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ActivityCompat.checkSelfPermission(this,
                "android.permission.POST_NOTIFICATIONS") == 0;
    }

    private void postCaptureNotification() {
        // Une session vivante : le service tient déjà sa propre notification (avec bouton Stop).
        // Ne pas la remplacer par une notification bootstrap sans action Stop.
        if (ScreenshotService.isSessionAlive()) return;

        Intent trigger = new Intent(this, CaptureTriggerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 1, trigger,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.baseline_crop_free_24)
                .setContentTitle(getString(R.string.shortAppName))
                .setContentText(getString(R.string.clickToTakeScreenshot))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(true)
                .setContentIntent(contentPi);

        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Screenshot channel", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    public void browse() {
        startActivity(new Intent(this, ListActivity.class));
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            if (grantResults.length > 0 && grantResults[0] == 0) {
                postCaptureNotification();
                markOnboarded(KEY_ONBOARDED_NOTIF);
                moveTaskToBack(true);
            } else {
                Toast.makeText(this, R.string.notificationPermissionDenied, Toast.LENGTH_LONG).show();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && !ActivityCompat.shouldShowRequestPermissionRationale(this, "android.permission.POST_NOTIFICATIONS")) {
                    openAppNotificationSettings();
                }
            }
        } else if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == 0) {
                // Grantée : on reprend le flow comme si l'utilisateur venait de taper « Turn on ».
                turnOn();
            } else {
                Toast.makeText(this, R.string.storagePermissionDenied, Toast.LENGTH_LONG).show();
                // « Don't ask again » coché : seul passage par les réglages système permet de revenir.
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                        "android.permission.WRITE_EXTERNAL_STORAGE")) {
                    openAppDetailsSettings();
                }
            }
        }
    }

    private void openAppNotificationSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
        }
        try {
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    private void openAppDetailsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    private void loadBanner() {
        if (adContainerView == null) return;
        // Le conteneur reste GONE (aucun espace réservé) tant qu'une pub n'est pas réellement
        // chargée : on l'affiche dans onAdLoaded, on le laisse masqué si le chargement échoue.
        adContainerView.setVisibility(View.GONE);
        adView = new AdView(this);
        adView.setAdUnitId(getString(R.string.banner));
        adView.setAdSize(getAdSize());
        adView.setAdListener(new com.google.android.gms.ads.AdListener() {
            @Override
            public void onAdLoaded() {
                adContainerView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAdFailedToLoad(com.google.android.gms.ads.LoadAdError error) {
                adContainerView.setVisibility(View.GONE);
            }
        });
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
