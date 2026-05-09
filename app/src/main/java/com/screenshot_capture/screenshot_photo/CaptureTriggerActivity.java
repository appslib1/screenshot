package com.screenshot_capture.screenshot_photo;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

public class CaptureTriggerActivity extends Activity {
    public static final String ACTION_CAPTURE_DONE = "screenshot.action.CAPTURE_DONE";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_NOTIFICATION = "notification";
    public static final String MODE_OVERLAY = "overlay";
    private static final int REQUEST_PROJECTION = 1;
    private static final long SAFETY_TIMEOUT_MS = 30_000L;

    private String mode;
    private boolean serviceStarted;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String path = intent.getStringExtra(EXTRA_PATH);
            Log.d("CaptureTrigger", "received CAPTURE_DONE path=" + path + " mode=" + mode);
            if (MODE_OVERLAY.equals(mode)) {
                FloatingButton.show(getApplicationContext());
            }
            if (path != null) {
                Intent open = new Intent(CaptureTriggerActivity.this, SingleActivity.class);
                open.putExtra("img_uri", path);
                open.putExtra("fromList", "no");
                startActivity(open);
            }
            finish();
        }
    };

    private final Runnable safetyFinish = () -> {
        Log.w("CaptureTrigger", "safety timeout - finishing");
        finish();
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_NOTIFICATION;

        IntentFilter filter = new IntentFilter(ACTION_CAPTURE_DONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            finish();
            return;
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_PROJECTION);
        } catch (Exception e) {
            Log.e("CaptureTrigger", "launching consent failed", e);
            finish();
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(safetyFinish, SAFETY_TIMEOUT_MS);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d("CaptureTrigger", "onNewIntent ignored (capture in flight)");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) return;
        if (serviceStarted) {
            Log.w("CaptureTrigger", "duplicate onActivityResult ignored");
            return;
        }
        if (resultCode == Activity.RESULT_OK && data != null) {
            serviceStarted = true;
            Intent svc = ScreenshotService.initIntent(this, resultCode, data);
            svc.putExtra(EXTRA_MODE, mode);
            ContextCompat.startForegroundService(this, svc);
        } else {
            Log.d("CaptureTrigger", "projection consent denied");
            if (MODE_OVERLAY.equals(mode)) FloatingButton.show(getApplicationContext());
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
