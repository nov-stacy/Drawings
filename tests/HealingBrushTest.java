package com.example.drawingsarchive;

import java.util.Arrays;
import java.util.concurrent.CancellationException;

public final class HealingBrushTest {
    public static void main(String[] args) {
        int w = 101, h = 81;
        int[] paper = new int[w * h];
        Arrays.fill(paper, 0xffe9dfca);
        boolean[] mask = new boolean[paper.length];
        for (int y = 30; y <= 50; y++) for (int x = 40; x <= 60; x++) {
            int i = y * w + x; paper[i] = 0xff181818; mask[i] = true;
        }
        int[] before = paper.clone();
        check(HealingBrush.fill(paper, w, h, mask), "Repairs a selected defect");
        for (int i = 0; i < paper.length; i++) check(paper[i] == (mask[i] ? 0xffe9dfca : before[i]), "Flat paper fill and unmasked pixels");

        // Harmonic fill should continue a linear lighting gradient through a dark mark.
        int[] gradient = new int[w * h], expected = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int r = 90 + x, g = 100 + y, b = 130 + x / 2;
            int i = y * w + x; expected[i] = 0xb3000000 | (r << 16) | (g << 8) | b;
            gradient[i] = mask[i] ? 0xb3000000 : expected[i];
        }
        HealingBrush.fill(gradient, w, h, mask);
        for (int i = 0; i < gradient.length; i++) {
            check((gradient[i] >>> 24) == 0xb3, "Alpha stays intact");
            if (!mask[i]) check(gradient[i] == expected[i], "Pixels outside the stroke stay exact");
            for (int shift : new int[]{0, 8, 16}) check(Math.abs(((gradient[i] >> shift) & 255) - ((expected[i] >> shift) & 255)) <= 2, "Lighting gradient is reconstructed");
        }
        Arrays.fill(mask, false);
        check(!HealingBrush.fill(paper, w, h, mask), "Empty mask is a no-op");
        Arrays.fill(mask, true);
        check(!HealingBrush.fill(paper, w, h, mask), "A missing surrounding region is rejected");
        Arrays.fill(mask, false);
        // A stroke may touch an image edge or include multiple disconnected spots.
        for (int y = 0; y < 8; y++) for (int x = 0; x < 10; x++) mask[y * w + x] = true;
        mask[70 * w + 90] = true;
        Arrays.fill(paper, 0xffcbbbaa);
        for (int i = 0; i < paper.length; i++) if (mask[i]) paper[i] = 0xff000000;
        HealingBrush.fill(paper, w, h, mask);
        for (int pixel : paper) check(pixel == 0xffcbbbaa, "Edge and disconnected defects are filled");
        Thread.currentThread().interrupt();
        try { HealingBrush.fill(paper, w, h, mask); throw new AssertionError("Cancellation expected"); }
        catch (CancellationException expectedCancellation) { Thread.interrupted(); }
        System.out.println("HealingBrush: flat paper, gradients, alpha, mask boundaries, image edges, disconnected spots and cancellation passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
