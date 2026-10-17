package com.miaokatze.gtswn.client;

/** Shared drawing and hit-test geometry. Sector zero points up; indices increase clockwise. */
public final class TerminalWheelGeometry {

    public static final int SIZE = 8;
    public static final double STEP = Math.PI / 4;
    public static final double GAP = 0.025;

    private TerminalWheelGeometry() {}

    public static double angle(int index) {
        return -Math.PI / 2 + index * STEP;
    }

    public static int sector(double x, double y, double innerRadius, double outerRadius) {
        double radius = Math.hypot(x, y);
        if (radius < innerRadius || radius > outerRadius) return -1;
        double angle = Math.atan2(y, x) + Math.PI / 2;
        double shifted = (angle + STEP / 2 + Math.PI * 2) % (Math.PI * 2);
        int index = (int) (shifted / STEP);
        double offset = shifted - index * STEP;
        return offset < GAP || offset > STEP - GAP ? -1 : index;
    }

    public static int next(int selected) {
        return (selected + 1) % SIZE;
    }
}
