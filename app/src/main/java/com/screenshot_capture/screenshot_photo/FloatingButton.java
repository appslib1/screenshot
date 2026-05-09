package com.screenshot_capture.screenshot_photo;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;

public final class FloatingButton {
    private static View view;
    private static WindowManager wm;
    private static long lastClickAt;

    private FloatingButton() {}

    public static boolean isShown() {
        return view != null;
    }

    public static void show(Context ctx) {
        if (view != null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) return;

        Context app = ctx.getApplicationContext();
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        view = LayoutInflater.from(app).inflate(R.layout.floating_button, null);

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.END;

        ImageButton btn = view.findViewById(R.id.floatingButton);
        btn.setOnClickListener(v -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastClickAt < 1500L) return;
            lastClickAt = now;
            hide();
            Intent trigger = new Intent(app, CaptureTriggerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(CaptureTriggerActivity.EXTRA_MODE, CaptureTriggerActivity.MODE_OVERLAY);
            app.startActivity(trigger);
        });

        try {
            wm.addView(view, params);
        } catch (Exception ignored) {
            view = null;
            wm = null;
        }
    }

    public static void hide() {
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Exception ignored) {}
        }
        view = null;
        wm = null;
    }
}
