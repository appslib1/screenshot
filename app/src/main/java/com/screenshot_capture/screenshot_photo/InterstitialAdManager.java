package com.screenshot_capture.screenshot_photo;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

public class InterstitialAdManager {

    private static final String TAG = "InterstitialAdManager";

    private static final int CLICK_THRESHOLD = 2;
    private static final int MAX_PER_SESSION = 4;

    // عدّاد الضغطات الدائم (يصمد أمام قتل العملية) لتنعيم تكرار الظهور
    private static final String PREFS_NAME = "interstitial_ad_prefs";
    private static final String KEY_CLICK_COUNTER = "click_counter";
    private static final String KEY_LAST_SHOW_TIME = "last_show_time";

    private static InterstitialAdManager instance;

    private InterstitialAd interstitialAd;
    private boolean isLoading = false;
    private int sessionShowCount = 0;
    private boolean isAdShowing = false;
    private boolean isPendingShow = false;

    private InterstitialAdManager() {}

    public static synchronized InterstitialAdManager getInstance() {
        if (instance == null) {
            instance = new InterstitialAdManager();
        }
        return instance;
    }

    private int getClickCounter(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_CLICK_COUNTER, 0);
    }

    private void setClickCounter(Context ctx, int value) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_CLICK_COUNTER, value).apply();
    }

    private long getLastShowTime(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_SHOW_TIME, 0);
    }

    private void setLastShowTime(Context ctx, long time) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_SHOW_TIME, time).apply();
    }

    public boolean isAdShowing() {
        return isAdShowing || isPendingShow;
    }

    // 🛡️ تعديل: أصبحت دالة التحميل تعود بـ callback اختياري لمعرفة وقت الجاهزية
    public void preload(Context context, @Nullable Runnable onReady) {
        if (sessionShowCount >= MAX_PER_SESSION || interstitialAd != null || isLoading) {
            if (interstitialAd != null && onReady != null) onReady.run();
            return;
        }

        isLoading = true;
        AdRequest adRequest = new AdRequest.Builder().build();
        String adUnitId = context.getString(R.string.interstitialAd);

        InterstitialAd.load(context.getApplicationContext(), adUnitId, adRequest, new InterstitialAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull InterstitialAd ad) {
                interstitialAd = ad;
                isLoading = false;
                Log.d(TAG, "Interstitial loaded successfully");
                if (onReady != null) onReady.run();
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                interstitialAd = null;
                isLoading = false;
                Log.e(TAG, "Failed to load interstitial: " + loadAdError.getMessage());
            }
        });
    }

    private void showAdDirectly(Activity activity, Runnable onAdClosed, Runnable onHideLoader) {
        AppOpenAdManager appOpen = AppOpenAdManager.getInstance();

        // 🛡️ إعادة تحقق كاملة في لحظة العرض الحقيقية: قد تكون الحالة تغيّرت أثناء مهلة اللودر/التحميل.
        // أهمها !isPendingShow: لو انتهت مهلة الأمان وتمّ التنقل بالفعل، لا نعرض الإعلان متأخراً فوق الشاشة الجديدة.
        if (!isPendingShow
                || activity.isFinishing() || activity.isDestroyed()
                || interstitialAd == null
                || isAdShowing
                || (appOpen != null && appOpen.isShowingAd())
                || System.currentTimeMillis() - AdControl.lastGlobalAdShowTime < AdControl.CROSS_FORMAT_COOLDOWN) {
            isPendingShow = false;
            if (onHideLoader != null) onHideLoader.run();
            if (onAdClosed != null) onAdClosed.run();
            return;
        }

        interstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdShowedFullScreenContent() {
                isAdShowing = true;
                isPendingShow = false;
                //long now = System.currentTimeMillis();
                //AdControl.lastGlobalAdShowTime = now;
                //setLastShowTime(activity, now); // بصمة آخر إعلان بيني للفاصل الزمني الأدنى
                if (onHideLoader != null) onHideLoader.run();
                Log.d(TAG, "Ad Showed");
            }

            @Override
            public void onAdDismissedFullScreenContent() {
                isAdShowing = false;
                interstitialAd = null;
                setClickCounter(activity, 0);
                sessionShowCount++;
                // Preload pour la prochaine fois : preload() est idempotent et garde le plafond
                // de session (sessionShowCount >= MAX_PER_SESSION) → aucune requête gâchée.
                preload(activity.getApplicationContext(), null);
                if (onAdClosed != null) onAdClosed.run();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull com.google.android.gms.ads.AdError adError) {
                isAdShowing = false;
                isPendingShow = false;
                interstitialAd = null;
                setClickCounter(activity, 0);
                // Échec d'affichage → on recharge pour ne pas rester sans inventaire.
                preload(activity.getApplicationContext(), null);
                if (onHideLoader != null) onHideLoader.run();
                if (onAdClosed != null) onAdClosed.run();
            }
        });

        interstitialAd.show(activity);
    }

    public void showWithSafetyLoader(Activity activity, Runnable onComplete) {
        // 🛡️ fallback يُنفَّذ مرة واحدة فقط مهما تعددت المسارات التي تستدعيه
        // (يمنع التنقل المزدوج: مهلة الأمان + الـ callback المتأخر).
        final boolean[] completed = {false};
        Runnable fallback = () -> {
            if (completed[0]) return;
            completed[0] = true;
            if (onComplete != null) onComplete.run();
        };

        AppOpenAdManager appOpen = AppOpenAdManager.getInstance();
        if (appOpen != null && appOpen.isShowingAd()) {
            fallback.run();
            return;
        }

        if (isPendingShow || isAdShowing || sessionShowCount >= MAX_PER_SESSION || activity.isFinishing() || activity.isDestroyed()) {
            fallback.run();
            return;
        }

        // On compte le clic AVANT les cooldowns : les clics pendant l'intervalle min ne sont plus perdus
        // → dès l'intervalle écoulé, le prochain clic affiche directement (compteur déjà au seuil).
        int counter = getClickCounter(activity) + 1;
        setClickCounter(activity, counter);
        // Cooldown actif → on NE consomme PAS le compteur : il reste au seuil et le PROCHAIN clic
        // (une fois le cooldown écoulé) affichera l'ad. Avant, le compteur était remis à 0 à la ligne
        // setClickCounter(0) puis showAdDirectly bloquait sur ce même cooldown → 1 ad sur 2 « mangée »
        // (2e clic OK, 4e non, 6e OK...). En vérifiant ici, le clic qualifiant n'est plus gaspillé.
        if (System.currentTimeMillis() - AdControl.lastGlobalAdShowTime < AdControl.CROSS_FORMAT_COOLDOWN) {
            Log.d(TAG, "Skip interstitial: cooldown active (compteur conservé pour le prochain clic)");
            fallback.run();
            return;
        }

        if (counter < CLICK_THRESHOLD) {
            // Préchargement dès le 1er clic (compteur sous le seuil) : l'ad sera prête au clic qui
            // atteint le seuil → affichage immédiat, et on évite le motif "1er clic requête / skip".
            if (interstitialAd == null && !isLoading) preload(activity, null);
            fallback.run();
            return;
        }

        // Ad pas prêt → on ne bloque PAS l'utilisateur derrière un loader : on continue (navigation)
        // et on précharge pour la prochaine fois. Afficher un ad chargé en retard = ad surprise = clics accidentels.
        if (interstitialAd == null) {
            Log.d(TAG, "Ad not ready → skip this time, preload for next");
            // Filet de sécurité : si le préchargement paresseux n'a pas fini à temps, on lance/poursuit
            // la requête ici (idempotent via isLoading) → l'ad sera prête au prochain clic qualifiant.
            if (!isLoading) preload(activity, null);
            fallback.run();
            return;
        }

        // 🚀 الحماية القصوى: صفر العداد هنا فوراً بمجرد التأكد من أن الإعلان سيعرض!
        // هذا يضمن عدم تداخل العدادات في أجزاء الثانية القادمة.
        setClickCounter(activity, 0);

        // Ad prêt : affichage immédiat, sans loader ni délai.
        isPendingShow = true;
        showAdDirectly(activity, fallback, null);
    }

}