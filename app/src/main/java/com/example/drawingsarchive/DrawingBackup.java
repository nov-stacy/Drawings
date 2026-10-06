package com.example.drawingsarchive;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DrawingBackup {
    private static final String BACKUP_PREFS = "drawing_daily_backup";
    private static final String APP_PREFS = "drawings_archive";
    private static final String KEY_FOLDER = "folder";
    private static final String KEY_LAST_TIME = "last_time";
    private static final String KEY_LAST_FILE = "last_file";
    private static final String KEY_ERROR = "error";
    private static final String KEY_ARTWORKS = "artworks";
    private static final String KEY_MATERIALS = "materials";
    private static final String KEY_MATERIALS_FULL = "materials_full";
    private static final int JOB_ID = 40721;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final AtomicBoolean WRITING = new AtomicBoolean(false);

    interface Stopped {
        boolean get();
    }

    private DrawingBackup() { }

    static void choose(Context context, Uri folder) {
        context.getContentResolver().takePersistableUriPermission(
                folder,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        );
        prefs(context).edit()
                .putString(KEY_FOLDER, folder.toString())
                .putLong(KEY_LAST_TIME, 0L)
                .remove(KEY_LAST_FILE)
                .remove(KEY_ERROR)
                .apply();
        schedule(context);
        new Thread(() -> writeIfDue(context.getApplicationContext(), () -> false), "drawing-backup-now").start();
    }

    static void disable(Context context) {
        prefs(context).edit().clear().apply();
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }

    static boolean enabled(Context context) {
        return !prefs(context).getString(KEY_FOLDER, "").isEmpty();
    }

    static String status(Context context) {
        SharedPreferences values = prefs(context);
        String error = values.getString(KEY_ERROR, "");
        if (!error.isEmpty()) return "Последняя копия не создана. Проверьте доступ к выбранной папке.";
        long time = values.getLong(KEY_LAST_TIME, 0L);
        if (time == 0L) return "Первая копия создаётся сейчас";
        String formatted = new SimpleDateFormat("dd.MM.yyyy, HH:mm", new Locale("ru", "RU")).format(new Date(time));
        return "Последняя копия: " + formatted;
    }

    static String folderName(Context context) {
        String value = prefs(context).getString(KEY_FOLDER, "");
        if (value.isEmpty()) return "";
        Uri uri = Uri.parse(value);
        String documentId;
        try {
            documentId = DocumentsContract.getTreeDocumentId(uri);
        } catch (Exception ignored) {
            return "выбранная папка";
        }
        int separator = documentId.lastIndexOf(':');
        String name = separator >= 0 ? documentId.substring(separator + 1) : documentId;
        return name.isEmpty() ? "память устройства" : name;
    }

    static void schedule(Context context) {
        if (!enabled(context)) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, DrawingBackupService.class))
                .setPersisted(true)
                .setPeriodic(60L * 60L * 1000L)
                .build();
        scheduler.schedule(job);
    }

    static boolean writeIfDue(Context context, Stopped stopped) {
        SharedPreferences backupPrefs = prefs(context);
        String folderValue = backupPrefs.getString(KEY_FOLDER, "");
        if (folderValue.isEmpty()) return false;
        long lastTime = backupPrefs.getLong(KEY_LAST_TIME, 0L);
        if (lastTime > 0L && System.currentTimeMillis() - lastTime < DAY_MS) return false;
        if (!WRITING.compareAndSet(false, true)) return false;

        File staged = null;
        try {
            if (stopped.get()) return false;
            SharedPreferences app = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
            JSONArray artworks = new JSONArray(app.getString(KEY_ARTWORKS, "[]"));
            String materialsValue = app.contains(KEY_MATERIALS_FULL)
                    ? app.getString(KEY_MATERIALS_FULL, "[]")
                    : app.getString(KEY_MATERIALS, "[]");
            JSONArray materials = new JSONArray(materialsValue);

            staged = File.createTempFile("drawings-backup-", ".zip", context.getCacheDir());
            JSONObject collection = new JSONObject();
            collection.put("format", "drawing-archive-backup");
            collection.put("version", 1);
            collection.put("createdAt", System.currentTimeMillis());
            collection.put("materials", materials);
            JSONArray copiedArtworks = new JSONArray();

            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(staged))) {
                for (int i = 0; i < artworks.length(); i++) {
                    if (stopped.get()) return false;
                    JSONObject source = artworks.getJSONObject(i);
                    JSONObject copied = new JSONObject(source.toString());
                    String uriValue = source.optString("uri", "");
                    if (!uriValue.isEmpty()) {
                        String imageName = "images/drawing_" + (i + 1) + ".jpg";
                        copied.put("image", imageName);
                        zip.putNextEntry(new ZipEntry(imageName));
                        try (InputStream input = context.getContentResolver().openInputStream(Uri.parse(uriValue))) {
                            if (input == null) throw new IllegalStateException("image");
                            copy(input, zip, stopped);
                        }
                        zip.closeEntry();
                    }
                    copiedArtworks.put(copied);
                }
                collection.put("artworks", copiedArtworks);
                zip.putNextEntry(new ZipEntry("collection.json"));
                zip.write(collection.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            if (stopped.get()) return false;
            Uri folder = Uri.parse(folderValue);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder));
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
            String fileName = "drawings_backup_" + stamp + ".zip";
            Uri destination = DocumentsContract.createDocument(
                    context.getContentResolver(), parent, "application/zip", fileName);
            if (destination == null) throw new IllegalStateException("destination");
            try (InputStream input = new FileInputStream(staged);
                 OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IllegalStateException("output");
                copy(input, output, stopped);
            }
            backupPrefs.edit()
                    .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                    .putString(KEY_LAST_FILE, fileName)
                    .remove(KEY_ERROR)
                    .apply();
            return true;
        } catch (Exception error) {
            backupPrefs.edit().putString(KEY_ERROR, error.getClass().getSimpleName()).apply();
            return false;
        } finally {
            if (staged != null) staged.delete();
            WRITING.set(false);
        }
    }

    private static void copy(InputStream input, OutputStream output, Stopped stopped) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (stopped.get()) throw new InterruptedException();
            output.write(buffer, 0, read);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE);
    }
}
