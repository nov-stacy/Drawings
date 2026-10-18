package com.example.drawingsarchive;

import android.content.Context;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

/** Editing only changes normalized geometry; the source bitmap remains untouched. */
@SuppressLint("ViewConstructor") // Programmatic only: requires the editor's bitmap and crop draft.
final class ImageCropView extends View {
    private static final String[] HANDLE_NAMES = {"Верхний левый угол", "Верхний край", "Верхний правый угол",
            "Правый край", "Нижний правый угол", "Нижний край", "Нижний левый угол", "Левый край"};
    private static final int MOVE_LEFT = 0x01010001, MOVE_RIGHT = 0x01010002;
    private static final int MOVE_UP = 0x01010003, MOVE_DOWN = 0x01010004;
    private final Bitmap original;
    private Bitmap image;
    private final CropGeometry geometry;
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Path mask = new Path();
    private final float[] screenVertices = new float[8];
    private final RectF imageBounds = new RectF();
    private final float density;
    private final ScaleGestureDetector scaleDetector;
    private Runnable onChange = () -> {};
    private float zoom = 1f, offsetX, offsetY, lastX, lastY;
    private int activeHandle = -1, selectedHandle, dragPointer = -1, accessibilityFocus = -1;
    private boolean movingFrame, panning, scaling;

