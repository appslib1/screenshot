package com.screenshot_capture.screenshot_photo;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.Display;
import android.widget.FrameLayout;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;

public class BannerAdManager {

    private final Activity activity;
    private AdView adView;

    public BannerAdManager(Activity activity) {
        this.activity = activity;
    }

    public void load(int containerId) {
        try {
            FrameLayout container = activity.findViewById(containerId);
            if (container == null) return;

            adView = new AdView(activity);
            adView.setAdUnitId(activity.getString(R.string.banner));
            container.addView(adView);

            adView.setAdSize(getAdSize());
            adView.loadAd(new AdRequest.Builder().build());
        } catch (Exception ignored) {
        }
    }

    private AdSize getAdSize() {
        Display display = activity.getWindowManager().getDefaultDisplay();
        DisplayMetrics outMetrics = new DisplayMetrics();
        display.getMetrics(outMetrics);

        float widthPixels = outMetrics.widthPixels;
        float density = outMetrics.density;
        int adWidth = (int) (widthPixels / density);

        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth);
    }
}
