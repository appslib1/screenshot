package com.screenshot_capture.screenshot_photo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenshotService extends Service {

    private static final String TAG = "ScreenshotService";

    public static final String EXTRA_RESULT_CODE = "extra_result_code";
    public static final String EXTRA_RESULT_DATA = "extra_result_data";

    private static final String CHANNEL_ID = "screenshot_silent_v1";
    private static final int NOTIFICATION_ID = 101;
    private static final int COUNTDOWN_SECONDS = 3;
    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_SOUND = "sound";

    private MediaProjection projection;
    private HandlerThread handlerThread;
    private Handler handler;
    private boolean capturing;
    private String mode = CaptureTriggerActivity.MODE_NOTIFICATION;
    private MediaActionSound shutterSound;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        handlerThread = new HandlerThread("ScreenshotCapture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        shutterSound = new MediaActionSound();
        shutterSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand projection=" + (projection != null) + " capturing=" + capturing);

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (capturing || projection != null) {
            Log.w(TAG, "init intent ignored - capture already in flight");
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String m = intent.getStringExtra(CaptureTriggerActivity.EXTRA_MODE);
        if (m != null) mode = m;

        startForegroundCompat();

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            stopForegroundDetach();
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                projection = null;
            }
        }, handler);

        if (!capturing) {
            capturing = true;
            new Handler(Looper.getMainLooper()).post(() -> countdown(COUNTDOWN_SECONDS));
        }

        return START_NOT_STICKY;
    }

    private void stopForegroundDetach() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
    }

    private void countdown(int remaining) {
        Log.d(TAG, "countdown remaining=" + remaining);
        if (projection == null) {
            Log.w(TAG, "countdown: projection null, aborting");
            capturing = false;
            stopForegroundDetach();
            stopSelf();
            return;
        }
        if (remaining <= 0) {
            updateNotificationText(getString(R.string.clickToTakeScreenshot));
            handler.post(this::capture);
            return;
        }
        updateNotificationText(getString(R.string.capturingIn, remaining));
        new Handler(Looper.getMainLooper())
                .postDelayed(() -> countdown(remaining - 1), 1000L);
    }

    private void capture() {
        Log.d(TAG, "capture() entered");
        if (projection == null) {
            Log.w(TAG, "capture: projection null");
            capturing = false;
            broadcastResult(null);
            return;
        }

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        final int width = metrics.widthPixels;
        final int height = metrics.heightPixels;
        final int density = metrics.densityDpi;

        final ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        final VirtualDisplay[] vdHolder = new VirtualDisplay[1];

        reader.setOnImageAvailableListener(r -> {
            Log.d(TAG, "image available");
            r.setOnImageAvailableListener(null, null);
            playShutterIfEnabled();
            Image image = null;
            String savedPath = null;
            try {
                image = r.acquireLatestImage();
                if (image != null) {
                    savedPath = save(image, width, height);
                } else {
                    Log.w(TAG, "acquireLatestImage returned null");
                    notifyMain(R.string.captureFailed);
                }
            } catch (Exception e) {
                Log.e(TAG, "save failed", e);
                notifyMain(R.string.captureFailed);
            } finally {
                if (image != null) image.close();
                r.close();
                if (vdHolder[0] != null) vdHolder[0].release();
                capturing = false;
                broadcastResult(savedPath);
            }
        }, handler);

        try {
            vdHolder[0] = projection.createVirtualDisplay(
                    "ScreenshotCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
            Log.d(TAG, "VirtualDisplay created " + width + "x" + height + "@" + density);
        } catch (Exception e) {
            Log.e(TAG, "createVirtualDisplay failed", e);
            capturing = false;
            reader.close();
            notifyMain(R.string.captureFailed);
            broadcastResult(null);
        }
    }

    private void broadcastResult(String pathOrNull) {
        Intent done = new Intent(CaptureTriggerActivity.ACTION_CAPTURE_DONE)
                .setPackage(getPackageName());
        if (pathOrNull != null) {
            done.putExtra(CaptureTriggerActivity.EXTRA_PATH, pathOrNull);
        }
        sendBroadcast(done);
        Log.d(TAG, "broadcast CAPTURE_DONE path=" + pathOrNull + " mode=" + mode);
        if (CaptureTriggerActivity.MODE_OVERLAY.equals(mode)) {
            stopForeground(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        } else {
            updateNotificationText(getString(R.string.clickToTakeScreenshot));
            stopForegroundDetach();
        }
        stopSelf();
    }

    private String save(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap raw = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        raw.copyPixelsFromBuffer(buffer);

        Bitmap finalBitmap = (rowPadding == 0) ? raw : Bitmap.createBitmap(raw, 0, 0, width, height);
        if (finalBitmap != raw) raw.recycle();

        File dir = new File(getCacheDir(), "Screenshots");
        if (!dir.exists()) dir.mkdirs();
        String name = "screenshot_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".png";
        File out = new File(dir, name);
        boolean ok = false;
        try (FileOutputStream fos = new FileOutputStream(out)) {
            ok = finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (Exception e) {
            Log.e(TAG, "writing PNG failed", e);
        } finally {
            finalBitmap.recycle();
        }
        Log.d(TAG, "save ok=" + ok + " path=" + out.getAbsolutePath());

        notifyMain(ok ? R.string.screenshotSaved : R.string.captureFailed);
        return ok ? out.getAbsolutePath() : null;
    }

    private void notifyMain(int stringRes) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(this, stringRes, Toast.LENGTH_SHORT).show());
    }

    private void playShutterIfEnabled() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (p.getBoolean(KEY_SOUND, true) && shutterSound != null) {
            try { shutterSound.play(MediaActionSound.SHUTTER_CLICK); } catch (Exception ignored) {}
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Screenshot channel", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent trigger = new Intent(this, CaptureTriggerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.baseline_camera_24)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 1, trigger, piFlags))
                .build();
    }

    private void startForegroundCompat() {
        Notification n = buildNotification(getString(R.string.clickToTakeScreenshot));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void updateNotificationText(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        if (handlerThread != null) handlerThread.quitSafely();
        if (shutterSound != null) {
            try { shutterSound.release(); } catch (Exception ignored) {}
            shutterSound = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static Intent initIntent(Context ctx, int resultCode, Intent data) {
        Intent i = new Intent(ctx, ScreenshotService.class);
        i.putExtra(EXTRA_RESULT_CODE, resultCode);
        i.putExtra(EXTRA_RESULT_DATA, data);
        return i;
    }
}
