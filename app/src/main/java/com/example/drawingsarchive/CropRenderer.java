package com.example.drawingsarchive;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;

final class CropRenderer {
    /** Same mapping as apply(), including the translation introduced by Bitmap rotation. */
    static Matrix sourceToOutput(Bitmap source, CropGeometry geometry) {
        Matrix rotation = new Matrix();
        rotation.setRotate(geometry.rotation);
        RectF bounds = new RectF(0, 0, source.getWidth(), source.getHeight());
        rotation.mapRect(bounds);
        rotation.postTranslate(-bounds.left, -bounds.top);
        if (geometry.isFullImage()) return rotation;
        float[] from = geometry.points.clone();
        for (int i = 0; i < 8; i += 2) {
            from[i] *= bounds.width();
            from[i + 1] *= bounds.height();
        }
        int[] size = geometry.dimensions(source.getWidth(), source.getHeight());
        float[] to = {0, 0, size[0], 0, size[0], size[1], 0, size[1]};
        Matrix crop = new Matrix(), result = new Matrix();
        if (!crop.setPolyToPoly(from, 0, to, 0, 4)) throw new IllegalArgumentException("Invalid crop");
        result.setConcat(crop, rotation);
        return result;
    }

    static Bitmap rotated(Bitmap source, int rotation) {
        if (rotation == 0) return source;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    static Bitmap apply(Bitmap source, CropGeometry geometry) {
        Bitmap rotated = rotated(source, geometry.rotation);
        if (geometry.isFullImage()) return rotated;
        try {
            int[] size = geometry.dimensions(source.getWidth(), source.getHeight());
            float[] from = geometry.points.clone();
            for (int i = 0; i < 8; i += 2) {
                from[i] *= rotated.getWidth();
                from[i + 1] *= rotated.getHeight();
            }
            float[] to = {0, 0, size[0], 0, size[0], size[1], 0, size[1]};
            Matrix transform = new Matrix();
            if (!transform.setPolyToPoly(from, 0, to, 0, 4)) throw new IllegalArgumentException("Invalid crop");
            Bitmap result = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(rotated, transform, paint);
            return result;
        } finally {
            if (rotated != source) rotated.recycle();
        }
    }

    private CropRenderer() {}
}
