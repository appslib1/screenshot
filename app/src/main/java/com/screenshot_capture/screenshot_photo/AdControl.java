package com.screenshot_capture.screenshot_photo;

public class AdControl {
    // Dernier ad plein écran affiché, tous formats confondus (interstitial OU App Open).
    // Lu par AppOpenAdManager pour ne pas enchaîner sur un interstitial.
    public static volatile long lastGlobalAdShowTime = 0;

    // Dernier App Open uniquement. L'interstitial s'appuie sur ce champ (et non sur
    // lastGlobalAdShowTime) pour le cooldown inter-formats, sinon il se bloquerait lui-même
    // pendant CROSS_FORMAT_COOLDOWN après chacun de ses propres affichages.
    public static volatile long lastAppOpenShowTime = 0;

    // Interstitial → interstitial. C'est le frein principal désormais : le seuil de clics est à 1,
    // donc c'est ce délai qui empêche une pub à chaque tap de navigation.
    public static final long GLOBAL_COOLDOWN = 90 * 1000L; // 90 s

    // Entre deux ads plein écran de formats différents (App Open ↔ interstitial).
    // Évite qu'un App Open ne tombe juste après un interstitial, et inversement.
    public static final long CROSS_FORMAT_COOLDOWN = 1 * 60 * 1000L; // 1 min
}