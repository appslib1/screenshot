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

    private static final int CLICK_THRESHOLD = 3;
    private static final int MAX_PER_SESSION = 3;

    // calm-down موحّد قبل عرض الإعلان: المستخدم ضغط زر تنقّل ويده ما زالت نشطة، نتركها تستقر
    private static final long CALM_DOWN_MS = 2500;

    // مهلة أمان لإغلاق اللودر إن تأخر تحميل الإعلان
    private static final long LOAD_TIMEOUT_MS = 5000;

    // 🛡️ أدنى فاصل زمني بين إعلانين بينيّين (90 ثانية) — يقتل دفعات التنقّل السريع
    // التي تُظهر الإعلان أثناء إيقاع نقر سريع (أعلى سياقات النقر العرضي)
    private static final long MIN_INTERVAL_MS = 60_000;

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
                long now = System.currentTimeMillis();
                AdControl.lastGlobalAdShowTime = now;
                setLastShowTime(activity, now); // بصمة آخر إعلان بيني للفاصل الزمني الأدنى
                if (onHideLoader != null) onHideLoader.run();
                Log.d(TAG, "Ad Showed");
            }

            @Override
            public void onAdDismissedFullScreenContent() {
                isAdShowing = false;
                interstitialAd = null;
                setClickCounter(activity, 0);
                sessionShowCount++;
                // 🛡️ تم إزالة preload التلقائي هنا لتجنب الطلبات غير المستغلة!
                if (onAdClosed != null) onAdClosed.run();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull com.google.android.gms.ads.AdError adError) {
                isAdShowing = false;
                isPendingShow = false;
                interstitialAd = null;
                setClickCounter(activity, 0);
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

        // 🛡️ أدنى فاصل زمني منذ آخر إعلان بيني — يمنع الظهور أثناء التنقّل السريع
        if (System.currentTimeMillis() - getLastShowTime(activity) < MIN_INTERVAL_MS) {
            Log.d(TAG, "Skip interstitial: min interval not elapsed");
            fallback.run();
            return;
        }

        // 🛡️ cross-format cooldown: لا نعرض إنترستيشال بعد أي إعلان ملء شاشة بفترة قصيرة
        if (System.currentTimeMillis() - AdControl.lastGlobalAdShowTime < AdControl.CROSS_FORMAT_COOLDOWN) {
            Log.d(TAG, "Skip interstitial: cross-format cooldown active");
            fallback.run();
            return;
        }

        // زيادة عداد الضغطات (دائم عبر SharedPreferences)
        int counter = getClickCounter(activity) + 1;
        setClickCounter(activity, counter);

        // 🛡️ ذكاء التحميل الاستباقي: نحمّل الإعلان قبل الضغطة الأخيرة بواحدة ليصبح جاهزاً عند العتبة
        if (counter == (CLICK_THRESHOLD - 1)) {
            Log.d(TAG, "Preloading dynamically 1 click before threshold");
            preload(activity, null);
        }

        if (counter < CLICK_THRESHOLD) {
            fallback.run();
            return;
        }

        isPendingShow = true;

        // إظهار اللودر فوراً لحماية الشاشة وعزل النقرات العشوائية
        final View loader = createAndShowLoader(activity);

        // 🛡️ استراتيجية التحميل تحت اللودر (إذا لم يكن جاهزاً بعد)
        if (interstitialAd == null) {
            Log.d(TAG, "Ad not ready yet, loading it under the safe shield...");
            preload(activity, () -> new Handler(Looper.getMainLooper()).postDelayed(() ->
                    showAdDirectly(activity, () -> {
                        removeLoader(loader);
                        fallback.run();
                    }, () -> removeLoader(loader)), CALM_DOWN_MS));

            // حماية التجميد الفني: إذا تأخر الإنترنت ولم يتم التحميل، نغلق اللودر ونكمل التطبيق
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (interstitialAd == null && isPendingShow) {
                    isPendingShow = false;
                    removeLoader(loader);
                    fallback.run();
                    Log.d(TAG, "Ad load timeout under shield. Fallback invoked.");
                }
            }, LOAD_TIMEOUT_MS);

        } else {
            // الإعلان جاهز مسبقاً، ننتظر مهلة الأمان (Calm Down) ثم نعرض
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    showAdDirectly(activity, () -> {
                        removeLoader(loader);
                        fallback.run();
                    }, () -> removeLoader(loader)), CALM_DOWN_MS);
        }
    }

    @Nullable
    private View createAndShowLoader(@NonNull Activity activity) {
        try {
            final float highZ = 2000f;
            View existing = activity.findViewById(R.id.fullscreen_loader_overlay);
            if (existing != null) {
                existing.setVisibility(View.VISIBLE);
                existing.bringToFront();
                return existing;
            }

            FrameLayout container = new FrameLayout(activity);
            container.setBackgroundColor(0xCC000000); // assombrissement (~80%) — un peu transparent, bloque les clics accidentels
            container.setClickable(true);

            container.setFocusable(true);
            container.setFocusableInTouchMode(true);
            container.requestFocus();
            container.setElevation(highZ);
            container.setTranslationZ(highZ);
            container.setOnTouchListener((v, event) -> true);

            float density = activity.getResources().getDisplayMetrics().density;

            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);

            ProgressBar pb = new ProgressBar(activity);
            pb.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
            int sizePx = (int) (48 * density);
            box.addView(pb, new LinearLayout.LayoutParams(sizePx, sizePx));

            TextView tv = new TextView(activity);
            tv.setText(R.string.adLoadingWait);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(14);
            LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tvParams.topMargin = (int) (16 * density);
            box.addView(tv, tvParams);

            FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            boxParams.gravity = Gravity.CENTER;
            container.addView(box, boxParams);

            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) content = activity.findViewById(android.R.id.content);

            if (content != null) {
                content.addView(container, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                container.bringToFront();
                return container;
            }
        } catch (Exception e) {
            Log.e(TAG, "Loader error: " + e.getMessage());
        }
        return null;
    }

    private void removeLoader(@Nullable View loader) {
        if (loader == null) return;
        try {
            if (loader.getId() == R.id.fullscreen_loader_overlay) {
                loader.setVisibility(View.GONE);
            } else {
                ViewGroup parent = (ViewGroup) loader.getParent();
                if (parent != null) parent.removeView(loader);
            }
        } catch (Exception e) {
            Log.e(TAG, "Remove loader error: " + e.getMessage());
        }
    }
}