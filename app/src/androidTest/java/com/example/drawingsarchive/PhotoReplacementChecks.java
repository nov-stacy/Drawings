package com.example.drawingsarchive;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Uses temporary works and restores stored collection data after every run. */
final class PhotoReplacementChecks {
    private final MainActivity activity;
    private final Instrumentation test;
    private final Class<?> workClass;
    private File saved;

    private PhotoReplacementChecks(Instrumentation test, MainActivity activity) throws Exception {
        this.test = test;
        this.activity = activity;
        workClass = Class.forName(MainActivity.class.getName() + "$Artwork");
    }

    static void run(Instrumentation test, MainActivity activity) throws Exception {
        PhotoReplacementChecks checks = new PhotoReplacementChecks(test, activity);
        checks.run();
    }

    @SuppressWarnings("unchecked") private void run() throws Exception {
        SharedPreferences prefs = activity.getSharedPreferences("drawings_archive", 0);
        String catalog = prefs.getString("artworks", "[]");
        String materials = prefs.getString("materials_full", null);
        File original = new File(activity.getCacheDir(), "replace-test-original.png");
        File replacement = new File(activity.getCacheDir(), "replace-test-new.png");
        fixture(original, Color.RED);
        fixture(replacement, Color.BLUE);
        Uri oldUri = Uri.fromFile(original), newUri = Uri.fromFile(replacement);
        Object newer = work("newer", "2025", oldUri);
        Object peer = work("peer", "2024", oldUri);
        Object target = work("replace-target", "2024", oldUri);
        ArrayList<Object> works = (ArrayList<Object>) get("artworks");
        ArrayList<Object> snapshot = new ArrayList<>(works);
        try {
            main(() -> {
                works.clear(); works.add(newer); works.add(peer); works.add(target);
                call("saveData"); call("showHome");
                call("showArtworkDetail", new Class[]{workClass}, target);
            });
            main(() -> {
                View anchor = find((View) get("artworkCard"), "Действия с рисунком");
                check(anchor != null, "Card actions button exists");
                call("showArtworkActions", new Class[]{View.class}, anchor);
                PopupWindow popup = (PopupWindow) get("artworkActionsPopup");
                View replace = find(popup.getContentView(), "Заменить фото");
                check(replace != null, "Replacement action exists");
                replace.performClick();
            });
            main(() -> {
                View dialog = null;
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    if (find(root, "Выберите новое фото работы") != null) dialog = root;
                }
                check(dialog != null, "Replacement source dialog opens");
                check(find(dialog, "Сканировать") != null && find(dialog, "Выбрать фото") != null,
                        "Gallery and scanner available");
                find(dialog, "Отмена").performClick();
                set("pendingImageArtworkId", "replace-target");
                Bundle state = new Bundle();
                call("onSaveInstanceState", new Class[]{Bundle.class}, state);
                check("replace-target".equals(state.getString("pending_image_artwork_id")), "Picker target survives activity recreation");
                result(Activity.RESULT_CANCELED, null);
                check(get("pendingImageArtworkId") == null && get("editorTargetArtwork") == null, "Cancelled picker clears target");
                check(value(target, "uri").equals(oldUri.toString()), "Cancelled picker retains original photo");
                set("pendingImageArtworkId", "replace-target");
                result(Activity.RESULT_OK, new Intent().setData(Uri.fromFile(new File(original.getParentFile(), "missing-replacement.png"))));
                check(get("editorTargetArtwork") == null, "Unreadable photo does not leave editor target");
                check(value(target, "uri").equals(oldUri.toString()), "Unreadable photo retains original");
                set("pendingImageArtworkId", "replace-target");
                result(Activity.RESULT_OK, new Intent().setData(newUri));
                check(get("editorTargetArtwork") == target, "Selected photo targets existing work");
                check(find(activity.getWindow().getDecorView(), "Обрезать") != null, "Replacement opens image tab");
                check("Работа replace-target".equals(get("editorTitleDraft")) && "2024".equals(get("editorYearDraft")), "Metadata copied to editor");
                check(((List<?>) get("editorMaterialDraft")).size() == 2, "All original materials retained");
                call("cancelImageEditor");
                check(value(target, "uri").equals(oldUri.toString()), "Editor cancellation retains original photo");
                check(works.size() == 3 && works.get(2) == target, "Cancellation retains collection order");
                set("pendingImageArtworkId", "replace-target");
                result(Activity.RESULT_OK, new Intent().setData(newUri));
                call("saveEditorResult");
                saved = new File(Uri.parse(value(target, "uri")).getPath());
                check(saved.exists() && !value(target, "uri").equals(oldUri.toString()), "Replacement saved to a new file");
                Bitmap image = BitmapFactory.decodeFile(saved.getAbsolutePath());
                check(image != null && Color.blue(image.getPixel(20, 20)) > 240 && Color.red(image.getPixel(20, 20)) < 15,
                        "Saved photo contains replacement pixels");
                image.recycle();
                check(value(target, "id").equals("replace-target") && value(target, "title").equals("Работа replace-target")
                        && value(target, "year").equals("2024") && value(target, "material").equals("Карандаш · Акварель"), "Identity and metadata unchanged");
                check(works.size() == 3 && works.get(0) == newer && works.get(1) == peer && works.get(2) == target, "Replacement keeps manual order without adding work");
                check(get("displayedArtwork") == target, "Updated card reopens");
                call("loadData");
                check(works.size() == 3 && value(works.get(2), "uri").equals(Uri.fromFile(saved).toString()), "Replacement persists after reload");
                check(original.exists(), "Original file is preserved");
                set("pendingImageArtworkId", "no-longer-exists");
                result(Activity.RESULT_OK, new Intent().setData(newUri));
                check(works.size() == 3 && get("editorTargetArtwork") == null, "Missing target does not create a new work");
            });
        } finally {
            main(() -> {
                SharedPreferences.Editor restore = prefs.edit().putString("artworks", catalog);
                if (materials == null) restore.remove("materials_full"); else restore.putString("materials_full", materials);
                restore.commit();
                works.clear(); works.addAll(snapshot);
                call("clearEditorState"); call("showHome");
            });
            original.delete(); replacement.delete();
            if (saved != null) saved.delete();
        }
    }

    private void result(int code, Intent data) throws Exception {
        call("onActivityResult", new Class[]{int.class, int.class, Intent.class}, 1201, code, data);
    }
    private Object work(String id, String year, Uri uri) throws Exception {
        Constructor<?> constructor = workClass.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, "Работа " + id, year, "Карандаш · Акварель", uri.toString());
    }
    private static void fixture(File path, int color) throws Exception {
        Bitmap image = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888); image.eraseColor(color);
        try (FileOutputStream out = new FileOutputStream(path)) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
        image.recycle();
    }
    private View find(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
                || view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    private Object get(String name) throws Exception { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity); }
    private void set(String name, Object value) throws Exception { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity, value); }
    private String value(Object work, String name) throws Exception { Field f = workClass.getDeclaredField(name); f.setAccessible(true); return (String) f.get(work); }
    private void call(String name) throws Exception { call(name, new Class[0]); }
    private void call(String name, Class<?>[] types, Object... args) throws Exception { Method m = MainActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(activity, args); }
    private interface Checked { void run() throws Exception; }
    private void main(Checked action) throws Exception {
        Throwable[] failure = new Throwable[1];
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError("Photo replacement integration", failure[0]);
        test.waitForIdleSync();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
