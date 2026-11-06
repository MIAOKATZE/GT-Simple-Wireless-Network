package com.miaokatze.gtswn.client;

/** Shared drawing and hit-test geometry. Sector zero points up; indices increase clockwise. */
public final class TerminalWheelGeometry {

    public static final int SIZE = 8;
    public static final double STEP = Math.PI / 4;
    public static final double GAP = 0.025;
    public static final int MAIN = 0;
    public static final int OUTER_FIRST = 1;
    public static final int OUTER_SECOND = 2;
    public static final int INNER = 3;
    public static final double OUTER_RADIUS = 136;
    private static final double SCALE = OUTER_RADIUS / 331;
    // The approved 32-unit auxiliary bands are 8% thicker, within the same outer contour.
    private static final double[] INNER_RADII = { 166.56 * SCALE, 296.44 * SCALE, 296.44 * SCALE, 128 * SCALE };
    private static final double[] OUTER_RADII = { 292.44 * SCALE, OUTER_RADIUS, OUTER_RADIUS, 162.56 * SCALE };
    private static final double HALF_GAP = Math.toRadians(0.6);

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

    public static double inner(int zone) {
        return INNER_RADII[zone];
    }

    public static double outer(int zone) {
        return OUTER_RADII[zone];
    }

    public static double start(int index, int zone) {
        return zone == OUTER_SECOND ? angle(index) + HALF_GAP : angle(index) - STEP / 2 + GAP;
    }

    public static double end(int index, int zone) {
        return zone == OUTER_FIRST ? angle(index) - HALF_GAP : angle(index) + STEP / 2 - GAP;
    }

    public static double centerAngle(int index, int zone) {
        return (start(index, zone) + end(index, zone)) / 2;
    }

    public static double centerRadius(int zone) {
        return (inner(zone) + outer(zone)) / 2;
    }

    /** The same angular and radial boundaries are used by drawing and all 32 MUI2 widgets. */
    public static boolean contains(int index, int zone, double x, double y) {
        return contains(index, zone, x, y, inner(zone), outer(zone));
    }

    public static boolean contains(int index, int zone, double x, double y, double innerRadius, double outerRadius) {
        if (sector(x, y, innerRadius, outerRadius) != index) return false;
        double offset = Math.atan2(y, x) - angle(index);
        offset = Math.atan2(Math.sin(offset), Math.cos(offset));
        return (zone != OUTER_FIRST || offset <= -HALF_GAP) && (zone != OUTER_SECOND || offset >= HALF_GAP);
    }
}
