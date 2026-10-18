package com.example.drawingsarchive;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Decodes card-sized images off the UI thread, including their reusable blurred backdrop. */
final class ThumbnailLoader implements AutoCloseable {
    private final ContentResolver resolver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "drawing-thumbnail"));
    private final LruCache<String, Thumbnail> cache;
    private volatile boolean closed;

    ThumbnailLoader(Context context) {
        resolver = context.getApplicationContext().getContentResolver();
        int budget = (int) Math.min(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
        cache = new LruCache<String, Thumbnail>(budget) {
            @Override protected int sizeOf(String key, Thumbnail value) {
                return value.image.getAllocationByteCount() + value.backdrop.getAllocationByteCount();
            }
        };
    }

    void bind(ImageView image, ImageView backdrop, String uri, int maxSide) {
        if (closed) return;
        String key = maxSide + ":" + uri;
        Object previous = image.getTag();
        if (previous instanceof Request) {
            Request request = (Request) previous;
            if (key.equals(request.key) && !request.cancelled && !request.failed) return;
        }
        cancel(image);
        Request request = new Request(key);
        image.setTag(request);
        Thumbnail cached = cache.get(key);
        if (cached != null) {
            display(image, backdrop, cached);
            return;
        }
        // A recycled card must never display the previous work while its new image is loading.
        image.setImageDrawable(null);
        if (backdrop != null) backdrop.setImageDrawable(null);
        request.task = new FutureTask<>(() -> {
            if (closed || request.cancelled) return null;
            Thumbnail thumbnail = cache.get(key);
            if (thumbnail == null) {
                try {
                    Bitmap bitmap = decode(Uri.parse(uri), maxSide);
                    if (bitmap != null) thumbnail = new Thumbnail(bitmap, blur(bitmap));
                } catch (Exception ignored) {
                    // Removed files and revoked document permissions show the normal placeholder.
                }
                if (closed || request.cancelled) return null;
                if (thumbnail != null) cache.put(key, thumbnail);
            }
            final Thumbnail result = thumbnail;
            main.post(() -> {
                if (closed || request.cancelled || image.getTag() != request) return;
                request.failed = result == null;
                if (result == null) image.setImageResource(R.drawable.app_icon);
                else display(image, backdrop, result);
            });
            return null;
        });
        workers.execute(request.task);
    }

    void cancel(ImageView image) {
        Object tag = image.getTag();
        if (tag instanceof Request) {
            Request request = (Request) tag;
            request.cancelled = true;
            if (request.task != null) {
                request.task.cancel(false);
                workers.remove(request.task);
            }
        }
        image.setTag(null);
    }

    private static void display(ImageView image, ImageView backdrop, Thumbnail thumbnail) {
        image.setImageBitmap(thumbnail.image);
        if (backdrop != null) backdrop.setImageBitmap(thumbnail.backdrop);
    }

    private Bitmap decode(Uri uri, int maxSide) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies EXIF orientation, as ImageView.setImageURI does.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri), (decoder, info, source) -> {
                int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                float scale = Math.min(1f, maxSide / (float) Math.max(width, height));
                decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(options.outWidth, options.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        options.inSampleSize = sample;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream stream = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap == null) return null;
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream stream = resolver.openInputStream(uri)) {
            orientation = new ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, orientation);
        } catch (Exception ignored) { }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            default: break;
        }
        if (!matrix.isIdentity()) {
            Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (oriented != bitmap) bitmap.recycle();
            bitmap = oriented;
        }
        return scaleDown(bitmap, maxSide);
    }

    private static Bitmap scaleDown(Bitmap bitmap, int maxSide) {
        float scale = Math.min(1f, maxSide / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (scale == 1f) return bitmap;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }

    private static Bitmap blur(Bitmap source) {
        float scale = Math.min(1f, 128f / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap small = Bitmap.createScaledBitmap(source, width, height, true);
        int[] pixels = new int[width * height], horizontal = new int[pixels.length];
        small.getPixels(pixels, 0, width, 0, 0, width, height);
        if (small != source) small.recycle();
        for (int pass = 0; pass < 2; pass++) {
            blurPass(pixels, horizontal, width, height, true);
            blurPass(horizontal, pixels, width, height, false);
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static void blurPass(int[] source, int[] target, int width, int height, boolean horizontal) {
        int radius = 8, divisor = radius * 2 + 1;
        int lines = horizontal ? height : width, length = horizontal ? width : height;
        int step = horizontal ? 1 : width;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * width : line;
            int alpha = 0, red = 0, green = 0, blue = 0;
            for (int k = -radius; k <= radius; k++) {
                int color = source[base + Math.max(0, Math.min(length - 1, k)) * step];
                alpha += color >>> 24; red += (color >>> 16) & 255;
                green += (color >>> 8) & 255; blue += color & 255;
            }
            for (int i = 0; i < length; i++) {
                target[base + i * step] = (alpha / divisor << 24) | (red / divisor << 16)
                        | (green / divisor << 8) | blue / divisor;
                int outgoing = source[base + Math.max(0, i - radius) * step];
                int incoming = source[base + Math.min(length - 1, i + radius + 1) * step];
                alpha += (incoming >>> 24) - (outgoing >>> 24);
                red += ((incoming >>> 16) & 255) - ((outgoing >>> 16) & 255);
                green += ((incoming >>> 8) & 255) - ((outgoing >>> 8) & 255);
                blue += (incoming & 255) - (outgoing & 255);
            }
        }
    }

    @Override public void close() {
        closed = true;
        workers.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }

    private static final class Request {
        final String key;
        volatile boolean cancelled;
        boolean failed;
        FutureTask<Void> task;
        Request(String key) { this.key = key; }
    }

    private static final class Thumbnail {
        final Bitmap image, backdrop;
        Thumbnail(Bitmap image, Bitmap backdrop) { this.image = image; this.backdrop = backdrop; }
    }
}
