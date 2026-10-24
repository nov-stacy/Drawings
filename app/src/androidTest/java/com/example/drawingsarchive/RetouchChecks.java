package com.example.drawingsarchive;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Exercises real screen coordinates and JPEG output, without adding collection entries. */
final class RetouchChecks {
    private static final int PAPER = Color.rgb(241, 231, 211);
    private final Instrumentation test;
    private final MainActivity activity;
    private File fixture, saved;
    private ImageRetouchView view;
    private CropGeometry geometry;

    private RetouchChecks(Instrumentation test, MainActivity activity) { this.test = test; this.activity = activity; }
    static void run(Instrumentation test, MainActivity activity) throws Exception { new RetouchChecks(test, activity).run(); }

    private void run() throws Exception {
        fixture = new File(activity.getCacheDir(), "retouch-check.png");
        Bitmap source = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
        source.eraseColor(PAPER);
        Canvas canvas = new Canvas(source);
        Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        pen.setColor(0xff667650); pen.setStyle(Paint.Style.STROKE); pen.setStrokeWidth(3);
        Path stem = new Path(); stem.moveTo(210, 710); stem.cubicTo(400, 530, 130, 280, 380, 95); canvas.drawPath(stem, pen);
        for (int i = 0; i < 6; i++) {
            int y = 180 + i * 70;
            pen.setStyle(Paint.Style.FILL); pen.setColor(0xff9fa780);
            canvas.drawOval(i % 2 == 0 ? 280 : 180, y, i % 2 == 0 ? 390 : 280, y + 38, pen);
        }
        pen.setColor(0xff382b23); canvas.drawCircle(300, 400, 5, pen); canvas.drawCircle(80, 90, 4, pen);
        try (FileOutputStream out = new FileOutputStream(fixture)) { source.compress(Bitmap.CompressFormat.PNG, 100, out); }
        source.recycle();
        try {
            mappingChecks();
            for (int mode = 0; mode < 3; mode++) integration(mode);
            main(this::open);
            test.waitForIdleSync();
            main(() -> {
                layout("Обрезать"); layout("Повернуть"); layout("Ластик"); layout("Оригинал"); layout("Ч/б"); layout("Тёплый");
                View a = button("Обрезать"), b = button("Повернуть"), c = button("Ластик");
                check(a.getTop() == b.getTop() && b.getTop() == c.getTop(), "Three tools occupy one row");
                float density = activity.getResources().getDisplayMetrics().density;
                check(a.getHeight() <= 44 * density + 1, "Tools use compact height");
                check(button("Оригинал").getHeight() <= 32 * density + 1, "Filters use compact height");
            });
            screenshot("editor-with-eraser.png");
            main(() -> { call("showRetouchEditor"); view = (ImageRetouchView) get("editorRetouchView"); });
            test.waitForIdleSync();
            screenshot("magic-eraser-screen.png");
        } finally {
            main(() -> call("clearEditorState"));
            fixture.delete(); if (saved != null) saved.delete();
        }
    }

