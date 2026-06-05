package com.screenshot_capture.screenshot_photo;

import android.app.Application;

import com.google.android.gms.ads.MobileAds;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize the AdMob SDK once for the whole app.
        try {
            MobileAds.initialize(this, initializationStatus -> {});
        } catch (Exception ignored) {
        }
    }
}
