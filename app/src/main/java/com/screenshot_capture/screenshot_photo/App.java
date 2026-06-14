package com.screenshot_capture.screenshot_photo;

import android.app.Application;

import com.google.android.gms.ads.MobileAds;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize the AdMob SDK once for the whole app.
        try {
            MobileAds.initialize(this, initializationStatus -> {
                // App Open ad manager observes the process lifecycle (cold start / warm resume).
                AppOpenAdManager.init(this);
            });
        } catch (Exception ignored) {
        }
    }
}
