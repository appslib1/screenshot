package com.screenshot_capture.screenshot_photo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;

public final class FloatingButton {
    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_BTN = "btn";

    private static View view;
    private static View removeView;
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

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        // Use TOP|START so x/y map directly to screen coordinates while dragging.
        params.gravity = Gravity.TOP | Gravity.START;
        // Initial position: bottom-right corner (button is ~88dp incl. margins).
        float dp = app.getResources().getDisplayMetrics().density;
        int buttonSize = (int) (88 * dp);
        params.x = app.getResources().getDisplayMetrics().widthPixels - buttonSize;
        params.y = app.getResources().getDisplayMetrics().heightPixels - buttonSize * 2;

        ImageButton btn = view.findViewById(R.id.floatingButton);
        btn.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float touchStartX, touchStartY;
            private boolean dragging;
            private final int touchSlop = ViewConfiguration.get(app).getScaledTouchSlop();

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        touchStartX = event.getRawX();
                        touchStartY = event.getRawY();
                        dragging = false;
                        return false;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchStartX);
                        int dy = (int) (event.getRawY() - touchStartY);
                        if (!dragging && Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop) {
                            return false;
                        }
                        if (!dragging) {
                            dragging = true;
                            showRemoveZone(app, layoutType);
                        }
                        params.x = initialX + dx;
                        params.y = initialY + dy;
                        try { wm.updateViewLayout(view, params); } catch (Exception ignored) {}
                        highlightRemoveZone(isOverRemoveZone());
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            boolean overRemove = isOverRemoveZone();
                            hideRemoveZone();
                            if (overRemove) {
                                removeByUser(app);
                            }
                            dragging = false;
                            return true;
                        }
                        // Not a drag -> treat as a click.
                        v.performClick();
                        return false;
                }
                return false;
            }
        });

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

    private static void showRemoveZone(Context app, int layoutType) {
        if (removeView != null || wm == null) return;
        removeView = LayoutInflater.from(app).inflate(R.layout.remove_zone, null);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

        try { wm.addView(removeView, params); } catch (Exception ignored) { removeView = null; }
    }

    private static void hideRemoveZone() {
        if (removeView != null && wm != null) {
            try { wm.removeView(removeView); } catch (Exception ignored) {}
        }
        removeView = null;
    }

    private static void highlightRemoveZone(boolean active) {
        if (removeView == null) return;
        ImageView icon = removeView.findViewById(R.id.removeZoneIcon);
        if (icon != null) {
            icon.setBackgroundResource(active
                    ? R.drawable.remove_zone_bg_active
                    : R.drawable.remove_zone_bg);
        }
    }

    /** True when the floating button currently overlaps the remove (X) zone. */
    private static boolean isOverRemoveZone() {
        if (view == null || removeView == null) return false;

        int[] btnLoc = new int[2];
        view.getLocationOnScreen(btnLoc);
        int btnCx = btnLoc[0] + view.getWidth() / 2;
        int btnCy = btnLoc[1] + view.getHeight() / 2;

        View icon = removeView.findViewById(R.id.removeZoneIcon);
        if (icon == null) return false;
        int[] zoneLoc = new int[2];
        icon.getLocationOnScreen(zoneLoc);
        Rect zone = new Rect(zoneLoc[0], zoneLoc[1],
                zoneLoc[0] + icon.getWidth(), zoneLoc[1] + icon.getHeight());
        // Grow the hit area a bit for easier targeting.
        zone.inset(-icon.getWidth() / 4, -icon.getHeight() / 4);
        return zone.contains(btnCx, btnCy);
    }

    /** Called when the user drops the button on the X: hide it and persist the choice. */
    private static void removeByUser(Context app) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_BTN, false).apply();
        hide();
    }

    public static void hide() {
        hideRemoveZone();
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Exception ignored) {}
        }
        view = null;
        wm = null;
    }
}
