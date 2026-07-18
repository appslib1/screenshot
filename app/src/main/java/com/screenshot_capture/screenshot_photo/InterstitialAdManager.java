package com.screenshot_capture.screenshot_photo;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

public class InterstitialAdManager {

    private static final String TAG = "InterstitialAdManager";

    // Le frein n'est plus le nombre de clics mais le temps (AdControl.GLOBAL_COOLDOWN).
    // Seuil à 1 : l'ad préchargée est affichée dès le clic qualifiant → plus de requête
    // brûlée sur un 1er clic qui n'aboutit jamais à une impression.
    private static final int CLICK_THRESHOLD = 1;
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
                || System.currentTimeMillis() - AdControl.lastAppOpenShowTime < AdControl.CROSS_FORMAT_COOLDOWN) {
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
                long now = System.currentTimeMillis();
                AdControl.lastGlobalAdShowTime = now; // bloque l'App Open pendant CROSS_FORMAT_COOLDOWN
                setLastShowTime(activity, now);       // base du cooldown interstitial → interstitial
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

        long now = System.currentTimeMillis();

        // Cooldown inter-formats : un App Open vient de s'afficher → on n'enchaîne pas un plein écran.
        // On lit lastAppOpenShowTime (et non lastGlobalAdShowTime) sinon l'interstitial se bloquerait
        // lui-même 5 min après chacun de ses propres affichages.
        if (now - AdControl.lastAppOpenShowTime < AdControl.CROSS_FORMAT_COOLDOWN) {
            Log.d(TAG, "Skip interstitial: cross-format cooldown (App Open récent)");
            fallback.run();
            return;
        }

        // Cooldown interstitial → interstitial. C'est le vrai frein anti-CTR maintenant que le seuil
        // de clics est à 1 : sans lui, MainActivity n'ayant que 2 boutons de navigation, l'utilisateur
        // se prendrait une pub à chaque tap. Le compteur n'est PAS consommé ici → le prochain clic
        // une fois le cooldown écoulé affichera directement.
        if (now - getLastShowTime(activity) < AdControl.GLOBAL_COOLDOWN) {
            Log.d(TAG, "Skip interstitial: cooldown interstitial actif");
            fallback.run();
            return;
        }

        if (counter < CLICK_THRESHOLD) {
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