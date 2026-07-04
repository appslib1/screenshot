package com.screenshot_capture.screenshot_photo;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
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
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.appopen.AppOpenAd;

import java.util.ArrayList;
import java.util.List;

public class AppOpenAdManager implements Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    private static final String TAG = "AppOpenAdManager";

    // 🛡️ زيادة مدة البقاء في الخلفية إلى 60 ثانية لفلترة الخروج القصير (مكالمة/إشعار/تبديل سريع) بدقة أعلى
    private static final long MIN_BACKGROUND_TIME_MS = 45_000;

    // 🛡️ مهلة أمان: إن لم يظهر الإعلان أو يفشل خلال هذه المدة بعد محاولة العرض، نزيل اللودر إجبارياً
    private static final long SHOW_SAFETY_TIMEOUT_MS = 5000;

    // Cooldown بين إعلانين لفتح التطبيق (ساعة) — التطبيق يُفتح كثيراً، فتقليل عدد مرات الظهور
    // يخفض النقرات العرضية ويرفع الـ eCPM ويحمي الحساب
    private static final long COOLDOWN_MS = 3 * 60 * 1000L;

    // صلاحية الإعلان المخزن مؤقتاً (4 ساعات)
    private static final long AD_VALIDITY_MS = 4 * 60 * 60 * 1000L;

    // 🛡️ مدة بقاء لودر الحماية معروضاً قبل الإعلان (Calm Down) — 2500ms ليستقر إصبع المستخدم
    // بعد فتح/إلغاء قفل التطبيق، فاللودر يمتص أي نقرات عشوائية طوال هذه المدة قبل ظهور الإعلان
    private static final long LOADER_HOLD_MS = 2500;

    private static final String PREFS_NAME = "AppOpenAdManager";
    private static final String KEY_HAS_LAUNCHED_BEFORE = "hasLaunchedBefore";

    private static AppOpenAdManager instance;

    private final Application app;

    private AppOpenAd appOpenAd;
    private long adLoadTime;
    private boolean isLoadingAd;
    private boolean isShowingAd;

    private long lastAdShownTime;
    private long appBackgroundedTime;
    private boolean isFirstResume = true;

    private boolean skipNextResume;

    // 🛡️ التطبيق في المقدمة فعلاً (ProcessLifecycle) — يمنع العرض إذا غادر المستخدم أثناء مهلة اللودر
    private boolean isForeground;

    // 🛡️ الـ Activity تفاعلية فعلاً (RESUMED) وليست فقط STARTED (multi-window / خلف Dialog)
    private boolean isActivityResumed;

    // callbacks في انتظار اكتمال التحميل (نجاحاً أو فشلاً) حتى لا تُفقد طلبات العرض عند وجود تحميل جارٍ
    private final List<Runnable> pendingLoadCallbacks = new ArrayList<>();

    @Nullable private Activity currentActivity;

    private AppOpenAdManager(Application app) {
        this.app = app;
    }

    public static synchronized void init(Application app) {
        if (instance != null) return;
        instance = new AppOpenAdManager(app);
        app.registerActivityLifecycleCallbacks(instance);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(instance);
    }

    public static AppOpenAdManager getInstance() {
        return instance;
    }

    public static void disableNext() {
        if (instance != null) instance.skipNextResume = true;
    }

    private boolean isAdAvailable() {
        if (appOpenAd == null) return false;
        return System.currentTimeMillis() - adLoadTime < AD_VALIDITY_MS;
    }

    // 🛡️ التحميل: الـ callback (إن وُجد) يُستدعى عند اكتمال العملية سواء نجح أو فشل.
    // إن كان هناك تحميل جارٍ، يُسجَّل في الطابور ويُستدعى عند انتهائه (يصلح تفويت العرض عند Warm Resume).
    public void loadAd(@Nullable Runnable onComplete) {
        if (isAdAvailable()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (onComplete != null) pendingLoadCallbacks.add(onComplete);
        if (isLoadingAd) return; // تحميل جارٍ بالفعل؛ الـ callback مُسجَّل وسيُستدعى عند الاكتمال
        isLoadingAd = true;

        String adUnitId = app.getString(R.string.appOpenAd);
        AppOpenAd.load(
                app,
                adUnitId,
                new AdRequest.Builder().build(),
                new AppOpenAd.AppOpenAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull AppOpenAd ad) {
                        appOpenAd = ad;
                        adLoadTime = System.currentTimeMillis();
                        isLoadingAd = false;
                        Log.d(TAG, "App Open Ad loaded successfully");
                        flushLoadCallbacks();
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        appOpenAd = null;
                        isLoadingAd = false;
                        Log.e(TAG, "Failed to load App Open Ad: " + loadAdError.getMessage());
                        flushLoadCallbacks();
                    }
                });
    }

    private void flushLoadCallbacks() {
        if (pendingLoadCallbacks.isEmpty()) return;
        List<Runnable> cbs = new ArrayList<>(pendingLoadCallbacks);
        pendingLoadCallbacks.clear();
        for (Runnable r : cbs) r.run();
    }

    // يستهلك علم التخطّي (يُضبط قبل نوايا النظام). يعيد true إذا وجب التخطّي.
    private boolean consumeSkipFlag() {
        if (skipNextResume) {
            skipNextResume = false;
            Log.d(TAG, "Skip: skipNextResume flag set");
            return true;
        }
        return false;
    }

    // 🛡️ كل شروط المنع (بدون أثر جانبي) — تُفحص قبل بدء التدفق وتُعاد فحصها لحظة العرض الفعلي.
    private boolean canShowNow() {
        if (currentActivity == null) return false;
        if (!isForeground) {
            Log.d(TAG, "Skip: app not in foreground");
            return false;
        }
        if (!isActivityResumed) {
            Log.d(TAG, "Skip: no resumed activity");
            return false;
        }
        if (isShowingAd) return false;
        if (InterstitialAdManager.getInstance().isAdShowing()) {
            Log.d(TAG, "Skip: interstitial currently showing");
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - AdControl.lastGlobalAdShowTime < AdControl.CROSS_FORMAT_COOLDOWN) {
            Log.d(TAG, "Skip: cross-format cooldown active");
            return false;
        }
        if (lastAdShownTime > 0 && now - lastAdShownTime < COOLDOWN_MS) {
            Log.d(TAG, "Skip: cooldown active");
            return false;
        }
        return true;
    }

    private void showAdIfAvailable() {
        if (consumeSkipFlag()) return;
        if (!canShowNow()) return;

        // 🛡️ استراتيجية التوفير والأمان: إذا لم يكن الإعلان جاهزاً، لا نطلب إعلان بشكل عشوائي معقد
        if (!isAdAvailable()) {
            Log.d(TAG, "Ad not available at the moment");
            return;
        }

        final Activity activity = currentActivity;

        // إظهار لودر الحماية الكلي لمنع النقرات المتسللة
        final View loader = createAndShowLoader(activity);
        final Handler handler = new Handler(Looper.getMainLooper());

        // مهلة أمان: إن لم يُحسم العرض (ظهور/فشل) تُزال الطبقة إجبارياً لتفادي شاشة سوداء عالقة
        final Runnable loaderSafety = () -> removeLoader(loader);

        handler.postDelayed(() -> {
            // 🛡️ إعادة التحقق الكامل في لحظة العرض الحقيقية: الحالة قد تتغيّر خلال مهلة اللودر
            // (مغادرة المستخدم للواجهة، ظهور إعلان آخر، نية نظام جديدة، تبدّل/إغلاق الـ Activity...).
            if (consumeSkipFlag()
                    || currentActivity != activity
                    || activity.isFinishing() || activity.isDestroyed()
                    || !canShowNow()
                    || appOpenAd == null) {
                removeLoader(loader);
                return;
            }

            appOpenAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdShowedFullScreenContent() {
                    isShowingAd = true;
                    lastAdShownTime = System.currentTimeMillis();
                    AdControl.lastGlobalAdShowTime = System.currentTimeMillis();
                    handler.removeCallbacks(loaderSafety);

                    // إخفاء اللودر فوراً بعد تغطية الإعلان للشاشة لمنع حدوث الوميض (Flash)
                    removeLoader(loader);
                    Log.d(TAG, "App Open Ad showed");
                }

                @Override
                public void onAdDismissedFullScreenContent() {
                    appOpenAd = null;
                    isShowingAd = false;
                    handler.removeCallbacks(loaderSafety);
                    removeLoader(loader);
                    // 🛡️ تم إزالة استدعاء loadAd الأعمى من هنا لتوفير الطلبات غير المستغلة!
                    Log.d(TAG, "App Open Ad dismissed");
                }

                @Override
                public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                    appOpenAd = null;
                    isShowingAd = false;
                    handler.removeCallbacks(loaderSafety);
                    removeLoader(loader);
                    Log.e(TAG, "Failed to show App Open Ad: " + adError.getMessage());
                }
            });

            // تسليح مهلة الأمان ثم العرض
            handler.postDelayed(loaderSafety, SHOW_SAFETY_TIMEOUT_MS);
            appOpenAd.show(activity);
        }, LOADER_HOLD_MS);
    }

    @Nullable
    private View createAndShowLoader(@NonNull Activity activity) {
        try {
            // 🛡️ رفع الـ Elevation والـ Translation Z لضمان التغطية المطلقة لجميع طبقات الـ UI
            final float highZ = 2500f;

            View existing = activity.findViewById(R.id.fullscreen_loader_overlay);
            if (existing != null) {
                existing.setVisibility(View.VISIBLE);
                existing.setClickable(true);
                existing.setFocusable(true);
                existing.setElevation(highZ);
                existing.setTranslationZ(highZ);
                existing.bringToFront();
                ViewGroup parent = (ViewGroup) existing.getParent();
                if (parent != null) parent.invalidate();
                return existing;
            }

            FrameLayout container = new FrameLayout(activity);
            container.setBackgroundColor(0xEE000000); // تعتيم أعلى (93%) لمنع تشتيت أو خداع المستخدم بالخلفية
            container.setClickable(true);
            container.setFocusable(true);
            container.setFocusableInTouchMode(true);
            container.setElevation(highZ);
            container.setTranslationZ(highZ);

            // امتصاص كامل أحداث اللمس المتعدد والإيماءات السريعة
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

            FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            boxParams.gravity = Gravity.CENTER;
            container.addView(box, boxParams);

            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) return null;

            content.addView(container, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            container.bringToFront();
            return container;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create loader: " + e.getMessage());
            return null;
        }
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
            Log.e(TAG, "Failed to remove loader: " + e.getMessage());
        }
    }

    // ────────────── ProcessLifecycleObserver ──────────────

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        isForeground = true;
        if (isFirstResume) {
            isFirstResume = false;

            SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_HAS_LAUNCHED_BEFORE, false)) {
                prefs.edit().putBoolean(KEY_HAS_LAUNCHED_BEFORE, true).apply();
                Log.d(TAG, "Skip: first launch after install");
                loadAd(null); // تحميل استباقي آمن للجلسة القادمة فقط
                return;
            }

            // في الـ Cold Start: نقوم بالتحميل والتشغيل المتزامن الآمن
            loadAd(() -> new Handler(Looper.getMainLooper()).postDelayed(this::showAdIfAvailable, 0));
            return;
        }

        long backgroundDuration = System.currentTimeMillis() - appBackgroundedTime;
        if (backgroundDuration < MIN_BACKGROUND_TIME_MS) {
            Log.d(TAG, "Skip: background duration insufficient");
            return;
        }

        // 🛡️ تحسين استراتيجي: عند العودة للتطبيق (Warm Resume)، نقوم بطلب الإعلان الآن، ليتم عرضه بعد انتهاء مهلة الـ Calm Down
        loadAd(() -> new Handler(Looper.getMainLooper()).postDelayed(this::showAdIfAvailable, 0));
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        isForeground = false;
        appBackgroundedTime = System.currentTimeMillis();
    }

    // ────────────── ActivityLifecycleCallbacks ──────────────

    @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        if (!isShowingAd) currentActivity = activity;
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        // لا نلتقط نشاط الإعلان نفسه (AdActivity) أثناء العرض
        if (!isShowingAd) {
            currentActivity = activity;
            isActivityResumed = true; // الواجهة تفاعلية فعلاً الآن
        }
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        // أي توقّف للتفاعل (Dialog، multi-window، انتقال، عرض الإعلان) يمنع عرض إعلان جديد
        isActivityResumed = false;
    }

    @Override public void onActivityStopped(@NonNull Activity activity) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (currentActivity == activity) {
            currentActivity = null;
            isActivityResumed = false;
        }
    }

    public void preload() {
        loadAd(null);
    }

    public boolean isShowingAd() {
        return isShowingAd;
    }
}