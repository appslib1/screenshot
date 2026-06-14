package com.screenshot_capture.screenshot_photo;

public class AdControl {
    public static long lastGlobalAdShowTime = 0;
    public static final long GLOBAL_COOLDOWN = 60000; // 60 secondes (interstitial → interstitial)
    // Cooldown minimum entre n'importe quels 2 ads plein écran (interstitial ↔ App Open).
    // Évite qu'un App Open ne tombe juste après un interstitial.
    public static final long CROSS_FORMAT_COOLDOWN = 1 * 60 * 1000L; // 5 min
}