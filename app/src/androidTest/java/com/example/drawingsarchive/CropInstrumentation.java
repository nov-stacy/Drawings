package com.example.drawingsarchive;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Runs pixel and editor integration checks on an emulator without adding catalog entries. */
public final class CropInstrumentation extends Instrumentation {
    private MainActivity activity;
    private File fixture, saved;
    private String catalog;
    private boolean layoutOnly;
    private boolean thumbnailsOnly;
    private boolean photoReplacementOnly;
    private boolean retouchOnly;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        layoutOnly = arguments != null && "true".equals(arguments.getString("layoutOnly"));
        thumbnailsOnly = arguments != null && "true".equals(arguments.getString("thumbnailsOnly"));
        photoReplacementOnly = arguments != null && "true".equals(arguments.getString("photoReplacementOnly"));
        retouchOnly = arguments != null && "true".equals(arguments.getString("retouchOnly"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_OK;
        try {
            if (!layoutOnly && !thumbnailsOnly && !photoReplacementOnly && !retouchOnly) { testRenderer(); report("Pixel crop, perspective, rotation: passed"); }
            Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) startActivitySync(intent);
            catalog = getTargetContext().getSharedPreferences("drawings_archive", 0).getString("artworks", "[]");
            if (retouchOnly) {
                RetouchChecks.run(this, activity);
                check(catalog.equals(getTargetContext().getSharedPreferences("drawings_archive", 0).getString("artworks", "[]")), "Retouch tests must not change the catalog");
                result.putString("stream", "\nRetouch: source mapping, touch, undo/redo, compare, cancel, crop, rotation, perspective, reset, saved pixels and compact layout passed.\n");
            } else if (photoReplacementOnly) {
                PhotoReplacementChecks.run(this, activity);
                check(catalog.equals(getTargetContext().getSharedPreferences("drawings_archive", 0).getString("artworks", "[]")), "Replacement tests must restore the original catalog");
                result.putString("stream", "\nPhoto replacement: menu, picker/editor cancellation, invalid images, metadata, order, saved pixels and reload passed.\n");
            } else if (thumbnailsOnly) {
                ThumbnailChecks.run(this, activity);
                check(catalog.equals(getTargetContext().getSharedPreferences("drawings_archive", 0).getString("artworks", "[]")), "Thumbnail tests must not change catalog entries");
                result.putString("stream", "\nThumbnail size, cache, recycling, EXIF orientation, missing files and UI disk reads: passed.\n");
            } else {
            fixture = new File(getTargetContext().getCacheDir(), "crop-instrumentation-source.png");
            try (FileOutputStream out = new FileOutputStream(fixture)) { gradient().compress(Bitmap.CompressFormat.PNG, 100, out); }
            if (layoutOnly) {
                runOnMainSync(() -> {
                    try {
                        invoke("showImageEditor", new Class[]{Uri.class}, Uri.fromFile(fixture));
                        invoke("showCropEditor");
                    } catch (Exception error) { throw new RuntimeException(error); }
                });
                waitForIdleSync();
                checkCropLayout("Свободная рамка");
                runOnMainSync(() -> click("Четыре угла"));
                waitForIdleSync();
                checkCropLayout("Четыре угла");
                result.putString("stream", "\nCrop layout fits the device in both modes.\n");
            } else {
            Throwable[] failure = new Throwable[1];
            runOnMainSync(() -> { try { testEditor(); } catch (Throwable error) { failure[0] = error; } });
            if (failure[0] != null) throw new AssertionError("Editor integration", failure[0]);
            waitForIdleSync();
            check(catalog.equals(getTargetContext().getSharedPreferences("drawings_archive", 0).getString("artworks", "[]")), "Tests must not add catalog entries");
            report("Touch handles, crop drafts, modes, color settings, tabs, saving: passed");
            result.putString("stream", "\nAll crop rendering and editor integration checks passed.\n");
            }
            }
        } catch (Throwable error) {
            result.putString("stream", "\nFAILED: " + android.util.Log.getStackTraceString(error));
            resultCode = Activity.RESULT_CANCELED;
        } finally {
            if (fixture != null) fixture.delete();
            if (saved != null) saved.delete();
            if (activity != null) runOnMainSync(() -> activity.finish());
        }
        finish(resultCode, result);
    }

