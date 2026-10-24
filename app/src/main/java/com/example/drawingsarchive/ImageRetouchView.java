package com.example.drawingsarchive;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Draft retouching in source coordinates; crop and color adjustments stay independent. */
@SuppressLint("ViewConstructor")
final class ImageRetouchView extends View {
    private static final int MAX_PATCH_PIXELS = 1_000_000;
    private static final int MAX_SELECTED_PIXELS = 250_000;
    private static final int MAX_HISTORY_BYTES = 16 * 1024 * 1024;
    private final Bitmap original;
    private final Bitmap draft;
    private final Matrix sourceToOutput;
    private final Matrix sourceToView = new Matrix(), viewToSource = new Matrix();
    private final RectF imageBounds = new RectF();
    private final int outputWidth, outputHeight;
    private final float density;
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path stroke = new Path();
    private final ArrayDeque<Patch> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "drawing-retouch"));
    private final ScaleGestureDetector scaleDetector;
    private Runnable onChange = () -> {};
    private float zoom = 1, brushSize = 24, offsetX, offsetY, lastX, lastY;
    private boolean moving, before, painting, multiTouch, busy, closed, committed;
    private int historyBytes, changes;

    ImageRetouchView(Context context, Bitmap source, CropGeometry geometry, ColorMatrixColorFilter colors) {
        super(context);
        original = source;
        draft = source.copy(Bitmap.Config.ARGB_8888, true);
        if (draft == null) throw new IllegalStateException("Could not create retouch draft");
        sourceToOutput = CropRenderer.sourceToOutput(source, geometry);
        int[] size = geometry.dimensions(source.getWidth(), source.getHeight());
        outputWidth = size[0]; outputHeight = size[1];
        density = getResources().getDisplayMetrics().density;
        photoPaint.setColorFilter(colors);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        setContentDescription("Ретушь рисунка. Проведите по небольшому пятну. Двумя пальцами можно приблизить фото.");
        setFocusable(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { cancelStroke(); return !busy; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                setZoom(zoom * detector.getScaleFactor());
                return true;
            }
        });
    }

    void setOnChangeListener(Runnable listener) { onChange = listener; }
    boolean isBusy() { return busy; }
    boolean canUndo() { return !busy && !undo.isEmpty(); }
    boolean canRedo() { return !busy && !redo.isEmpty(); }
    boolean isBefore() { return before; }
    float getZoom() { return zoom; }
    void setZoom(float value) { cancelStroke(); zoom = Math.max(1, Math.min(3, value)); updateTransform(); invalidate(); onChange.run(); }
    void setBrushSize(float value) { cancelStroke(); brushSize = Math.max(8, Math.min(60, value)); invalidate(); }
    void setMoving(boolean value) { cancelStroke(); moving = value; invalidate(); }
    void setBefore(boolean value) { cancelStroke(); before = value; invalidate(); onChange.run(); }

    void undo() {
        if (!canUndo()) return;
        Patch patch = undo.removeLast(); patch.write(draft, false); redo.addLast(patch);
        changes--; before = false; invalidate(); onChange.run();
    }
    void redo() {
        if (!canRedo()) return;
        Patch patch = redo.removeLast(); patch.write(draft, true); undo.addLast(patch);
        changes++; before = false; invalidate(); onChange.run();
    }
    Bitmap takeResult() {
        if (busy) throw new IllegalStateException("Retouch is still running");
        if (changes == 0) return original;
        committed = true;
        return draft;
    }

    private void updateTransform() {
        if (getWidth() == 0 || getHeight() == 0) return;
        float fit = Math.min(Math.max(1, getWidth() - 24 * density) / outputWidth,
                Math.max(1, getHeight() - 24 * density) / outputHeight);
        float w = outputWidth * fit * zoom, h = outputHeight * fit * zoom;
        float maxX = Math.max(0, (w - getWidth()) / 2 + 12 * density);
        float maxY = Math.max(0, (h - getHeight()) / 2 + 12 * density);
        offsetX = Math.max(-maxX, Math.min(maxX, offsetX));
        offsetY = Math.max(-maxY, Math.min(maxY, offsetY));
        float x = (getWidth() - w) / 2 + offsetX, y = (getHeight() - h) / 2 + offsetY;
        imageBounds.set(x, y, x + w, y + h);
        Matrix placement = new Matrix();
        placement.setScale(fit * zoom, fit * zoom);
        placement.postTranslate(x, y);
        sourceToView.setConcat(placement, sourceToOutput);
        if (!sourceToView.invert(viewToSource)) throw new IllegalStateException("Invalid image transform");
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { cancelStroke(); updateTransform(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (closed || imageBounds.isEmpty()) return;
        int save = canvas.save();
        canvas.clipRect(imageBounds);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(before ? original : draft, sourceToView, photoPaint);
        if (painting) {
            strokePaint.setColor(0x88AC553B); strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(brushSize * density);
            canvas.drawPath(stroke, strokePaint);
            strokePaint.setColor(Color.WHITE); strokePaint.setStrokeWidth(density);
            canvas.drawCircle(lastX, lastY, brushSize * density / 2, strokePaint);
        }
        canvas.restoreToCount(save);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (closed || imageBounds.isEmpty()) return false;
        if (busy) return true;
        scaleDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            multiTouch = false; lastX = event.getX(); lastY = event.getY();
            getParent().requestDisallowInterceptTouchEvent(true);
            if (!moving && !before && imageBounds.contains(lastX, lastY)) {
                painting = true; stroke.reset(); stroke.moveTo(lastX, lastY); stroke.lineTo(lastX + .01f, lastY);
                invalidate(); onChange.run();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) { multiTouch = true; cancelStroke(); }
        if (action == MotionEvent.ACTION_MOVE) {
            float x = event.getX(), y = event.getY();
            if ((moving || multiTouch) && !scaleDetector.isInProgress()) {
                offsetX += x - lastX; offsetY += y - lastY; updateTransform();
            } else if (painting && !multiTouch) {
                for (int i = 0; i < event.getHistorySize(); i++) stroke.lineTo(event.getHistoricalX(i), event.getHistoricalY(i));
                stroke.lineTo(x, y);
            }
            lastX = x; lastY = y; invalidate();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (painting && action == MotionEvent.ACTION_UP && !multiTouch) {
                stroke.lineTo(event.getX(), event.getY());
                finishStroke();
            } else cancelStroke();
            getParent().requestDisallowInterceptTouchEvent(false);
            if (action == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    private void cancelStroke() { painting = false; stroke.reset(); invalidate(); }

    private void finishStroke() {
        RectF bounds = new RectF(); stroke.computeBounds(bounds, true);
        bounds.inset(-brushSize * density / 2 - 2, -brushSize * density / 2 - 2);
        viewToSource.mapRect(bounds);
        int left = Math.max(0, (int) Math.floor(bounds.left) - 3), top = Math.max(0, (int) Math.floor(bounds.top) - 3);
        int right = Math.min(draft.getWidth(), (int) Math.ceil(bounds.right) + 3);
        int bottom = Math.min(draft.getHeight(), (int) Math.ceil(bounds.bottom) + 3);
        int w = right - left, h = bottom - top;
        if (w <= 0 || h <= 0) { cancelStroke(); return; }
        if ((long) w * h > MAX_PATCH_PIXELS) {
            cancelStroke(); message("Обрабатывайте небольшие участки. Для точности приблизьте фото."); return;
        }
        Bitmap maskBitmap = null;
        try {
            maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas maskCanvas = new Canvas(maskBitmap);
            maskCanvas.translate(-left, -top);
            maskCanvas.concat(viewToSource);
            maskCanvas.clipRect(imageBounds);
            maskCanvas.clipRect(0, 0, getWidth(), getHeight());
            strokePaint.setColor(Color.WHITE); strokePaint.setStyle(Paint.Style.STROKE); strokePaint.setStrokeWidth(brushSize * density);
            maskCanvas.drawPath(stroke, strokePaint);
            int[] maskPixels = new int[w * h];
            maskBitmap.getPixels(maskPixels, 0, w, 0, 0, w, h);
            boolean[] mask = new boolean[w * h];
            int selected = 0;
            for (int i = 0; i < mask.length; i++) if ((maskPixels[i] >>> 24) >= 128) { mask[i] = true; selected++; }
            if (selected == 0) { cancelStroke(); return; }
            if (selected > MAX_SELECTED_PIXELS) { cancelStroke(); message("Выделите участок поменьше или приблизьте фото."); return; }
            int[] pixels = new int[w * h];
            draft.getPixels(pixels, 0, w, left, top, w, h);
            Patch patch = new Patch(left, top, w, h, pixels);
            cancelStroke(); busy = true; onChange.run();
            worker.execute(() -> {
                try {
                    int[] repaired = pixels.clone();
                    boolean changed = HealingBrush.fill(repaired, w, h, mask);
                    post(() -> {
                        if (closed) return;
                        busy = false;
                        if (changed) {
                            patch.after = repaired; patch.write(draft, true);
                            for (Patch discarded : redo) historyBytes -= discarded.bytes();
                            redo.clear(); undo.addLast(patch); historyBytes += patch.bytes();
                            while (undo.size() > 20 || historyBytes > MAX_HISTORY_BYTES) historyBytes -= undo.removeFirst().bytes();
                            changes++; before = false;
                        }
                        invalidate(); onChange.run();
                    });
                } catch (CancellationException ignored) {
                    // The screen was closed; its draft is no longer in use.
                } catch (RuntimeException | OutOfMemoryError error) {
                    post(() -> { if (!closed) { busy = false; message("Не удалось обработать участок. Попробуйте кисть поменьше."); onChange.run(); } });
                }
            });
        } catch (RuntimeException | OutOfMemoryError error) {
            cancelStroke(); busy = false; message("Не удалось выделить участок. Попробуйте кисть поменьше."); onChange.run();
        } finally {
            if (maskBitmap != null) maskBitmap.recycle();
        }
    }

    private void message(String text) { Toast.makeText(getContext(), text, Toast.LENGTH_LONG).show(); }
    void close() {
        if (closed) return;
        closed = true; worker.shutdownNow(); undo.clear(); redo.clear();
        if (!committed) draft.recycle();
    }
    @Override protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }

    private static final class Patch {
        final int x, y, width, height;
        final int[] before;
        int[] after;
        Patch(int x, int y, int width, int height, int[] before) { this.x = x; this.y = y; this.width = width; this.height = height; this.before = before; }
        int bytes() { return before.length * 8; }
        void write(Bitmap bitmap, boolean forward) { bitmap.setPixels(forward ? after : before, 0, width, x, y, width, height); }
    }
}
