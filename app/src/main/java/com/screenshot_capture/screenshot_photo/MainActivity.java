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
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    static final String CHANNEL_ID = "screenshot_silent_v1";
    static final int NOTIFICATION_ID = 101;
    private static final String KEY_BTN = "btn";
    private static final String KEY_NOTIFICATION = "notification";
    private static final String PREFS_NAME = "app_settings";
    private static final String PREF_NAME_AD = "adPrefs";

    private Button launch, browse, settings, exit;
    private SharedPreferences prefs;
    private ActivityResultLauncher<Intent> overlayLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar);
        LinearLayout contentArea = findViewById(R.id.contentArea);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(bars.left, bars.top, bars.right, 0);
            contentArea.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });
        setSupportActionBar(toolbar);
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
        boolean showNotif = prefs.getBoolean(KEY_NOTIFICATION, true);
        boolean showBtn = prefs.getBoolean(KEY_BTN, false);

        if (showNotif) {
            if (hasNotificationPermission()) {
                postCaptureNotification();
                moveTaskToBack(true);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(this,
                        new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
            } else {
                postCaptureNotification();
                moveTaskToBack(true);
            }
        } else if (showBtn) {
            launchFloatingButtonFlow();
        }
    }

    private void launchFloatingButtonFlow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            FloatingButton.show(this);
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
            moveTaskToBack(true);
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ActivityCompat.checkSelfPermission(this,
                "android.permission.POST_NOTIFICATIONS") == 0;
    }

    private void postCaptureNotification() {
        Intent trigger = new Intent(this, CaptureTriggerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 1, trigger,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.baseline_camera_24)
                .setContentTitle(getString(R.string.app_name))
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
        if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == 0) {
                postCaptureNotification();
                moveTaskToBack(true);
            } else {
                Toast.makeText(this, R.string.notificationPermissionDenied, Toast.LENGTH_LONG).show();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && !ActivityCompat.shouldShowRequestPermissionRationale(this, "android.permission.POST_NOTIFICATIONS")) {
                    openAppNotificationSettings();
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

}
