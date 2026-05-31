package com.screenshot_capture.screenshot_photo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Custom view that draws a bitmap fit-center with an interactive, resizable
 * crop rectangle (movable interior + four draggable corner handles).
 */
public class CropOverlayView extends View {

    private Bitmap bitmap;

    // Where the bitmap is drawn inside the view (fit-center).
    private final RectF imageRect = new RectF();
    // The crop selection, in view coordinates.
    private final RectF cropRect = new RectF();
    private boolean cropInitialized = false;

    private final float density;
    private final float handleTouchRadius;
    private final float minCropSize;

    private final Paint borderPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint handlePaint = new Paint();
    private final Paint dimPaint = new Paint();

    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_TL = 2;
    private static final int MODE_TR = 3;
    private static final int MODE_BL = 4;
    private static final int MODE_BR = 5;

    private int dragMode = MODE_NONE;
    private float lastX, lastY;

    public CropOverlayView(Context context) {
        this(context, null);
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        handleTouchRadius = 28f * density;
        minCropSize = 60f * density;

        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f * density);
        borderPaint.setAntiAlias(true);

        gridPaint.setColor(Color.argb(150, 255, 255, 255));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * density);
        gridPaint.setAntiAlias(true);

        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setAntiAlias(true);

        dimPaint.setColor(Color.argb(140, 0, 0, 0));
        dimPaint.setStyle(Paint.Style.FILL);
    }

    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        cropInitialized = false;
        requestLayout();
        invalidate();
    }

    public void resetCrop() {
        cropRect.set(imageRect);
        invalidate();
    }

    private void computeImageRect() {
        if (bitmap == null) return;
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        if (viewWidth <= 0f || viewHeight <= 0f) return;

        float scale = Math.min(viewWidth / bitmap.getWidth(), viewHeight / bitmap.getHeight());
        float scaledWidth = bitmap.getWidth() * scale;
        float scaledHeight = bitmap.getHeight() * scale;
        float left = (viewWidth - scaledWidth) / 2f;
        float top = (viewHeight - scaledHeight) / 2f;
        imageRect.set(left, top, left + scaledWidth, top + scaledHeight);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeImageRect();
        cropRect.set(imageRect);
        cropInitialized = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;

        if (imageRect.isEmpty()) computeImageRect();
        if (!cropInitialized) {
            cropRect.set(imageRect);
            cropInitialized = true;
        }

        canvas.drawBitmap(bitmap, null, imageRect, null);

        // Dim the area outside the crop rectangle.
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, dimPaint);
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, dimPaint);
        canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, dimPaint);
        canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, dimPaint);

        // Rule-of-thirds grid.
        float thirdW = cropRect.width() / 3f;
        float thirdH = cropRect.height() / 3f;
        canvas.drawLine(cropRect.left + thirdW, cropRect.top, cropRect.left + thirdW, cropRect.bottom, gridPaint);
        canvas.drawLine(cropRect.left + 2 * thirdW, cropRect.top, cropRect.left + 2 * thirdW, cropRect.bottom, gridPaint);
        canvas.drawLine(cropRect.left, cropRect.top + thirdH, cropRect.right, cropRect.top + thirdH, gridPaint);
        canvas.drawLine(cropRect.left, cropRect.top + 2 * thirdH, cropRect.right, cropRect.top + 2 * thirdH, gridPaint);

        // Border and corner handles.
        canvas.drawRect(cropRect, borderPaint);
        float r = 7f * density;
        canvas.drawCircle(cropRect.left, cropRect.top, r, handlePaint);
        canvas.drawCircle(cropRect.right, cropRect.top, r, handlePaint);
        canvas.drawCircle(cropRect.left, cropRect.bottom, r, handlePaint);
        canvas.drawCircle(cropRect.right, cropRect.bottom, r, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragMode = detectDragMode(x, y);
                lastX = x;
                lastY = y;
                return dragMode != MODE_NONE;
            case MotionEvent.ACTION_MOVE:
                applyDrag(x - lastX, y - lastY);
                lastX = x;
                lastY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = MODE_NONE;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private int detectDragMode(float x, float y) {
        if (near(x, y, cropRect.left, cropRect.top)) return MODE_TL;
        if (near(x, y, cropRect.right, cropRect.top)) return MODE_TR;
        if (near(x, y, cropRect.left, cropRect.bottom)) return MODE_BL;
        if (near(x, y, cropRect.right, cropRect.bottom)) return MODE_BR;
        if (cropRect.contains(x, y)) return MODE_MOVE;
        return MODE_NONE;
    }

    private boolean near(float px, float py, float cx, float cy) {
        return Math.abs(px - cx) <= handleTouchRadius && Math.abs(py - cy) <= handleTouchRadius;
    }

    private void applyDrag(float dx, float dy) {
        switch (dragMode) {
            case MODE_MOVE:
                float ndx = dx;
                float ndy = dy;
                if (cropRect.left + ndx < imageRect.left) ndx = imageRect.left - cropRect.left;
                if (cropRect.right + ndx > imageRect.right) ndx = imageRect.right - cropRect.right;
                if (cropRect.top + ndy < imageRect.top) ndy = imageRect.top - cropRect.top;
                if (cropRect.bottom + ndy > imageRect.bottom) ndy = imageRect.bottom - cropRect.bottom;
                cropRect.offset(ndx, ndy);
                break;
            case MODE_TL:
                cropRect.left = clamp(cropRect.left + dx, imageRect.left, cropRect.right - minCropSize);
                cropRect.top = clamp(cropRect.top + dy, imageRect.top, cropRect.bottom - minCropSize);
                break;
            case MODE_TR:
                cropRect.right = clamp(cropRect.right + dx, cropRect.left + minCropSize, imageRect.right);
                cropRect.top = clamp(cropRect.top + dy, imageRect.top, cropRect.bottom - minCropSize);
                break;
            case MODE_BL:
                cropRect.left = clamp(cropRect.left + dx, imageRect.left, cropRect.right - minCropSize);
                cropRect.bottom = clamp(cropRect.bottom + dy, cropRect.top + minCropSize, imageRect.bottom);
                break;
            case MODE_BR:
                cropRect.right = clamp(cropRect.right + dx, cropRect.left + minCropSize, imageRect.right);
                cropRect.bottom = clamp(cropRect.bottom + dy, cropRect.top + minCropSize, imageRect.bottom);
                break;
        }
    }

    private float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public Bitmap getCroppedBitmap() {
        if (bitmap == null || imageRect.isEmpty() || cropRect.isEmpty()) return null;

        float scale = bitmap.getWidth() / imageRect.width();
        int x = (int) ((cropRect.left - imageRect.left) * scale);
        int y = (int) ((cropRect.top - imageRect.top) * scale);
        int w = (int) (cropRect.width() * scale);
        int h = (int) (cropRect.height() * scale);

        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x + w > bitmap.getWidth()) w = bitmap.getWidth() - x;
        if (y + h > bitmap.getHeight()) h = bitmap.getHeight() - y;
        if (w <= 0 || h <= 0) return null;

        return Bitmap.createBitmap(bitmap, x, y, w, h);
    }
}
