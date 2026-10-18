package com.example.drawingsarchive;

public final class CropGeometryTest {
    public static void main(String[] args) {
        CropGeometry crop = new CropGeometry();
        check(crop.isFullImage(), "Full source is the default");
        crop.moveHandle(0, .2f, .1f, 2, .04f, .04f);
        crop.moveHandle(4, .8f, .9f, 2, .04f, .04f);
        int[] size = crop.dimensions(1000, 500);
        check(size[0] == 600 && size[1] == 400, "Off-center free crop dimensions");
        crop.moveRectangle(-2, 2);
        near(crop.points[0], 0); near(crop.points[5], 1);
        crop.setAspect(1, 2);
        size = crop.dimensions(1000, 500);
        check(size[0] == size[1], "Square in a non-square image");
        crop.moveHandle(0, .1f, .1f, 2, .04f, .04f);
        size = crop.dimensions(1000, 500);
        check(size[0] == size[1], "Dragging keeps locked aspect");
        CropGeometry before = crop.copy();
        for (int i = 0; i < 4; i++) crop.rotateClockwise();
        for (int i = 0; i < 8; i++) near(crop.points[i], before.points[i]);
        check(crop.rotation == 0, "Four turns restore rotation");
        crop = new CropGeometry(); crop.setMode(CropGeometry.CORNERS);
        crop.moveHandle(0, .2f, .1f, 2, .04f, .04f);
        check(crop.points[2] == 1 && crop.points[7] == 1, "Corners move independently");
        before = crop.copy(); crop.moveHandle(0, .9f, .9f, 2, .04f, .04f);
        for (int i = 0; i < 8; i++) near(crop.points[i], before.points[i]);
        check(CropGeometry.validQuad(crop.points), "Crossed quad is rejected");
        crop.setAspect(.8f, 2); size = crop.dimensions(1000, 500);
        check(Math.abs(size[0] / (float) size[1] - .8f) < .003, "Perspective output aspect");
        crop.setMode(CropGeometry.FREE);
        check(crop.aspect == 0 && crop.points[0] == 0, "Returning to free uses quad bounds");
        crop = before.copy(); crop.rotateClockwise(); size = crop.dimensions(1000, 500);
        int[] oldSize = before.dimensions(1000, 500);
        check(size[0] == oldSize[1] && size[1] == oldSize[0], "Rotated crop swaps dimensions");
        float original = before.points[0]; crop.points[0] = .4f;
        check(before.points[0] == original, "Draft copies are independent");
        check(!CropGeometry.validQuad(new float[]{Float.NaN, 0, 1, 0, 1, 1, 0, 1}), "Nonfinite quad rejected");
        System.out.println("CropGeometry: all checks passed");
    }
    private static void near(float actual, float expected) { check(Math.abs(actual - expected) < .0001f, actual + " != " + expected); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
