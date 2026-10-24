package com.example.drawingsarchive;

import java.util.concurrent.CancellationException;

/** Local inpainting for small marks on paper. Only masked pixels are changed. */
final class HealingBrush {
    static boolean fill(int[] pixels, int width, int height, boolean[] mask) {
        if (width <= 0 || height <= 0 || pixels.length != width * height || mask.length != pixels.length) {
            throw new IllegalArgumentException("Invalid patch");
        }
        int count = 0;
        for (boolean selected : mask) if (selected) count++;
        if (count == 0 || count == pixels.length) return false;
        float[] red = new float[pixels.length], green = new float[pixels.length], blue = new float[pixels.length];
        byte[] state = new byte[pixels.length]; // 0 known, 1 waiting, 2 queued, 3 filled.
        for (int i = 0; i < pixels.length; i++) {
            red[i] = (pixels[i] >> 16) & 255;
            green[i] = (pixels[i] >> 8) & 255;
            blue[i] = pixels[i] & 255;
            if (mask[i]) state[i] = 1;
        }
        int[] queue = new int[count];
        int tail = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (state[i] != 1) continue;
            int x = i % width, y = i / width;
            if ((x > 0 && state[i - 1] == 0) || (x + 1 < width && state[i + 1] == 0)
                    || (y > 0 && state[i - width] == 0) || (y + 1 < height && state[i + width] == 0)) {
                state[i] = 2;
                queue[tail++] = i;
            }
        }
        // Grow inward from the surrounding paper, never sampling the marked defect.
        for (int head = 0; head < tail; head++) {
            if ((head & 1023) == 0) checkCancelled();
            int i = queue[head], x = i % width, y = i / width;
            float r = 0, g = 0, b = 0, weights = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                if ((dx == 0 && dy == 0) || x + dx < 0 || x + dx >= width || y + dy < 0 || y + dy >= height) continue;
                int j = i + dy * width + dx;
                if (state[j] == 1 || state[j] == 2) continue;
                float weight = dx == 0 || dy == 0 ? 1 : .7071f;
                r += red[j] * weight; g += green[j] * weight; b += blue[j] * weight; weights += weight;
            }
            if (weights == 0) throw new IllegalStateException("Missing patch boundary");
            red[i] = r / weights; green[i] = g / weights; blue[i] = b / weights;
            state[i] = 3;
            if (x > 0 && state[i - 1] == 1) { state[i - 1] = 2; queue[tail++] = i - 1; }
            if (x + 1 < width && state[i + 1] == 1) { state[i + 1] = 2; queue[tail++] = i + 1; }
            if (y > 0 && state[i - width] == 1) { state[i - width] = 2; queue[tail++] = i - width; }
            if (y + 1 < height && state[i + width] == 1) { state[i + width] = 2; queue[tail++] = i + width; }
        }
        // Relax the fill so gradual lighting changes continue across the repaired area.
        for (int pass = 0; pass < 160; pass++) {
            checkCancelled();
            float change = 0;
            for (int k = 0; k < count; k++) {
                int i = queue[(pass & 1) == 0 ? k : count - 1 - k], x = i % width, y = i / width;
                float r = 0, g = 0, b = 0;
                int n = 0;
                if (x > 0) { r += red[i - 1]; g += green[i - 1]; b += blue[i - 1]; n++; }
                if (x + 1 < width) { r += red[i + 1]; g += green[i + 1]; b += blue[i + 1]; n++; }
                if (y > 0) { r += red[i - width]; g += green[i - width]; b += blue[i - width]; n++; }
                if (y + 1 < height) { r += red[i + width]; g += green[i + width]; b += blue[i + width]; n++; }
                float dr = (r / n - red[i]) * 1.6f, dg = (g / n - green[i]) * 1.6f, db = (b / n - blue[i]) * 1.6f;
                red[i] += dr; green[i] += dg; blue[i] += db;
                change = Math.max(change, Math.max(Math.abs(dr), Math.max(Math.abs(dg), Math.abs(db))));
            }
            if (change < .04f) break;
        }
        for (int i : queue) pixels[i] = (pixels[i] & 0xff000000) | (channel(red[i]) << 16) | (channel(green[i]) << 8) | channel(blue[i]);
        return true;
    }

    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value))); }
    private static void checkCancelled() { if (Thread.currentThread().isInterrupted()) throw new CancellationException(); }
    private HealingBrush() {}
}