    private void checkCropLayout(String mode) {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try {
                String[] labels = {"Свободная рамка", "Четыре угла", mode.equals("Четыре угла") ? "По рисунку" : "Свободно", "1:1", "4:5", "↻", "Отмена", "Применить"};
                for (String label : labels) {
                    Button button = findButton(activity.getWindow().getDecorView(), label);
                    check(button != null && button.getLayout() != null, "Missing measured button " + label);
                    Rect visible = new Rect(); button.getGlobalVisibleRect(visible);
                    int width = button.getWidth() - button.getTotalPaddingLeft() - button.getTotalPaddingRight();
                    int height = button.getHeight() - button.getTotalPaddingTop() - button.getTotalPaddingBottom();
                    report(mode + ": " + label + " button=" + button.getWidth() + "x" + button.getHeight()
                            + " visible=" + visible.width() + "x" + visible.height()
                            + " text=" + button.getLayout().getWidth() + "x" + button.getLayout().getHeight()
                            + " available=" + width + "x" + height);
                    check(visible.width() >= button.getWidth() - 1 && visible.height() >= button.getHeight() - 1, "Clipped button bounds: " + label);
                    check(button.getLayout().getHeight() <= height, "Clipped text height: " + label);
                    for (int line = 0; line < button.getLineCount(); line++) {
                        check(button.getLayout().getEllipsisCount(line) == 0 && button.getLayout().getLineWidth(line) <= width + 1, "Clipped text width: " + label);
                    }
                }
            } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError("Real device crop layout", failure[0]);
    }

    private void testRenderer() {
        Bitmap source = gradient();
        CropGeometry crop = new CropGeometry();
        check(CropRenderer.apply(source, crop) == source, "Identity preserves source");
        crop.moveHandle(0, .2f, .1f, 2, .04f, .04f);
        crop.moveHandle(4, .8f, .9f, 2, .04f, .04f);
        Bitmap free = CropRenderer.apply(source, crop);
        check(free.getWidth() == 240 && free.getHeight() == 160, "Free crop output dimensions");
        close(Color.red(free.getPixel(0, 0)), 51, 3, "Free crop left pixel");
        close(Color.green(free.getPixel(0, 0)), 26, 3, "Free crop top pixel");
        crop.rotateClockwise();
        Bitmap rotated = CropRenderer.apply(source, crop);
        check(rotated.getWidth() == 160 && rotated.getHeight() == 240, "Rotated free crop dimensions");
        close(Color.red(rotated.getPixel(2, 2)), 52, 5, "Rotated crop remains on the same content");
        close(Color.green(rotated.getPixel(2, 2)), 226, 5, "Clockwise orientation");
        crop = new CropGeometry(); crop.setMode(CropGeometry.CORNERS);
        float[] trapezoid = {.25f, .1f, .75f, .1f, .9f, .9f, .1f, .9f};
        System.arraycopy(trapezoid, 0, crop.points, 0, 8);
        Bitmap perspective = CropRenderer.apply(source, crop);
        close(Color.red(perspective.getPixel(2, 2)), 64, 5, "Perspective top left maps to selected corner");
        close(Color.red(perspective.getPixel(perspective.getWidth() - 3, perspective.getHeight() - 3)), 228, 5, "Perspective bottom right maps to selected corner");
        int center = perspective.getPixel(perspective.getWidth() / 2, perspective.getHeight() / 2);
        close(Color.red(center), 128, 3, "Perspective horizontal center");
        close(Color.green(center), 104, 4, "Projective midpoint differs from affine midpoint");
        crop.setAspect(1, 2); Bitmap square = CropRenderer.apply(source, crop);
        check(square.getWidth() == square.getHeight(), "Perspective square output");
    }

