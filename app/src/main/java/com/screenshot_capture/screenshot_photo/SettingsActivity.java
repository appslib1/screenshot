package com.screenshot_capture.screenshot_photo;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsActivity extends AppCompatActivity {
    private static final String KEY_BTN = "btn";
    private static final String KEY_NOTIFICATION = "notification";
    private static final String KEY_SOUND = "sound";
    private static final String PREFS_NAME = "app_settings";

    private SwitchMaterial notificationSwitch;
    private SwitchMaterial btnSwitch;
    private SwitchMaterial soundSwitch;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Initialisation des préférences (Mode privé)
        this.prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        setupToolbar();
        setupSwitches();
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        LinearLayout contentArea = findViewById(R.id.contentArea);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(bars.left, bars.top, bars.right, 0);
            contentArea.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
            actionBar.setTitle(R.string.settings); // Assurez-vous que cette string existe
        }
    }

    private void setupSwitches() {
        notificationSwitch = findViewById(R.id.notificationSwitch);
        btnSwitch = findViewById(R.id.btnSwitch);
        soundSwitch = findViewById(R.id.soundSwitch);

        // Chargement des valeurs sauvegardées
        notificationSwitch.setChecked(prefs.getBoolean(KEY_NOTIFICATION, true));
        btnSwitch.setChecked(prefs.getBoolean(KEY_BTN, false));
        soundSwitch.setChecked(prefs.getBoolean(KEY_SOUND, true));

        // Listener Notification
        notificationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Si on active la notification, on désactive le bouton flottant
                btnSwitch.setChecked(false);
            }
            savePrefs();
        });

        // Listener Bouton Flottant
        btnSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Si on active le bouton flottant, on désactive la notification
                notificationSwitch.setChecked(false);
            }
            savePrefs();
        });

        // Listener Son
        soundSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> savePrefs());
    }

    private void savePrefs() {
        prefs.edit()
                .putBoolean(KEY_NOTIFICATION, notificationSwitch.isChecked())
                .putBoolean(KEY_BTN, btnSwitch.isChecked())
                .putBoolean(KEY_SOUND, soundSwitch.isChecked())
                .apply();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        // Retour flèche toolbar
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

        return super.onOptionsItemSelected(item);
    }
}