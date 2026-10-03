package com.screenshot_capture.screenshot_photo;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * Thumbnail flottant style iOS : apparaît en bas à gauche juste après une capture réussie,
 * auto-dismiss au bout de {@link #AUTO_DISMISS_MS} ms. Tap → ouvre la capture dans
 * {@link SingleActivity}. Swipe horizontal → dismiss anticipé.
 * Même mécanique d'overlay que {@link FloatingButton} et donc même pré-requis : nécessite
 * {@code canDrawOverlays()}. Sans permission on no-op silencieusement.
 */
public final class PreviewThumbnail {
    private static final long AUTO_DISMISS_MS = 4000L;
    private static final long FADE_OUT_MS = 180L;

    private static View view;
    private static WindowManager wm;
    private static Bitmap currentThumb;
    private static long lastClickAt;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable autoDismiss = PreviewThumbnail::hide;

    private PreviewThumbnail() {}

    public static void show(Context ctx, Bitmap thumb, String screenshotId) {
        if (thumb == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            // Pas de perm overlay (ex : utilisateur en mode notif uniquement) → on skip proprement.
            try { thumb.recycle(); } catch (Exception ignored) {}
            return;
        }

        // Capture rapide consécutive : on remplace la preview précédente plutôt que d'empiler.
        hide();

        Context app = ctx.getApplicationContext();
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            try { thumb.recycle(); } catch (Exception ignored) {}
            return;
        }

        view = LayoutInflater.from(app).inflate(R.layout.preview_thumbnail, null);
        ImageView img = view.findViewById(R.id.previewImage);
        img.setImageBitmap(thumb);
        currentThumb = thumb;

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
        params.gravity = Gravity.BOTTOM | Gravity.START;
        float dp = app.getResources().getDisplayMetrics().density;
        params.x = (int) (12 * dp);
        params.y = (int) (24 * dp);

        view.setOnClickListener(v -> {
            // Debounce : si la session système tente un double-dispatch (anciens bugs sur
            // certaines versions), on garantit un seul startActivity → pas de double splash
            // qui provoquait BadTokenException côté SplashscreenWindowCreator.
            long now = SystemClock.uptimeMillis();
            if (now - lastClickAt < 800L) return;
            lastClickAt = now;
            try {
                Intent intent = new Intent(app, SingleActivity.class)
                        .putExtra("img_uri", screenshotId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                app.startActivity(intent);
            } catch (Exception ignored) {}
            hide();
        });

        // Swipe horizontal pour dismiss anticipé (fluide : on suit le doigt, alpha baisse avec dx).
        // On retourne TRUE partout pour consommer tout le stream touch → la détection de click
        // par défaut de View.onTouchEvent ne tirera PAS performClick en plus du nôtre. Sans ça,
        // chaque tap provoquait deux startActivity rapides et le système plantait côté
        // SplashscreenWindowCreator (« window has already been added »).
        view.setOnTouchListener(new View.OnTouchListener() {
            final int touchSlop = ViewConfiguration.get(app).getScaledTouchSlop();
            final int dismissDistance = (int) (72 * dp);
            float startX, startY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        startY = e.getRawY();
                        dragging = false;
                        // Pendant l'interaction on désactive l'auto-dismiss.
                        mainHandler.removeCallbacks(autoDismiss);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - startX;
                        float dy = e.getRawY() - startY;
                        if (!dragging && Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop) {
                            return true;
                        }
                        dragging = true;
                        v.setTranslationX(dx);
                        float fadeRange = dismissDistance * 2f;
                        v.setAlpha(Math.max(0f, 1f - Math.abs(dx) / fadeRange));
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) {
                            // Tap simple : on déclenche le click une seule fois. L'event étant
                            // consommé (return true), onTouchEvent ne le retriggera pas.
                            v.performClick();
                            return true;
                        }
                        float totalDx = e.getRawX() - startX;
                        if (Math.abs(totalDx) >= dismissDistance) {
                            hide();
                        } else {
                            // Snap back + relance l'auto-dismiss (temps plein, l'user a hésité).
                            v.animate().translationX(0f).alpha(1f).setDuration(120).start();
                            mainHandler.postDelayed(autoDismiss, AUTO_DISMISS_MS);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().translationX(0f).alpha(1f).setDuration(120).start();
                        mainHandler.postDelayed(autoDismiss, AUTO_DISMISS_MS);
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(view, params);
        } catch (Exception ignored) {
            view = null;
            wm = null;
            if (currentThumb != null) {
                try { currentThumb.recycle(); } catch (Exception e) {}
                currentThumb = null;
            }
            return;
        }

        mainHandler.postDelayed(autoDismiss, AUTO_DISMISS_MS);
    }

    public static void hide() {
        mainHandler.removeCallbacks(autoDismiss);
        if (view == null || wm == null) {
            view = null;
            wm = null;
            currentThumb = null;
            return;
        }
        // Fade out puis removeView : les refs statiques sont effacées immédiatement pour qu'une
        // nouvelle capture pendant l'animation puisse réinstaller une nouvelle preview sans conflit.
        final View v = view;
        final WindowManager w = wm;
        view = null;
        wm = null;
        currentThumb = null;
        v.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_MS)
                .withEndAction(() -> {
                    // D'abord détacher le bitmap de l'ImageView : WindowManager.removeView est
                    // asynchrone sur certaines versions, un frame en vol peut encore essayer de
                    // dessiner. En mettant le drawable à null on garantit qu'aucun draw tardif
                    // ne touchera un bitmap. Le bitmap sera libéré par le GC quand plus rien
                    // ne le référence (~80 KB, pas d'enjeu mémoire).
                    try {
                        ImageView img = v.findViewById(R.id.previewImage);
                        if (img != null) img.setImageDrawable(null);
                    } catch (Exception ignored) {}
                    try { w.removeView(v); } catch (Exception ignored) {}
                })
                .start();
    }
}