    @SuppressWarnings("unchecked") private void testEditor() throws Exception {
        set("pendingTitleDraft", "Проверка обрезки"); set("pendingYearDraft", "2021");
        ArrayList<String> selected = (ArrayList<String>) get("selectedArtworkMaterials");
        selected.clear(); selected.add("Масло");
        invoke("showImageEditor", new Class[]{Uri.class}, Uri.fromFile(fixture));
        set("editorBrightness", 18f); set("editorContrast", 1.25f); set("editorFilter", 1);
        invoke("showCropEditor");
        ImageCropView view = (ImageCropView) get("editorCropView");
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 360, 280);
        // The left edge of a 2:1 source fits 16dp inside the canvas. Drag its mid-edge.
        float density = activity.getResources().getDisplayMetrics().density;
        float fitWidth = 360 - 32 * density, left = 16 * density;
        touch(view, MotionEvent.ACTION_DOWN, left, 140);
        touch(view, MotionEvent.ACTION_MOVE, left + fitWidth * .2f, 140);
        touch(view, MotionEvent.ACTION_UP, left + fitWidth * .2f, 140);
        CropGeometry draft = (CropGeometry) get("editorCropDraft");
        check(draft.points[0] > .15f, "Touch drag changes crop edge");
        click("Применить");
        CropGeometry committed = ((CropGeometry) get("editorGeometry")).copy();
        check((float) get("editorBrightness") == 18 && (float) get("editorContrast") == 1.25f && (int) get("editorFilter") == 1, "Apply preserves all color settings");
        invoke("showEditorScreen", new Class[]{boolean.class}, false);
        invoke("showEditorScreen", new Class[]{boolean.class}, true);
        check(((ImageView) get("editorPreview")).getColorFilter() != null, "Preview uses color filter after tabs");
        click("Повернуть");
        check(((CropGeometry) get("editorGeometry")).rotation == 90, "Editor rotation updates crop geometry");
        invoke("showCropEditor"); click("Четыре угла");
        draft = (CropGeometry) get("editorCropDraft"); draft.moveHandle(0, .12f, .3f, .5f, .04f, .04f);
        click("Отмена");
        check(((CropGeometry) get("editorGeometry")).mode == CropGeometry.FREE, "Cancel discards method change");
        check(((CropGeometry) get("editorGeometry")).rotation == 90, "Cancel preserves committed rotation");
        invoke("showCropEditor"); click("Четыре угла");
        draft = (CropGeometry) get("editorCropDraft");
        draft.moveHandle(0, .12f, .3f, .5f, .04f, .04f);
        check(draft.mode == CropGeometry.CORNERS && CropGeometry.validQuad(draft.points), "Both methods share valid geometry");
        view = (ImageCropView) get("editorCropView"); view.setZoom(2);
        int[] sizeBeforeZoom = draft.dimensions(400, 200);
        view.setZoom(1);
        int[] sizeAfterZoom = draft.dimensions(400, 200);
        check(sizeBeforeZoom[0] == sizeAfterZoom[0] && sizeBeforeZoom[1] == sizeAfterZoom[1], "View zoom does not cut off image data");
        click("Применить");
        Bitmap geometry = (Bitmap) invoke("editorImageGeometry");
        ColorMatrix matrix = (ColorMatrix) invoke("editorColorMatrix");
        Bitmap expected = Bitmap.createBitmap(geometry.getWidth(), geometry.getHeight(), Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(); paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        new Canvas(expected).drawBitmap(geometry, 0, 0, paint);
        invoke("saveEditorResult");
        saved = new File(((Uri) get("pendingImageUri")).getPath());
        Bitmap actual = BitmapFactory.decodeFile(saved.getAbsolutePath());
        check(actual != null && actual.getWidth() == expected.getWidth() && actual.getHeight() == expected.getHeight(), "Saved JPEG matches crop dimensions");
        int a = actual.getPixel(actual.getWidth() / 2, actual.getHeight() / 2);
        int e = expected.getPixel(expected.getWidth() / 2, expected.getHeight() / 2);
        close(Color.red(a), Color.red(e), 4, "Saved JPEG applies brightness, contrast and filter once");
        close(Color.red(a), Color.green(a), 3, "Saved grayscale remains grayscale");
        check(get("pendingTitleDraft").equals("Проверка обрезки") && get("pendingYearDraft").equals("2021"), "Metadata survives editing");
        check(committed.points[0] > .15f, "Earlier geometry snapshot is independent");
    }

    private static Bitmap gradient() {
        Bitmap bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 200; y++) for (int x = 0; x < 400; x++) bitmap.setPixel(x, y, Color.rgb(Math.round(x * 255f / 399), Math.round(y * 255f / 199), 50));
        return bitmap;
    }
    private void touch(View view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis(); MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        view.dispatchTouchEvent(event); event.recycle();
    }
    private void click(String text) {
        Button button = findButton(activity.getWindow().getDecorView(), text);
        check(button != null, "Missing button: " + text); button.performClick();
    }
    private Button findButton(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text)) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = findButton(((ViewGroup) view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private Object get(String name) throws Exception { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity); }
    private void set(String name, Object value) throws Exception { Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity, value); }
    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception { Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(activity, args); }
    private Object invoke(String name) throws Exception { return invoke(name, new Class[0]); }
    private void report(String text) { Bundle status = new Bundle(); status.putString("stream", text + "\n"); sendStatus(0, status); }
    private static void close(int actual, int expected, int tolerance, String message) { check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