    ImageCropView(Context context, Bitmap source, CropGeometry geometry, ColorMatrixColorFilter colors) {
        super(context);
        original = source;
        this.geometry = geometry;
        density = getResources().getDisplayMetrics().density;
        photoPaint.setColorFilter(colors);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setBackgroundColor(Color.parseColor("#E9DFDA"));
        refreshRotation();
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                scaling = true;
                activeHandle = -1; movingFrame = false; panning = false;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                setZoom(zoom * detector.getScaleFactor());
                onChange.run();
                return true;
            }
        });
    }

    void setOnChangeListener(Runnable listener) { onChange = listener; }
    float getZoom() { return zoom; }
    void setZoom(float value) {
        zoom = Math.max(1, Math.min(3, value));
        updateBounds(); invalidate();
    }
    void geometryChanged(boolean rotated) {
        if (rotated) { refreshRotation(); zoom = 1; offsetX = 0; offsetY = 0; }
        updateBounds(); invalidate();
        setContentDescription(geometry.mode == CropGeometry.FREE ? "Свободная рамка обрезки" : "Четыре угла рисунка");
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        onChange.run();
    }

    private void refreshRotation() {
        Bitmap previous = image;
        image = CropRenderer.rotated(original, geometry.rotation);
        if (previous != null && previous != original && previous != image) previous.recycle();
    }

    private void updateBounds() {
        if (getWidth() == 0 || getHeight() == 0) return;
        float fit = Math.min(Math.max(1, getWidth() - 32 * density) / image.getWidth(),
                Math.max(1, getHeight() - 32 * density) / image.getHeight());
        float w = image.getWidth() * fit * zoom, h = image.getHeight() * fit * zoom;
        offsetX = clamp(offsetX, -Math.max(0, (w - getWidth()) / 2 + 16 * density), Math.max(0, (w - getWidth()) / 2 + 16 * density));
        offsetY = clamp(offsetY, -Math.max(0, (h - getHeight()) / 2 + 16 * density), Math.max(0, (h - getHeight()) / 2 + 16 * density));
        float x = (getWidth() - w) / 2 + offsetX, y = (getHeight() - h) / 2 + offsetY;
        imageBounds.set(x, y, x + w, y + h);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { updateBounds(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageBounds.isEmpty()) return;
        canvas.drawBitmap(image, null, imageBounds, photoPaint);
        float[] p = screenPoints();
        outline.reset(); outline.moveTo(p[0], p[1]);
        for (int i = 2; i < 8; i += 2) outline.lineTo(p[i], p[i + 1]);
        outline.close();
        mask.reset(); mask.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        mask.addPath(outline); mask.setFillType(Path.FillType.EVEN_ODD);
        linePaint.setStyle(Paint.Style.FILL); linePaint.setColor(0x55000000); canvas.drawPath(mask, linePaint);
        linePaint.setStyle(Paint.Style.STROKE); linePaint.setStrokeWidth(density); linePaint.setColor(0xAAFFFFFF);
        for (int i = 1; i < 3; i++) {
            float t = i / 3f;
            canvas.drawLine(mix(p[0], p[2], t), mix(p[1], p[3], t), mix(p[6], p[4], t), mix(p[7], p[5], t), linePaint);
            canvas.drawLine(mix(p[0], p[6], t), mix(p[1], p[7], t), mix(p[2], p[4], t), mix(p[3], p[5], t), linePaint);
        }
        linePaint.setColor(Color.WHITE); linePaint.setStrokeWidth(1.5f * density); canvas.drawPath(outline, linePaint);
        for (int i = 0; i < 8; i++) {
            if (!geometry.handleVisible(i)) continue;
            float[] point = screenHandle(i);
            linePaint.setStyle(Paint.Style.FILL); linePaint.setColor(Color.WHITE);
            drawHandle(canvas, point, linePaint);
            linePaint.setStyle(Paint.Style.STROKE); linePaint.setColor(Color.parseColor("#AC553B"));
            linePaint.setStrokeWidth(2 * density); drawHandle(canvas, point, linePaint);
            if (i == accessibilityFocus) {
                linePaint.setStrokeWidth(2 * density); canvas.drawCircle(point[0], point[1], 16 * density, linePaint);
            }
        }
    }

    private void drawHandle(Canvas canvas, float[] p, Paint paint) {
        if (geometry.mode == CropGeometry.CORNERS) canvas.drawCircle(p[0], p[1], 7 * density, paint);
        else canvas.drawRoundRect(p[0] - 6 * density, p[1] - 6 * density, p[0] + 6 * density, p[1] + 6 * density, 2 * density, 2 * density, paint);
    }

    private float[] screenPoints() {
        for (int i = 0; i < 8; i += 2) { screenVertices[i] = imageBounds.left + geometry.points[i] * imageBounds.width(); screenVertices[i + 1] = imageBounds.top + geometry.points[i + 1] * imageBounds.height(); }
        return screenVertices;
    }
    private float[] screenHandle(int handle) {
        float[] p = geometry.handlePoint(handle);
        return new float[]{imageBounds.left + p[0] * imageBounds.width(), imageBounds.top + p[1] * imageBounds.height()};
    }
    private int hitHandle(float x, float y) {
        int found = -1; double nearest = 28 * density;
        for (int i = 0; i < 8; i++) {
            if (!geometry.handleVisible(i)) continue;
            float[] p = screenHandle(i); double d = Math.hypot(x - p[0], y - p[1]);
            if (d < nearest) { nearest = d; found = i; }
        }
        return found;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (imageBounds.isEmpty()) return false;
        scaleDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            scaling = false; dragPointer = event.getPointerId(0); lastX = event.getX(); lastY = event.getY();
            activeHandle = hitHandle(lastX, lastY);
            if (activeHandle >= 0) selectedHandle = activeHandle;
            float x = (lastX - imageBounds.left) / imageBounds.width(), y = (lastY - imageBounds.top) / imageBounds.height();
            movingFrame = activeHandle < 0 && geometry.mode == CropGeometry.FREE
                    && x > geometry.points[0] && x < geometry.points[4] && y > geometry.points[1] && y < geometry.points[5];
            panning = activeHandle < 0 && !movingFrame;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) { scaling = true; activeHandle = -1; movingFrame = false; panning = false; }
        if (action == MotionEvent.ACTION_MOVE && !scaling) {
            int index = event.findPointerIndex(dragPointer);
            if (index < 0) return true;
            float x = event.getX(index), y = event.getY(index);
            if (activeHandle >= 0) moveHandle(activeHandle, (x - imageBounds.left) / imageBounds.width(), (y - imageBounds.top) / imageBounds.height());
            else if (movingFrame) geometry.moveRectangle((x - lastX) / imageBounds.width(), (y - lastY) / imageBounds.height());
            else if (panning) { offsetX += x - lastX; offsetY += y - lastY; updateBounds(); }
            lastX = x; lastY = y; invalidate(); onChange.run();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activeHandle = -1; movingFrame = false; panning = false; dragPointer = -1;
            getParent().requestDisallowInterceptTouchEvent(false);
            if (action == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }

    private void moveHandle(int handle, float x, float y) {
        float minW = Math.min(.4f, 96 * density / imageBounds.width());
        float minH = Math.min(.4f, 96 * density / imageBounds.height());
        if (geometry.mode == CropGeometry.CORNERS) {
            for (int other = 0; other < 8; other += 2) {
                if (other == handle) continue;
                if (Math.hypot((x - geometry.points[other]) * imageBounds.width(), (y - geometry.points[other + 1]) * imageBounds.height()) < 48 * density) return;
            }
        }
        geometry.moveHandle(handle, x, y, geometry.imageAspect(original.getWidth(), original.getHeight()), minW, minH);
    }

    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER) { do { selectedHandle = (selectedHandle + 1) % 8; } while (!geometry.handleVisible(selectedHandle)); invalidate(); return true; }
        float dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -.01f : code == KeyEvent.KEYCODE_DPAD_RIGHT ? .01f : 0;
        float dy = code == KeyEvent.KEYCODE_DPAD_UP ? -.01f : code == KeyEvent.KEYCODE_DPAD_DOWN ? .01f : 0;
        if (dx != 0 || dy != 0) { nudge(selectedHandle, dx, dy); return true; }
        return super.onKeyDown(code, event);
    }
    private void nudge(int handle, float dx, float dy) {
        float[] p = geometry.handlePoint(handle); moveHandle(handle, p[0] + dx, p[1] + dy); invalidate(); onChange.run();
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return new AccessibilityNodeProvider() {
            @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                if (id == View.NO_ID) {
                    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(ImageCropView.this);
                    onInitializeAccessibilityNodeInfo(node);
                    for (int i = 0; i < 8; i++) if (geometry.handleVisible(i)) node.addChild(ImageCropView.this, i);
                    return node;
                }
                if (id < 0 || id >= 8 || !geometry.handleVisible(id)) return null;
                AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
                node.setSource(ImageCropView.this, id); node.setParent(ImageCropView.this);
                node.setPackageName(getContext().getPackageName()); node.setClassName("android.widget.Button");
                node.setContentDescription(HANDLE_NAMES[id]); node.setEnabled(true); node.setFocusable(true);
                float[] p = screenHandle(id); int r = Math.round(22 * density);
                Rect rect = new Rect(Math.round(p[0]) - r, Math.round(p[1]) - r, Math.round(p[0]) + r, Math.round(p[1]) + r);
                node.setBoundsInParent(rect); node.setVisibleToUser(isShown() && Rect.intersects(rect, new Rect(0, 0, getWidth(), getHeight())));
                int[] location = new int[2]; getLocationOnScreen(location); rect.offset(location[0], location[1]); node.setBoundsInScreen(rect);
                node.setAccessibilityFocused(accessibilityFocus == id);
                node.addAction(accessibilityFocus == id ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS : AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_LEFT, "Передвинуть влево"));
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_RIGHT, "Передвинуть вправо"));
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_UP, "Передвинуть вверх"));
                node.addAction(new AccessibilityNodeInfo.AccessibilityAction(MOVE_DOWN, "Передвинуть вниз"));
                return node;
            }
            @Override public boolean performAction(int id, int action, Bundle arguments) {
                if (id == View.NO_ID) return ImageCropView.this.performAccessibilityAction(action, arguments);
                if (id < 0 || id >= 8 || !geometry.handleVisible(id)) return false;
                if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS || action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                    accessibilityFocus = action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS ? id : -1;
                    AccessibilityEvent event = AccessibilityEvent.obtain(action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                            ? AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED : AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                    event.setSource(ImageCropView.this, id); event.setPackageName(getContext().getPackageName());
                    event.setClassName("android.widget.Button"); getParent().requestSendAccessibilityEvent(ImageCropView.this, event);
                    invalidate(); return true;
                }
                if (action == MOVE_LEFT || action == MOVE_RIGHT || action == MOVE_UP || action == MOVE_DOWN) {
                    nudge(id, action == MOVE_LEFT ? -.01f : action == MOVE_RIGHT ? .01f : 0,
                            action == MOVE_UP ? -.01f : action == MOVE_DOWN ? .01f : 0);
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); return true;
                }
                return false;
            }
        };
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (image != original) image.recycle();
    }
    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
