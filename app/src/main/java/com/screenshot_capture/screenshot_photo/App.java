package com.screenshot_capture.screenshot_photo;

import android.app.Application;

import com.google.android.gms.ads.MobileAds;
import com.screenshot_capture.screenshot_photo.AppOpenAdManager;

public class App extends Application {

    @Override
    public void onCreate() {
        installForegroundServiceTimeoutGuard();
        super.onCreate();
        MobileAds.initialize(this, initializationStatus -> { });
        AppOpenAdManager.init(this);
        AppOpenAdManager.getInstance().preload();
    }

    // Suppresses the system-thrown crash that fires when startForegroundService()
    // doesn't reach Service.startForeground() in time. On low-RAM / older-Android
    // devices the LMK can kill the process or scheduling delays push past the
    // deadline before our code runs — there is no try/catch site we can add to
    // prevent it, so we filter the exception here and let everything else through.
    private void installForegroundServiceTimeoutGuard() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (isForegroundServiceTimeoutCrash(throwable)) {
                return;
            }
            if (isThreadStartDuringShutdownCrash(throwable)) {
                return;
            }
            if (isWorkManagerDbCrash(throwable)) {
                return;
            }
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private static boolean isForegroundServiceTimeoutCrash(Throwable t) {
        while (t != null) {
            String name = t.getClass().getName();
            // Android 12+ variant: nested class, no message needed.
            if (name.contains("ForegroundServiceDidNotStartInTimeException")) {
                return true;
            }
            // Pre-12 variant: plain RemoteServiceException with a specific message.
            if ("android.app.RemoteServiceException".equals(name)) {
                String msg = t.getMessage();
                if (msg != null && msg.contains("startForeground")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    // Filters InternalError "Thread starting during runtime shutdown" — fired when
    // a ThreadPoolExecutor (often inside SDKs like AdMob/Play Services) tries to
    // spawn a replacement worker while the JVM is already in shutdown phase. The
    // process is dying anyway and there is no user-side site we can wrap; swallow it.
    private static boolean isThreadStartDuringShutdownCrash(Throwable t) {
        while (t != null) {
            if (t instanceof InternalError) {
                String msg = t.getMessage();
                if (msg != null && msg.contains("Thread starting during runtime shutdown")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    // Filters SQLite disk-I/O crashes thrown from WorkManager's own database init
    // (ForceStopRunnable → Room → PRAGMA journal_mode → SQLITE_IOERR_SHMOPEN). WorkManager
    // is only a transitive dependency here and runs on its own SerialExecutor thread, so there
    // is no user-side site to wrap. On low-storage / corrupted-FS devices the WAL/SHM file can't
    // be opened. We swallow ONLY when the exception is a SQLite error AND the stack is inside
    // androidx.work — the app itself uses no SQLite/Room, so nothing else is masked.
    private static boolean isWorkManagerDbCrash(Throwable t) {
        while (t != null) {
            if (t.getClass().getName().contains("SQLite")) {
                for (StackTraceElement el : t.getStackTrace()) {
                    if (el.getClassName().startsWith("androidx.work")) {
                        return true;
                    }
                }
            }
            t = t.getCause();
        }
        return false;
    }
}
