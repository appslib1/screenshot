package com.screenshot_capture.screenshot_photo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Vue d'édition pour l'annotation : texte, flèche, pixelation.
 * Même pattern que {@link CropOverlayView} : bitmap dessiné fit-center, puis une couche
 * d'annotations stockées en coordonnées bitmap (indépendantes de la taille à l'écran). Le flatten
 * dans {@link #getAnnotatedBitmap()} dessine à la résolution native, sans dégrader la qualité.
 *
 * <p>Pour la pixelation, on pré-calcule une version très réduite + agrandie de la source avec
 * nearest-neighbor (c'est le fait de ne pas filtrer qui donne le look pixelisé). La preview live et
 * le flatten utilisent la même tiny bitmap, clip-rect sur la zone d'annotation.
 */
public class AnnotateOverlayView extends View {

    public static final int TOOL_TEXT = 0;
    public static final int TOOL_ARROW = 1;
    public static final int TOOL_BLUR = 2;

    public static final int BLUR_LEVELS = 4;
    public static final int BLUR_LEVEL_DEFAULT = 1;

    public interface OnTextTapListener {
        /** Appelé quand l'utilisateur tape pour poser du texte : à l'activity d'afficher un input. */
        void onTextTap(float bitmapX, float bitmapY);
    }

    private Bitmap bitmap;
    /** Version downsamplée de la source. Canvas.drawBitmap l'étire en nearest-neighbor au moment
     * du dessin → effet pixelation sans conserver un bitmap pleine résolution en mémoire. */
    private Bitmap small;
    private int blurLevel = BLUR_LEVEL_DEFAULT;

    private final RectF imageRect = new RectF();
    private final float density;

    private final List<Annotation> annotations = new ArrayList<>();

    private int currentTool = TOOL_ARROW;
    private int currentColor = Color.RED;

    private OnTextTapListener textTapListener;

    private final Paint strokePaint = new Paint();
    private final Paint fillPaint = new Paint();
    private final Paint pixelPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint blurOutlinePaint = new Paint();

    // Dessin en cours (geste pas encore relâché).
    private boolean dragging = false;
    private final PointF dragStartBmp = new PointF();
    private final PointF dragEndBmp = new PointF();

    // Déplacement d'une annotation existante : la touch a hit un élément déjà posé → on la bouge
    // au lieu d'en créer une nouvelle. On ne modifie pas l'ordre d'insertion pour que l'undo reste
    // intuitif (= « enlève la dernière annotation ajoutée »).
    private Annotation activeAnnotation = null;
    private float lastDragBmpX, lastDragBmpY;

    public AnnotateOverlayView(Context context) {
        this(context, null);
    }

    public AnnotateOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AnnotateOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setAntiAlias(true);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);

        // Pas de filtering = nearest-neighbor sur l'upscale → gros pixels bien visibles.
        pixelPaint.setFilterBitmap(false);
        pixelPaint.setAntiAlias(false);

        textPaint.setAntiAlias(true);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        blurOutlinePaint.setStyle(Paint.Style.STROKE);
        blurOutlinePaint.setStrokeWidth(2f * density);
        blurOutlinePaint.setColor(Color.WHITE);
        blurOutlinePaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{6f * density, 4f * density}, 0f));
    }

    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        annotations.clear();
        activeAnnotation = null;
        rebuildSmall();
        requestLayout();
        invalidate();
    }

    /** 0 = fine (effet léger), {@link #BLUR_LEVELS}-1 = gros (effet fort). */
    public void setBlurLevel(int level) {
        int clamped = Math.max(0, Math.min(BLUR_LEVELS - 1, level));
        if (clamped == blurLevel && small != null) return;
        blurLevel = clamped;
        rebuildSmall();
        invalidate();
    }

    public int getBlurLevel() {
        return blurLevel;
    }

    public void setTool(int tool) {
        currentTool = tool;
        dragging = false;
        activeAnnotation = null;
        invalidate();
    }

    public void setColor(int color) {
        currentColor = color;
    }

    public void setOnTextTapListener(OnTextTapListener l) {
        this.textTapListener = l;
    }

    public void undo() {
        if (!annotations.isEmpty()) {
            Annotation removed = annotations.remove(annotations.size() - 1);
            if (activeAnnotation == removed) activeAnnotation = null;
            invalidate();
        }
    }

    public boolean hasAnnotations() {
        return !annotations.isEmpty();
    }

    /** Ajout d'une annotation texte à partir du dialog d'input de l'activity. */
    public void addText(float bitmapX, float bitmapY, String text, int color) {
        if (bitmap == null || text == null || text.isEmpty()) return;
        TextAnnotation t = new TextAnnotation();
        t.x = bitmapX;
        t.y = bitmapY;
        t.text = text;
        t.color = color;
        // Taille lisible à l'écran puis convertie en coord bitmap. ~24dp à l'écran.
        t.textSizePx = 24f * density * (bitmap.getWidth() / Math.max(1f, imageRect.width()));
        annotations.add(t);
        invalidate();
    }

    private void rebuildSmall() {
        if (small != null && !small.isRecycled()) {
            small.recycle();
        }
        small = null;
        if (bitmap == null) return;
        int tile = tileSizeForLevel(blurLevel, bitmap.getWidth());
        int w = Math.max(1, bitmap.getWidth() / tile);
        int h = Math.max(1, bitmap.getHeight() / tile);
        small = Bitmap.createScaledBitmap(bitmap, w, h, true);
    }

    /** Mapping niveau → taille de tile en px bitmap. Croissance non-linéaire : chaque niveau ~2x. */
    private static int tileSizeForLevel(int level, int bitmapWidth) {
        switch (level) {
            case 0: return Math.max(6, bitmapWidth / 120);  // léger
            case 1: return Math.max(12, bitmapWidth / 60);  // moyen (défaut)
            case 2: return Math.max(20, bitmapWidth / 35);  // fort
            default: return Math.max(32, bitmapWidth / 20); // max
        }
    }

    private void computeImageRect() {
        if (bitmap == null) return;
        float vw = getWidth();
        float vh = getHeight();
        if (vw <= 0 || vh <= 0) return;
        float scale = Math.min(vw / bitmap.getWidth(), vh / bitmap.getHeight());
        float sw = bitmap.getWidth() * scale;
        float sh = bitmap.getHeight() * scale;
        float left = (vw - sw) / 2f;
        float top = (vh - sh) / 2f;
        imageRect.set(left, top, left + sw, top + sh);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeImageRect();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        if (imageRect.isEmpty()) computeImageRect();

        canvas.drawBitmap(bitmap, null, imageRect, null);

        for (Annotation a : annotations) {
            drawAnnotationOnView(canvas, a);
        }

        if (dragging) {
            drawInProgressOnView(canvas);
        }
    }

    private void drawAnnotationOnView(Canvas canvas, Annotation a) {
        if (a instanceof TextAnnotation) {
            TextAnnotation t = (TextAnnotation) a;
            float vx = bmpToViewX(t.x);
            float vy = bmpToViewY(t.y);
            float sizeView = t.textSizePx * (imageRect.width() / bitmap.getWidth());
            textPaint.setColor(t.color);
            textPaint.setTextSize(sizeView);
            // y stocké = top du texte → on décale de l'ascent pour drawText (qui attend la baseline).
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(t.text, vx, vy - fm.ascent, textPaint);
        } else if (a instanceof ArrowAnnotation) {
            ArrowAnnotation ar = (ArrowAnnotation) a;
            float x1 = bmpToViewX(ar.x1), y1 = bmpToViewY(ar.y1);
            float x2 = bmpToViewX(ar.x2), y2 = bmpToViewY(ar.y2);
            float strokeView = ar.strokeWidthPx * (imageRect.width() / bitmap.getWidth());
            drawArrow(canvas, x1, y1, x2, y2, strokeView, ar.color);
        } else if (a instanceof BlurAnnotation) {
            BlurAnnotation b = (BlurAnnotation) a;
            float l = bmpToViewX(Math.min(b.x1, b.x2));
            float t = bmpToViewY(Math.min(b.y1, b.y2));
            float r = bmpToViewX(Math.max(b.x1, b.x2));
            float bo = bmpToViewY(Math.max(b.y1, b.y2));
            if (small != null) {
                canvas.save();
                canvas.clipRect(l, t, r, bo);
                // small stretché à imageRect en nearest-neighbor → gros pixels alignés sur
                // le même « grid » que le flatten, et confinés par le clipRect au carré blur.
                canvas.drawBitmap(small, null, imageRect, pixelPaint);
                canvas.restore();
            }
        }
    }

    private void drawInProgressOnView(Canvas canvas) {
        float x1 = bmpToViewX(dragStartBmp.x), y1 = bmpToViewY(dragStartBmp.y);
        float x2 = bmpToViewX(dragEndBmp.x), y2 = bmpToViewY(dragEndBmp.y);
        if (currentTool == TOOL_ARROW) {
            drawArrow(canvas, x1, y1, x2, y2, 4f * density, currentColor);
        } else if (currentTool == TOOL_BLUR) {
            float l = Math.min(x1, x2), t = Math.min(y1, y2);
            float r = Math.max(x1, x2), bo = Math.max(y1, y2);
            if (small != null) {
                canvas.save();
                canvas.clipRect(l, t, r, bo);
                // small stretché à imageRect en nearest-neighbor → gros pixels alignés sur
                // le même « grid » que le flatten, et confinés par le clipRect au carré blur.
                canvas.drawBitmap(small, null, imageRect, pixelPaint);
                canvas.restore();
            }
            canvas.drawRect(l, t, r, bo, blurOutlinePaint);
        }
    }

    private void drawArrow(Canvas canvas, float x1, float y1, float x2, float y2,
                           float strokeWidth, int color) {
        strokePaint.setStrokeWidth(strokeWidth);
        strokePaint.setColor(color);
        fillPaint.setColor(color);

        // Flèche = ligne + tête triangulaire. On rétracte la ligne de arrowLen pour éviter le double-dessin.
        float arrowLen = strokeWidth * 4f;
        double angle = Math.atan2(y2 - y1, x2 - x1);
        float retractX = (float) (arrowLen * Math.cos(angle));
        float retractY = (float) (arrowLen * Math.sin(angle));
        canvas.drawLine(x1, y1, x2 - retractX, y2 - retractY, strokePaint);

        // Tête
        double headAngle = Math.PI / 6; // 30°
        float hx1 = (float) (x2 - arrowLen * Math.cos(angle - headAngle));
        float hy1 = (float) (y2 - arrowLen * Math.sin(angle - headAngle));
        float hx2 = (float) (x2 - arrowLen * Math.cos(angle + headAngle));
        float hy2 = (float) (y2 - arrowLen * Math.sin(angle + headAngle));
        Path head = new Path();
        head.moveTo(x2, y2);
        head.lineTo(hx1, hy1);
        head.lineTo(hx2, hy2);
        head.close();
        canvas.drawPath(head, fillPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || imageRect.isEmpty()) return false;
        float x = event.getX();
        float y = event.getY();
        if (!imageRect.contains(x, y) && event.getAction() == MotionEvent.ACTION_DOWN) {
            return false;
        }
        float bx = viewToBmpX(x);
        float by = viewToBmpY(y);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Priorité : si on tape sur une annotation existante (n'importe quel type,
                // indépendamment de l'outil courant), on la déplace plutôt que d'en créer une.
                Annotation hit = hitTest(bx, by);
                if (hit != null) {
                    activeAnnotation = hit;
                    lastDragBmpX = bx;
                    lastDragBmpY = by;
                    invalidate();
                    return true;
                }
                if (currentTool == TOOL_TEXT) {
                    // Rien à faire pendant le drag : le texte est posé sur ACTION_UP (= tap).
                    dragStartBmp.set(bx, by);
                    return true;
                }
                dragging = true;
                dragStartBmp.set(bx, by);
                dragEndBmp.set(bx, by);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activeAnnotation != null) {
                    translateAnnotation(activeAnnotation, bx - lastDragBmpX, by - lastDragBmpY);
                    lastDragBmpX = bx;
                    lastDragBmpY = by;
                    invalidate();
                    return true;
                }
                if (currentTool == TOOL_TEXT) return true;
                dragEndBmp.set(bx, by);
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                if (activeAnnotation != null) {
                    // Déplacement terminé : l'annotation a déjà été mise à jour en place.
                    activeAnnotation = null;
                    invalidate();
                    return true;
                }
                if (currentTool == TOOL_TEXT) {
                    if (textTapListener != null) {
                        textTapListener.onTextTap(dragStartBmp.x, dragStartBmp.y);
                    }
                    return true;
                }
                dragging = false;
                dragEndBmp.set(bx, by);
                commitDragAnnotation();
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                activeAnnotation = null;
                dragging = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    /** Hit test en coords bitmap, ordre descendant (dernier dessiné = premier testé). */
    private Annotation hitTest(float bx, float by) {
        for (int i = annotations.size() - 1; i >= 0; i--) {
            Annotation a = annotations.get(i);
            if (a instanceof TextAnnotation && hitText((TextAnnotation) a, bx, by)) return a;
            if (a instanceof ArrowAnnotation && hitArrow((ArrowAnnotation) a, bx, by)) return a;
            if (a instanceof BlurAnnotation && hitBlur((BlurAnnotation) a, bx, by)) return a;
        }
        return null;
    }

    private boolean hitText(TextAnnotation t, float bx, float by) {
        textPaint.setTextSize(t.textSizePx);
        float width = textPaint.measureText(t.text);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float height = fm.descent - fm.ascent;
        // Padding généreux pour que la cible soit confortable au doigt même sur petit texte.
        float pad = Math.max(t.textSizePx * 0.3f,
                12f * density * (bitmap.getWidth() / Math.max(1f, imageRect.width())));
        return bx >= t.x - pad && bx <= t.x + width + pad
                && by >= t.y - pad && by <= t.y + height + pad;
    }

    private boolean hitArrow(ArrowAnnotation a, float bx, float by) {
        // Zone de hit = max(stroke * 4, 2% largeur image) → confortable au doigt même pour trait fin.
        float threshold = Math.max(a.strokeWidthPx * 4f, bitmap.getWidth() * 0.02f);
        return pointToSegmentDist(bx, by, a.x1, a.y1, a.x2, a.y2) < threshold;
    }

    private boolean hitBlur(BlurAnnotation b, float bx, float by) {
        float l = Math.min(b.x1, b.x2), t = Math.min(b.y1, b.y2);
        float r = Math.max(b.x1, b.x2), bo = Math.max(b.y1, b.y2);
        return bx >= l && bx <= r && by >= t && by <= bo;
    }

    private static float pointToSegmentDist(float px, float py,
                                            float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        float lenSq = dx * dx + dy * dy;
        if (lenSq == 0f) return (float) Math.hypot(px - x1, py - y1);
        float t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
        t = Math.max(0f, Math.min(1f, t));
        float cx = x1 + t * dx;
        float cy = y1 + t * dy;
        return (float) Math.hypot(px - cx, py - cy);
    }

    private void translateAnnotation(Annotation a, float dx, float dy) {
        if (a instanceof TextAnnotation) {
            TextAnnotation t = (TextAnnotation) a;
            t.x += dx;
            t.y += dy;
        } else if (a instanceof ArrowAnnotation) {
            ArrowAnnotation ar = (ArrowAnnotation) a;
            ar.x1 += dx; ar.y1 += dy;
            ar.x2 += dx; ar.y2 += dy;
        } else if (a instanceof BlurAnnotation) {
            BlurAnnotation b = (BlurAnnotation) a;
            b.x1 += dx; b.y1 += dy;
            b.x2 += dx; b.y2 += dy;
        }
    }

    private void commitDragAnnotation() {
        if (currentTool == TOOL_ARROW) {
            // Rejette les traits trop courts (tap accidentel).
            if (distanceBmp(dragStartBmp, dragEndBmp) < bitmap.getWidth() * 0.015f) return;
            ArrowAnnotation a = new ArrowAnnotation();
            a.x1 = dragStartBmp.x; a.y1 = dragStartBmp.y;
            a.x2 = dragEndBmp.x; a.y2 = dragEndBmp.y;
            a.color = currentColor;
            a.strokeWidthPx = 4f * density * (bitmap.getWidth() / Math.max(1f, imageRect.width()));
            annotations.add(a);
        } else if (currentTool == TOOL_BLUR) {
            float l = Math.min(dragStartBmp.x, dragEndBmp.x);
            float t = Math.min(dragStartBmp.y, dragEndBmp.y);
            float r = Math.max(dragStartBmp.x, dragEndBmp.x);
            float b = Math.max(dragStartBmp.y, dragEndBmp.y);
            // Rejette les rects trop petits.
            if (r - l < bitmap.getWidth() * 0.02f || b - t < bitmap.getWidth() * 0.02f) return;
            BlurAnnotation ba = new BlurAnnotation();
            ba.x1 = l; ba.y1 = t; ba.x2 = r; ba.y2 = b;
            annotations.add(ba);
        }
    }

    private static float distanceBmp(PointF a, PointF b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float bmpToViewX(float bx) {
        return imageRect.left + bx * imageRect.width() / bitmap.getWidth();
    }
    private float bmpToViewY(float by) {
        return imageRect.top + by * imageRect.height() / bitmap.getHeight();
    }
    private float viewToBmpX(float vx) {
        float x = (vx - imageRect.left) * bitmap.getWidth() / imageRect.width();
        return clamp(x, 0f, bitmap.getWidth());
    }
    private float viewToBmpY(float vy) {
        float y = (vy - imageRect.top) * bitmap.getHeight() / imageRect.height();
        return clamp(y, 0f, bitmap.getHeight());
    }
    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** Produit un nouveau bitmap avec toutes les annotations aplaties, en résolution native. */
    public Bitmap getAnnotatedBitmap() {
        if (bitmap == null) return null;
        Bitmap out = bitmap.copy(bitmap.getConfig(), true);
        Canvas canvas = new Canvas(out);

        Paint sp = new Paint(strokePaint);
        Paint fp = new Paint(fillPaint);
        Paint tp = new Paint(textPaint);

        for (Annotation a : annotations) {
            if (a instanceof TextAnnotation) {
                TextAnnotation t = (TextAnnotation) a;
                tp.setColor(t.color);
                tp.setTextSize(t.textSizePx);
                Paint.FontMetrics fm = tp.getFontMetrics();
                canvas.drawText(t.text, t.x, t.y - fm.ascent, tp);
            } else if (a instanceof ArrowAnnotation) {
                ArrowAnnotation ar = (ArrowAnnotation) a;
                sp.setStrokeWidth(ar.strokeWidthPx);
                sp.setColor(ar.color);
                fp.setColor(ar.color);
                float arrowLen = ar.strokeWidthPx * 4f;
                double angle = Math.atan2(ar.y2 - ar.y1, ar.x2 - ar.x1);
                float retractX = (float) (arrowLen * Math.cos(angle));
                float retractY = (float) (arrowLen * Math.sin(angle));
                canvas.drawLine(ar.x1, ar.y1, ar.x2 - retractX, ar.y2 - retractY, sp);
                double headAngle = Math.PI / 6;
                float hx1 = (float) (ar.x2 - arrowLen * Math.cos(angle - headAngle));
                float hy1 = (float) (ar.y2 - arrowLen * Math.sin(angle - headAngle));
                float hx2 = (float) (ar.x2 - arrowLen * Math.cos(angle + headAngle));
                float hy2 = (float) (ar.y2 - arrowLen * Math.sin(angle + headAngle));
                Path head = new Path();
                head.moveTo(ar.x2, ar.y2);
                head.lineTo(hx1, hy1);
                head.lineTo(hx2, hy2);
                head.close();
                canvas.drawPath(head, fp);
            } else if (a instanceof BlurAnnotation) {
                BlurAnnotation b = (BlurAnnotation) a;
                if (small == null) continue;
                int l = Math.round(b.x1), t = Math.round(b.y1);
                int r = Math.round(b.x2), bo = Math.round(b.y2);
                canvas.save();
                canvas.clipRect(l, t, r, bo);
                canvas.drawBitmap(small, null,
                        new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), pixelPaint);
                canvas.restore();
            }
        }
        return out;
    }

    public void releaseResources() {
        if (small != null && !small.isRecycled()) {
            small.recycle();
        }
        small = null;
    }

    // --- Modèle ---
    abstract static class Annotation {
        int color;
    }
    static class TextAnnotation extends Annotation {
        float x, y;
        String text;
        float textSizePx;
    }
    static class ArrowAnnotation extends Annotation {
        float x1, y1, x2, y2;
        float strokeWidthPx;
    }
    static class BlurAnnotation extends Annotation {
        float x1, y1, x2, y2;
    }
}