    private void mappingChecks() {
        Bitmap gradient = Bitmap.createBitmap(200, 160, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 160; y++) for (int x = 0; x < 200; x++) gradient.setPixel(x, y, Color.rgb(x, y, 80));
        for (int rotation = 0; rotation < 360; rotation += 90) for (int mode = 0; mode < 3; mode++) {
            CropGeometry crop = new CropGeometry(); crop.rotation = rotation;
            if (mode == 1) {
                float[] p = {.12f, .08f, .88f, .08f, .88f, .92f, .12f, .92f}; System.arraycopy(p, 0, crop.points, 0, 8);
            } else if (mode == 2) {
                crop.mode = CropGeometry.CORNERS;
                float[] p = {.2f, .1f, .8f, .05f, .9f, .9f, .08f, .85f}; System.arraycopy(p, 0, crop.points, 0, 8);
            }
            Bitmap expected = CropRenderer.apply(gradient, crop);
            Bitmap actual = Bitmap.createBitmap(expected.getWidth(), expected.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(actual); canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(gradient, CropRenderer.sourceToOutput(gradient, crop), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            for (int y = 4; y < actual.getHeight() - 4; y += 11) for (int x = 4; x < actual.getWidth() - 4; x += 13) {
                int a = actual.getPixel(x, y), e = expected.getPixel(x, y);
                close(Color.red(a), Color.red(e), 2, "Retouch and crop horizontal coordinates agree");
                close(Color.green(a), Color.green(e), 2, "Retouch and crop vertical coordinates agree");
            }
            actual.recycle(); if (expected != gradient) expected.recycle();
        }
        gradient.recycle();
    }

    private void integration(int mode) throws Exception {
        main(() -> {
            open();
            geometry = (CropGeometry) get("editorGeometry");
            float[] p = mode == 2 ? new float[]{.2f, .1f, .8f, .05f, .9f, .9f, .08f, .85f}
                    : new float[]{.12f, .08f, .88f, .08f, .88f, .92f, .12f, .92f};
            System.arraycopy(p, 0, geometry.points, 0, 8);
            if (mode == 1) geometry.rotateClockwise();
            if (mode == 2) geometry.mode = CropGeometry.CORNERS;
            set("editorBrightness", 12f); set("editorContrast", 1.1f); set("editorFilter", 1);
            call("showRetouchEditor"); view = (ImageRetouchView) get("editorRetouchView");
        });
        test.waitForIdleSync();
        main(() -> {
            check(view.getHeight() > 100, "Photo has usable height");
            layout("Отмена"); layout("Применить"); layout("Отменить"); layout("Повторить"); layout("До / после");
            touch(MotionEvent.ACTION_DOWN, 1, 1); touch(MotionEvent.ACTION_UP, 1, 1);
            check(!view.canUndo(), "Touch outside photo does not retouch");
            float[] center = point(300, 400);
            touch(MotionEvent.ACTION_DOWN, center[0], center[1]); touch(MotionEvent.ACTION_CANCEL, center[0], center[1]);
            check(!view.canUndo(), "Cancelled gesture leaves no stroke");
            view.setZoom(1.8f); view.setMoving(true);
            touch(MotionEvent.ACTION_DOWN, center[0], center[1]); touch(MotionEvent.ACTION_MOVE, center[0] + 12, center[1] + 8); touch(MotionEvent.ACTION_UP, center[0] + 12, center[1] + 8);
            check(!view.canUndo(), "Panning never paints");
            view.setMoving(false); stroke();
            check(view.isBusy() && !button("Применить").isEnabled(), "Processing blocks applying an unfinished stroke");
        });
        waitForStroke();
        main(() -> {
            check(view.canUndo(), "Stroke creates undo step");
            Bitmap draft = (Bitmap) field(view, "draft");
            check(Color.red(draft.getPixel(300, 400)) > 200, "Brush removes dark defect in source coordinates");
            check(Color.red(((Bitmap) get("editorSourceBitmap")).getPixel(300, 400)) < 100, "Draft does not change editor source");
            check(draft.getPixel(80, 90) == ((Bitmap) get("editorSourceBitmap")).getPixel(80, 90), "Other marks remain exact");
            view.undo(); check(Color.red(draft.getPixel(300, 400)) < 100 && view.canRedo(), "Undo restores exact defect");
            view.redo(); check(Color.red(draft.getPixel(300, 400)) > 200, "Redo restores repaired pixels");
            view.setBefore(true); check(view.isBefore(), "Original comparison is available");
            view.setBefore(false);
            call("leaveRetouchEditor", new Class[]{boolean.class}, false);
            check(Color.red(((Bitmap) get("editorSourceBitmap")).getPixel(300, 400)) < 100, "Cancel discards draft");
            call("showRetouchEditor"); view = (ImageRetouchView) get("editorRetouchView");
        });
        test.waitForIdleSync();
        main(this::stroke); waitForStroke();
        main(() -> {
            call("leaveRetouchEditor", new Class[]{boolean.class}, true);
            check(Color.red(((Bitmap) get("editorSourceBitmap")).getPixel(300, 400)) > 200, "Apply commits retouch");
            check(get("editorGeometry") == geometry, "Crop and perspective remain editable");
            check((float) get("editorBrightness") == 12f && (float) get("editorContrast") == 1.1f && (int) get("editorFilter") == 1, "Retouch preserves color adjustments");
            call("showCropEditor"); call("leaveCropEditor", new Class[]{boolean.class}, false);
            check(Color.red(((Bitmap) get("editorSourceBitmap")).getPixel(300, 400)) > 200, "Crop round trip keeps retouch");
            Bitmap output = (Bitmap) call("editorImageGeometry");
            Matrix transform = CropRenderer.sourceToOutput((Bitmap) get("editorSourceBitmap"), geometry);
            float[] center = {300, 400}; transform.mapPoints(center);
            Bitmap expected = Bitmap.createBitmap(output.getWidth(), output.getHeight(), Bitmap.Config.ARGB_8888);
            Paint paint = new Paint(); paint.setColorFilter(new ColorMatrixColorFilter((ColorMatrix) call("editorColorMatrix")));
            new Canvas(expected).drawBitmap(output, 0, 0, paint);
            call("saveEditorResult"); saved = new File(((Uri) get("pendingImageUri")).getPath());
            Bitmap actual = BitmapFactory.decodeFile(saved.getAbsolutePath());
            check(actual.getWidth() == expected.getWidth() && actual.getHeight() == expected.getHeight(), "JPEG keeps crop dimensions");
            int x = Math.round(center[0]), y = Math.round(center[1]);
            close(Color.red(actual.getPixel(x, y)), Color.red(expected.getPixel(x, y)), 5, "JPEG includes retouch and color adjustments exactly once");
            actual.recycle(); expected.recycle(); saved.delete(); saved = null;
            open(); call("showRetouchEditor"); view = (ImageRetouchView) get("editorRetouchView");
        });
        test.waitForIdleSync(); main(this::stroke); waitForStroke();
        main(() -> {
            call("leaveRetouchEditor", new Class[]{boolean.class}, true);
            button("Сбросить изменения").performClick();
            check(Color.red(((Bitmap) get("editorSourceBitmap")).getPixel(300, 400)) < 100, "Reset restores the original photo");
            call("showRetouchEditor"); view = (ImageRetouchView) get("editorRetouchView");
        });
        test.waitForIdleSync();
        main(() -> {
            stroke();
            call("leaveRetouchEditor", new Class[]{boolean.class}, false);
            check("editor".equals(get("currentScreen")), "Cancel works during processing");
        });
    }

    @SuppressWarnings("unchecked") private void open() throws Exception {
        set("pendingTitleDraft", "Проверка ластика"); set("pendingYearDraft", "2026");
        ArrayList<String> materials = (ArrayList<String>) get("selectedArtworkMaterials"); materials.clear(); materials.add("Карандаш");
        call("showImageEditor", new Class[]{Uri.class}, Uri.fromFile(fixture));
    }
    private void stroke() throws Exception {
        float[] center = point(300, 400);
        view.setBrushSize(24);
        touch(MotionEvent.ACTION_DOWN, center[0] - 3, center[1]);
        touch(MotionEvent.ACTION_MOVE, center[0] + 2, center[1]);
        touch(MotionEvent.ACTION_UP, center[0] + 3, center[1]);
    }
    private float[] point(float x, float y) throws Exception {
        float[] p = {x, y}; ((Matrix) field(view, "sourceToView")).mapPoints(p); return p;
    }
    private void touch(int action, float x, float y) {
        long now = SystemClock.uptimeMillis(); MotionEvent e = MotionEvent.obtain(now, now, action, x, y, 0); view.dispatchTouchEvent(e); e.recycle();
    }
    private void waitForStroke() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (true) {
            boolean[] busy = {false}; main(() -> busy[0] = view.isBusy());
            if (!busy[0]) break;
            if (SystemClock.uptimeMillis() > deadline) throw new AssertionError("Retouch worker timed out");
            SystemClock.sleep(20);
        }
        test.waitForIdleSync();
    }
    private void layout(String text) {
        TextView button = button(text);
        check(button != null && button.getLayout() != null, "Measured control exists: " + text);
        Rect visible = new Rect(); button.getGlobalVisibleRect(visible);
        check(visible.height() >= button.getHeight() - 1 && visible.width() >= button.getWidth() - 1, "Control fully visible: " + text);
        int width = button.getWidth() - button.getTotalPaddingLeft() - button.getTotalPaddingRight();
        for (int i = 0; i < button.getLineCount(); i++) check(button.getLayout().getLineWidth(i) <= width + 1 && button.getLayout().getEllipsisCount(i) == 0, "Text fits: " + text);
        check(button.getLayout().getHeight() <= button.getHeight() - button.getTotalPaddingTop() - button.getTotalPaddingBottom(), "Text height fits: " + text);
    }
    private Button button(String text) { return findButton(activity.getWindow().getDecorView(), text); }
    private Button findButton(View root, String text) {
        if (root instanceof Button && text.equals(((Button) root).getText().toString())) return (Button) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            Button found = findButton(((ViewGroup) root).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private void screenshot(String name) throws Exception {
        test.waitForIdleSync();
        SystemClock.sleep(2200); // Let save toasts disappear before reviewing the screen.
        Bitmap screenshot = test.getUiAutomation().takeScreenshot();
        File file = new File(activity.getExternalFilesDir(null), name);
        try (FileOutputStream out = new FileOutputStream(file)) { screenshot.compress(Bitmap.CompressFormat.PNG, 100, out); }
        screenshot.recycle();
    }
    private interface Task { void run() throws Exception; }
    private void main(Task task) throws Exception {
        Throwable[] error = new Throwable[1]; test.runOnMainSync(() -> { try { task.run(); } catch (Throwable e) { error[0] = e; } });
        if (error[0] != null) throw new AssertionError("Retouch integration", error[0]);
    }
    private Object get(String name) throws Exception { return field(activity, name); }
    private Object field(Object object, String name) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    private void set(String name, Object value) throws Exception { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity, value); }
    private Object call(String name) throws Exception { return call(name, new Class[0]); }
    private Object call(String name, Class<?>[] types, Object... args) throws Exception { Method m = MainActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(activity, args); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void close(int a, int e, int tolerance, String message) { check(Math.abs(a - e) <= tolerance, message + ": " + a + " != " + e); }
}
