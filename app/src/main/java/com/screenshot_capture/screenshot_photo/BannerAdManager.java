package com.screenshot_capture.screenshot_photo;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;

// 🛡️ Précharge UNE bannière au démarrage de l'app (contexte applicatif) afin qu'elle soit déjà
// chargée quand la première Activity s'ouvre → l'impression est captée même sur une visite très courte.
//
// L'AdView est partagée et vit pour toute la durée du process. Comme une seule Activity est au
// premier plan à la fois, la bannière « suit » l'écran visible : chaque Activity appelle showIn()
// dans onResume (ré-attache + relance) et hide() dans onPause (stoppe + détache). On ne la détruit
// jamais → pas de rechargement, l'ad reste prête en permanence.
public class BannerAdManager {

    private static BannerAdManager instance;

    @Nullable private AdView adView;

    public static synchronized BannerAdManager getInstance() {
        if (instance == null) instance = new BannerAdManager();
        return instance;
    }

    // À appeler une seule fois au démarrage (depuis MyApp).
    public void preload(Context appContext) {
        if (adView != null) return; // déjà préchargée
        AdView v = new AdView(appContext);
        v.setAdUnitId(appContext.getString(R.string.banner));
        v.setAdSize(getAdSize(appContext));
        v.loadAd(new AdRequest.Builder().build());
        adView = v;
    }

    // Affiche la bannière dans le conteneur de l'Activity au premier plan (depuis onResume).
    public void showIn(@Nullable ViewGroup container) {
        if (adView == null || container == null) return;
        detachFromParent();
        container.addView(adView);
        adView.resume();
    }

    // Stoppe l'auto-refresh et détache la bannière (depuis onPause) — réutilisable au prochain showIn().
    public void hide() {
        if (adView == null) return;
        adView.pause();
        detachFromParent();
    }

    private void detachFromParent() {
        if (adView != null && adView.getParent() instanceof ViewGroup) {
            ((ViewGroup) adView.getParent()).removeView(adView);
        }
    }

    private AdSize getAdSize(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int adWidth = (int) (dm.widthPixels / dm.density);
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth);
    }
}
