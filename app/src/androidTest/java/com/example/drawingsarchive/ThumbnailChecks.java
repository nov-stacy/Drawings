package com.example.drawingsarchive;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.StrictMode;
import android.os.SystemClock;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises asynchronous rebinding without writing any collection data. */
final class ThumbnailChecks {
    static void run(Instrumentation test, MainActivity activity) throws Exception {
        File red = new File(activity.getCacheDir(), "thumbnail-test-red.jpg");
        File blue = new File(activity.getCacheDir(), "thumbnail-test-blue.jpg");
        ThumbnailLoader loader = new ThumbnailLoader(activity);
        ImageView[] views = new ImageView[2];
        try {
            fixture(red, Color.RED);
            fixture(blue, Color.BLUE);
            ExifInterface exif = new ExifInterface(blue.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, "6");
            exif.saveAttributes();
            test.runOnMainSync(() -> { views[0] = new ImageView(activity); views[1] = new ImageView(activity); });
            String redUri = Uri.fromFile(red).toString(), blueUri = Uri.fromFile(blue).toString();
            AtomicInteger diskReads = new AtomicInteger();
            test.runOnMainSync(() -> {
                StrictMode.ThreadPolicy old = StrictMode.getThreadPolicy();
                if (Build.VERSION.SDK_INT >= 28) StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads().penaltyListener(Runnable::run, violation -> diskReads.incrementAndGet()).build());
                try {
                    loader.bind(views[0], views[1], redUri, 240);
                    loader.bind(views[0], views[1], blueUri, 240);
                } finally { StrictMode.setThreadPolicy(old); }
            });
            Bitmap loaded = awaitBitmap(test, views[0]);
            check(loaded.getWidth() == 120 && loaded.getHeight() == 240, "Bounded thumbnail preserves EXIF rotation");
            SystemClock.sleep(150);
            check(Color.blue(bitmap(test, views[0]).getPixel(50, 50)) > 240, "Recycled card must show the blue work");
            check(bitmap(test, views[1]).getWidth() <= 128 && bitmap(test, views[1]).getHeight() <= 128, "Small preblurred backdrop");
            check(diskReads.get() == 0, "Binding must not read images from disk on the UI thread");
            test.runOnMainSync(() -> {
                loader.cancel(views[0]);
                views[0].setImageDrawable(null);
                loader.bind(views[0], views[1], blueUri, 240);
                check(((BitmapDrawable) views[0].getDrawable()).getBitmap() == loaded, "Cached bitmap is reused immediately");
                loader.bind(views[0], views[1], Uri.fromFile(new File(activity.getCacheDir(), "absent-thumbnail.jpg")).toString(), 240);
                check(views[0].getDrawable() == null && views[1].getDrawable() == null, "New work clears both recycled images");
            });
            long deadline = SystemClock.uptimeMillis() + 5000;
            boolean[] placeholder = {false};
            while (!placeholder[0] && SystemClock.uptimeMillis() < deadline) {
                test.runOnMainSync(() -> placeholder[0] = views[0].getDrawable() != null);
                SystemClock.sleep(20);
            }
            check(placeholder[0], "Missing image gets a placeholder");
            test.runOnMainSync(() -> {
                loader.bind(views[0], views[1], redUri, 220);
                loader.close();
            });
            SystemClock.sleep(150);
            test.runOnMainSync(() -> check(views[0].getDrawable() == null, "Closed loader must not update views"));
        } finally {
            test.runOnMainSync(loader::close);
            red.delete(); blue.delete();
        }
    }

    private static void fixture(File file, int color) throws Exception {
        Bitmap image = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888);
        image.eraseColor(color);
        try (FileOutputStream out = new FileOutputStream(file)) { image.compress(Bitmap.CompressFormat.JPEG, 95, out); }
        image.recycle();
    }
    private static Bitmap awaitBitmap(Instrumentation test, ImageView image) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        Bitmap result;
        do {
            result = bitmap(test, image);
            if (result != null) return result;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Thumbnail load timed out");
    }
    private static Bitmap bitmap(Instrumentation test, ImageView image) {
        Bitmap[] result = {null};
        test.runOnMainSync(() -> { if (image.getDrawable() instanceof BitmapDrawable) result[0] = ((BitmapDrawable) image.getDrawable()).getBitmap(); });
        return result[0];
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
