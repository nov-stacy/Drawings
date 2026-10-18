package com.example.drawingsarchive;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class DrawingBackup {
    private static final String BACKUP_PREFS = "drawing_daily_backup";
    private static final String APP_PREFS = "drawings_archive";
    private static final String KEY_FOLDER = "folder";
    private static final String KEY_LAST_TIME = "last_time";
    private static final String KEY_LAST_FILE = "last_file";
    private static final String KEY_LAST_URI = "last_uri";
    private static final String KEY_ERROR = "error";
    private static final String KEY_ARTWORKS = "artworks";
    private static final String KEY_MATERIALS = "materials";
    private static final String KEY_MATERIALS_FULL = "materials_full";
    private static final int JOB_ID = 40721;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long MAX_ENTRY = 40L * 1024L * 1024L;
    private static final long MAX_TOTAL = 1024L * 1024L * 1024L;
    private static final int MAX_ARTWORKS = 20000;
    private static final AtomicBoolean WRITING = new AtomicBoolean(false);

    interface Stopped { boolean get(); }

    static final class Archive implements AutoCloseable {
        final JSONArray artworks;
        final JSONArray materials;
        final File directory;
        final int missingCount;

        Archive(JSONArray artworks, JSONArray materials, File directory, int missingCount) {
            this.artworks = artworks;
            this.materials = materials;
            this.directory = directory;
            this.missingCount = missingCount;
        }

        int totalCount() { return artworks.length(); }
        @Override public void close() { remove(directory); }
    }

    private DrawingBackup() { }

    static void choose(Context context, Uri folder) {
        context.getContentResolver().takePersistableUriPermission(folder,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        prefs(context).edit().putString(KEY_FOLDER, folder.toString()).putLong(KEY_LAST_TIME, 0L)
                .remove(KEY_ERROR).apply();
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
        if (!values.getString(KEY_ERROR, "").isEmpty())
            return "Последняя копия не создана. Проверьте доступ к выбранной папке.";
        long time = values.getLong(KEY_LAST_TIME, 0L);
        if (time == 0L) return "Первая копия создаётся сейчас";
        String formatted = new SimpleDateFormat("dd.MM.yyyy, HH:mm", new Locale("ru", "RU")).format(new Date(time));
        return "Последняя копия: " + formatted;
    }

    static String folderName(Context context) {
        String value = prefs(context).getString(KEY_FOLDER, "");
        if (value.isEmpty()) return "";
        try {
            String documentId = DocumentsContract.getTreeDocumentId(Uri.parse(value));
            int separator = documentId.lastIndexOf(':');
            String name = separator >= 0 ? documentId.substring(separator + 1) : documentId;
            return name.isEmpty() ? "память устройства" : name;
        } catch (Exception ignored) { return "выбранная папка"; }
    }

    static void schedule(Context context) {
        if (!enabled(context)) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, DrawingBackupService.class))
                .setPersisted(true).setPeriodic(60L * 60L * 1000L).build());
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
            String previousFile = backupPrefs.getString(KEY_LAST_FILE, "");
            String previousUri = backupPrefs.getString(KEY_LAST_URI, "");
            staged = File.createTempFile("drawings-backup-", ".zip", context.getCacheDir());
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(staged))) {
                writeArchive(context, output, stopped);
            }
            if (stopped.get()) return false;
            Uri folder = Uri.parse(folderValue);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder));
            String fileName = "drawings_backup_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date()) + ".zip";
            Uri destination = DocumentsContract.createDocument(context.getContentResolver(), parent, "application/zip", fileName);
            if (destination == null) throw new IllegalStateException("destination");
            try (InputStream input = new BufferedInputStream(new FileInputStream(staged));
                 OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IllegalStateException("output");
                copy(input, output, stopped);
            }
            deletePreviousBackup(context, folder, previousUri, previousFile, destination);
            backupPrefs.edit().putLong(KEY_LAST_TIME, System.currentTimeMillis()).putString(KEY_LAST_FILE, fileName)
                    .putString(KEY_LAST_URI, destination.toString())
                    .remove(KEY_ERROR).apply();
            return true;
        } catch (Exception error) {
            backupPrefs.edit().putString(KEY_ERROR, error.getClass().getSimpleName()).apply();
            return false;
        } finally {
            if (staged != null) staged.delete();
            WRITING.set(false);
        }
    }

    static void exportTo(Context context, Uri destination) throws Exception {
        try (OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IllegalStateException("output");
            writeArchive(context, new BufferedOutputStream(output), () -> false);
        }
    }

    private static void deletePreviousBackup(Context context, Uri folder, String previousUri,
                                             String previousFile, Uri current) {
        if (!previousUri.isEmpty()) {
            Uri old = Uri.parse(previousUri);
            if (!old.equals(current)) {
                try {
                    DocumentsContract.deleteDocument(context.getContentResolver(), old);
                    return;
                } catch (Exception ignored) {
                }
            }
        }
        if (previousFile.isEmpty()) return;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    folder, DocumentsContract.getTreeDocumentId(folder));
            String[] columns = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
            };
            try (Cursor cursor = context.getContentResolver().query(children, columns, null, null, null)) {
                if (cursor == null) return;
                while (cursor.moveToNext()) {
                    if (!previousFile.equals(cursor.getString(1))) continue;
                    Uri old = DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0));
                    if (!old.equals(current)) DocumentsContract.deleteDocument(context.getContentResolver(), old);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void writeArchive(Context context, OutputStream output, Stopped stopped) throws Exception {
        SharedPreferences app = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        JSONArray artworks = new JSONArray(app.getString(KEY_ARTWORKS, "[]"));
        if (artworks.length() > MAX_ARTWORKS) throw new IllegalStateException("artworks");
        String materialsValue = app.contains(KEY_MATERIALS_FULL) ? app.getString(KEY_MATERIALS_FULL, "[]")
                : app.getString(KEY_MATERIALS, "[]");
        JSONObject collection = new JSONObject();
        collection.put("format", "drawing-archive-backup");
        collection.put("version", 2);
        collection.put("createdAt", System.currentTimeMillis());
        collection.put("materials", new JSONArray(materialsValue));
        JSONArray copiedArtworks = new JSONArray();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int i = 0; i < artworks.length(); i++) {
                if (stopped.get()) throw new InterruptedException();
                JSONObject source = artworks.getJSONObject(i);
                JSONObject copied = new JSONObject(source.toString());
                if (copied.optString("id", "").isEmpty()) copied.put("id", UUID.randomUUID().toString());
                String uriValue = source.optString("uri", "");
                copied.remove("uri");
                if (!uriValue.isEmpty()) {
                    String imageName = "images/drawing_" + (i + 1) + ".jpg";
                    copied.put("image", imageName);
                    zip.putNextEntry(new ZipEntry(imageName));
                    try (InputStream input = context.getContentResolver().openInputStream(Uri.parse(uriValue))) {
                        if (input == null) throw new IllegalStateException("image");
                        copyLimited(input, zip, MAX_ENTRY, stopped);
                    }
                    zip.closeEntry();
                }
                copiedArtworks.put(copied);
            }
            collection.put("artworks", copiedArtworks);
            byte[] metadata = collection.toString(2).getBytes(StandardCharsets.UTF_8);
            if (metadata.length > MAX_ENTRY) throw new IllegalStateException("metadata");
            zip.putNextEntry(new ZipEntry("collection.json"));
            zip.write(metadata);
            zip.closeEntry();
        }
    }

    static Archive inspect(Context context, Uri source) throws Exception {
        File directory = new File(context.getCacheDir(), "restore-" + UUID.randomUUID());
        if (!directory.mkdirs()) throw new IllegalStateException("cache");
        boolean valid = false;
        try {
            Set<String> names = new HashSet<>();
            long total = 0L;
            try (InputStream raw = context.getContentResolver().openInputStream(source);
                 ZipInputStream zip = new ZipInputStream(new BufferedInputStream(require(raw)))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    boolean allowed = name.equals("collection.json") || name.matches("images/drawing_[0-9]+\\.jpg");
                    if (entry.isDirectory() || !allowed || !names.add(name)) throw new IllegalStateException("structure");
                    File destination = new File(directory, name);
                    File parent = destination.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("cache");
                    try (OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                        long copied = copyLimited(zip, output, Math.min(MAX_ENTRY, MAX_TOTAL - total), () -> false);
                        total += copied;
                    }
                    zip.closeEntry();
                }
            }
            File metadataFile = new File(directory, "collection.json");
            if (!metadataFile.isFile()) throw new IllegalStateException("metadata");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = new FileInputStream(metadataFile)) { copyLimited(input, bytes, MAX_ENTRY, () -> false); }
            JSONObject collection = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            int version = collection.getInt("version");
            if (!"drawing-archive-backup".equals(collection.getString("format")) || version < 1 || version > 2)
                throw new IllegalStateException("format");
            JSONArray sourceMaterials = collection.optJSONArray("materials");
            JSONArray cleanMaterials = new JSONArray();
            Set<String> materialNames = new HashSet<>();
            if (sourceMaterials != null) {
                if (sourceMaterials.length() > 1000) throw new IllegalStateException("materials");
                for (int i = 0; i < sourceMaterials.length(); i++) {
                    String value = sourceMaterials.getString(i).trim();
                    if (value.isEmpty() || value.length() > 200 || !materialNames.add(value.toLowerCase(Locale.ROOT)))
                        throw new IllegalStateException("materials");
                    cleanMaterials.put(value);
                }
            }
            JSONArray sourceArtworks = collection.getJSONArray("artworks");
            if (sourceArtworks.length() > MAX_ARTWORKS) throw new IllegalStateException("artworks");
            JSONArray cleanArtworks = new JSONArray();
            Set<String> archiveIds = new HashSet<>(), referencedImages = new HashSet<>();
            for (int i = 0; i < sourceArtworks.length(); i++) {
                JSONObject sourceItem = sourceArtworks.getJSONObject(i);
                String title = sourceItem.optString("title", "").trim();
                String year = sourceItem.optString("year", "").trim();
                String material = sourceItem.optString("material", "").trim();
                String image = sourceItem.optString("image", "");
                if (title.isEmpty() || title.length() > 1000 || !year.matches("[0-9]{4}")
                        || material.isEmpty() || material.length() > 1000) throw new IllegalStateException("artwork");
                if (!image.isEmpty() && (!image.matches("images/drawing_[0-9]+\\.jpg") || !names.contains(image)
                        || !referencedImages.add(image) || new File(directory, image).length() == 0L))
                    throw new IllegalStateException("image");
                String id = sourceItem.optString("id", "").trim();
                boolean legacy = id.isEmpty();
                if (legacy) id = "legacy-" + UUID.nameUUIDFromBytes((title + "\n" + year + "\n" + material + "\n" + image)
                        .getBytes(StandardCharsets.UTF_8));
                if (id.length() > 100 || !archiveIds.add(id)) throw new IllegalStateException("id");
                JSONObject clean = new JSONObject();
                clean.put("id", id); clean.put("legacy", legacy); clean.put("title", title);
                clean.put("year", year); clean.put("material", material); clean.put("image", image);
                cleanArtworks.put(clean);
            }
            for (String name : names)
                if (!name.equals("collection.json") && !referencedImages.contains(name)) throw new IllegalStateException("unused");
            int missing = countMissing(context, cleanArtworks);
            valid = true;
            return new Archive(cleanArtworks, cleanMaterials, directory, missing);
        } finally { if (!valid) remove(directory); }
    }

    static int restoreMissing(Context context, Archive archive) throws Exception {
        SharedPreferences app = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE);
        JSONArray existing = new JSONArray(app.getString(KEY_ARTWORKS, "[]"));
        String materialsValue = app.contains(KEY_MATERIALS_FULL) ? app.getString(KEY_MATERIALS_FULL, "[]")
                : app.getString(KEY_MATERIALS, "[]");
        JSONArray existingMaterials = new JSONArray(materialsValue);
        Set<String> ids = ids(existing), signatures = signatures(existing), materialNames = new HashSet<>();
        for (int i = 0; i < existingMaterials.length(); i++) materialNames.add(existingMaterials.getString(i).toLowerCase(Locale.ROOT));
        for (int i = 0; i < archive.materials.length(); i++) {
            String material = archive.materials.getString(i);
            if (materialNames.add(material.toLowerCase(Locale.ROOT))) existingMaterials.put(material);
        }
        File artworkDirectory = new File(context.getFilesDir(), "artworks");
        if (!artworkDirectory.isDirectory() && !artworkDirectory.mkdirs()) throw new IllegalStateException("storage");
        JSONArray additions = new JSONArray();
        Set<File> created = new HashSet<>();
        try {
            for (int i = 0; i < archive.artworks.length(); i++) {
                JSONObject source = archive.artworks.getJSONObject(i);
                String id = source.getString("id"), signature = signature(source);
                if (ids.contains(id) || (source.optBoolean("legacy", false) && signatures.contains(signature))) continue;
                String uri = "", image = source.optString("image", "");
                if (!image.isEmpty()) {
                    File destination = new File(artworkDirectory, "restored_" + UUID.randomUUID() + ".jpg");
                    try (InputStream input = new BufferedInputStream(new FileInputStream(new File(archive.directory, image)));
                         OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                        copyLimited(input, output, MAX_ENTRY, () -> false);
                    }
                    created.add(destination); uri = Uri.fromFile(destination).toString();
                }
                JSONObject restored = new JSONObject();
                restored.put("id", id); restored.put("title", source.getString("title"));
                restored.put("year", source.getString("year")); restored.put("material", source.getString("material"));
                restored.put("uri", uri); additions.put(restored); ids.add(id); signatures.add(signature);
            }
            for (int i = 0; i < additions.length(); i++) existing.put(additions.getJSONObject(i));
            app.edit().putString(KEY_ARTWORKS, existing.toString())
                    .putString(KEY_MATERIALS_FULL, existingMaterials.toString()).apply();
            return additions.length();
        } catch (Exception error) {
            for (File file : created) file.delete();
            throw error;
        }
    }

    private static int countMissing(Context context, JSONArray incoming) throws Exception {
        JSONArray existing = new JSONArray(context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ARTWORKS, "[]"));
        Set<String> ids = ids(existing), signatures = signatures(existing);
        int missing = 0;
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject item = incoming.getJSONObject(i);
            if (!ids.contains(item.getString("id"))
                    && !(item.optBoolean("legacy", false) && signatures.contains(signature(item)))) missing++;
        }
        return missing;
    }

    private static Set<String> ids(JSONArray artworks) throws Exception {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < artworks.length(); i++) {
            String id = artworks.getJSONObject(i).optString("id", ""); if (!id.isEmpty()) values.add(id);
        }
        return values;
    }

    private static Set<String> signatures(JSONArray artworks) throws Exception {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < artworks.length(); i++) values.add(signature(artworks.getJSONObject(i)));
        return values;
    }

    private static String signature(JSONObject item) {
        return item.optString("title", "").trim().toLowerCase(Locale.ROOT) + "\n"
                + item.optString("year", "").trim() + "\n"
                + item.optString("material", "").trim().toLowerCase(Locale.ROOT);
    }

    private static InputStream require(InputStream input) {
        if (input == null) throw new IllegalStateException("input"); return input;
    }

    private static void copy(InputStream input, OutputStream output, Stopped stopped) throws Exception {
        byte[] buffer = new byte[32 * 1024]; int read;
        while ((read = input.read(buffer)) >= 0) { if (stopped.get()) throw new InterruptedException(); output.write(buffer, 0, read); }
    }

    private static long copyLimited(InputStream input, OutputStream output, long limit, Stopped stopped) throws Exception {
        if (limit < 0) throw new IllegalStateException("size");
        byte[] buffer = new byte[32 * 1024]; long total = 0L; int read;
        while ((read = input.read(buffer)) >= 0) {
            if (stopped.get()) throw new InterruptedException(); total += read;
            if (total > limit) throw new IllegalStateException("size"); output.write(buffer, 0, read);
        }
        return total;
    }

    private static void remove(File file) {
        if (file == null) return; File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child); file.delete();
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE);
    }
}
