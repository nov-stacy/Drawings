package com.example.drawingsarchive;

/** Crop coordinates are normalized in the image after its quarter-turn rotation. */
final class CropGeometry {
    static final int FREE = 0;
    static final int CORNERS = 1;
    int mode = FREE;
    int rotation;
    float aspect;
    final float[] points = {0, 0, 1, 0, 1, 1, 0, 1};

    CropGeometry copy() {
        CropGeometry copy = new CropGeometry();
        copy.mode = mode;
        copy.rotation = rotation;
        copy.aspect = aspect;
        System.arraycopy(points, 0, copy.points, 0, points.length);
        return copy;
    }

    void setMode(int value) {
        if (mode == value) return;
        if (value == FREE) {
            float left = 1, top = 1, right = 0, bottom = 0;
            for (int i = 0; i < 8; i += 2) {
                left = Math.min(left, points[i]);
                top = Math.min(top, points[i + 1]);
                right = Math.max(right, points[i]);
                bottom = Math.max(bottom, points[i + 1]);
            }
            rectangle(left, top, right, bottom);
            aspect = 0;
        }
        mode = value;
    }

    void setAspect(float value, float imageAspect) {
        aspect = value;
        if (mode != FREE || aspect == 0) return;
        float left = points[0], top = points[1], w = points[4] - left, h = points[5] - top;
        float normalizedRatio = aspect / imageAspect;
        float newW = Math.min(w, h * normalizedRatio);
        float newH = newW / normalizedRatio;
        rectangle(left + (w - newW) / 2, top + (h - newH) / 2,
                left + (w + newW) / 2, top + (h + newH) / 2);
    }

    void rotateClockwise() {
        float[] before = points.clone();
        int[] order = {3, 0, 1, 2};
        for (int i = 0; i < 4; i++) {
            points[i * 2] = 1 - before[order[i] * 2 + 1];
            points[i * 2 + 1] = before[order[i] * 2];
        }
        rotation = (rotation + 90) % 360;
        if (aspect > 0) aspect = 1 / aspect;
    }

    void moveRectangle(float dx, float dy) {
        float left = points[0], top = points[1], right = points[4], bottom = points[5];
        dx = clamp(dx, -left, 1 - right);
        dy = clamp(dy, -top, 1 - bottom);
        rectangle(left + dx, top + dy, right + dx, bottom + dy);
    }

    // Handles run clockwise: NW, N, NE, E, SE, S, SW, W.
    void moveHandle(int handle, float x, float y, float imageAspect, float minW, float minH) {
        x = clamp(x, 0, 1);
        y = clamp(y, 0, 1);
        if (mode == CORNERS) {
            if (handle % 2 != 0) return;
            float[] candidate = points.clone();
            candidate[handle] = x;
            candidate[handle + 1] = y;
            if (validQuad(candidate)) System.arraycopy(candidate, 0, points, 0, 8);
            return;
        }
        float left = points[0], top = points[1], right = points[4], bottom = points[5];
        minW = Math.min(minW, right - left);
        minH = Math.min(minH, bottom - top);
        boolean west = handle == 0 || handle == 6 || handle == 7;
        boolean east = handle == 2 || handle == 3 || handle == 4;
        boolean north = handle == 0 || handle == 1 || handle == 2;
        boolean south = handle == 4 || handle == 5 || handle == 6;
        if (aspect > 0) {
            if (handle % 2 != 0) return;
            float anchorX = west ? right : left, anchorY = north ? bottom : top;
            float ratio = aspect / imageAspect;
            float maxW = west ? anchorX : 1 - anchorX;
            float maxH = north ? anchorY : 1 - anchorY;
            float w = Math.max(Math.abs(x - anchorX), Math.abs(y - anchorY) * ratio);
            w = Math.min(Math.max(w, Math.max(minW, minH * ratio)), Math.min(maxW, maxH * ratio));
            float h = w / ratio;
            rectangle(west ? anchorX - w : anchorX, north ? anchorY - h : anchorY,
                    west ? anchorX : anchorX + w, north ? anchorY : anchorY + h);
        } else {
            if (west) left = clamp(x, 0, right - minW);
            if (east) right = clamp(x, left + minW, 1);
            if (north) top = clamp(y, 0, bottom - minH);
            if (south) bottom = clamp(y, top + minH, 1);
            rectangle(left, top, right, bottom);
        }
    }

    float[] handlePoint(int handle) {
        if (handle % 2 == 0) return new float[]{points[handle], points[handle + 1]};
        int a = handle - 1, b = (handle + 1) % 8;
        return new float[]{(points[a] + points[b]) / 2, (points[a + 1] + points[b + 1]) / 2};
    }

    boolean handleVisible(int handle) {
        return handle % 2 == 0 || (mode == FREE && aspect == 0);
    }

    int[] dimensions(int sourceWidth, int sourceHeight) {
        int w = rotation % 180 == 0 ? sourceWidth : sourceHeight;
        int h = rotation % 180 == 0 ? sourceHeight : sourceWidth;
        float width = (distance(0, 2, w, h) + distance(6, 4, w, h)) / 2;
        float height = (distance(0, 6, w, h) + distance(2, 4, w, h)) / 2;
        if (aspect > 0) {
            width = Math.min(width, height * aspect);
            height = width / aspect;
        }
        return new int[]{Math.max(1, Math.round(width)), Math.max(1, Math.round(height))};
    }

    float imageAspect(int width, int height) {
        return rotation % 180 == 0 ? width / (float) height : height / (float) width;
    }

    boolean isFullImage() {
        float[] full = {0, 0, 1, 0, 1, 1, 0, 1};
        if (aspect != 0) return false;
        for (int i = 0; i < 8; i++) if (Math.abs(points[i] - full[i]) > .00001f) return false;
        return true;
    }

    static boolean validQuad(float[] p) {
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = (i + 1) % 4 * 2, c = (i + 2) % 4 * 2;
            if (!Float.isFinite(p[a]) || !Float.isFinite(p[a + 1])
                    || p[a] < 0 || p[a] > 1 || p[a + 1] < 0 || p[a + 1] > 1) return false;
            float cross = (p[b] - p[a]) * (p[c + 1] - p[b + 1])
                    - (p[b + 1] - p[a + 1]) * (p[c] - p[b]);
            if (cross < .001f || Math.hypot(p[a] - p[b], p[a + 1] - p[b + 1]) < .035) return false;
        }
        return true;
    }

    private float distance(int a, int b, int width, int height) {
        return (float) Math.hypot((points[a] - points[b]) * width, (points[a + 1] - points[b + 1]) * height);
    }

    private void rectangle(float left, float top, float right, float bottom) {
        points[0] = left; points[1] = top;
        points[2] = right; points[3] = top;
        points[4] = right; points[5] = bottom;
        points[6] = left; points[7] = bottom;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
