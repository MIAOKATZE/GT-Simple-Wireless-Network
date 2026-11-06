package com.miaokatze.gtswn.client;

/** Pixel layout in the actual MUI viewport; text and icons are never scaled with the wheel. */
public final class TerminalWheelLayout {

    public final int width, height, centerX, centerY, radius, helpTop = 4, controlsTop = 38;
    public final int mainIconSize;
    private final double mainInner, mainOuter;

    public TerminalWheelLayout(int width, int height, boolean engineering) {
        this.width = width;
        this.height = height;
        int top = engineering ? 64 : 38;
        radius = Math.max(1, Math.min(176, Math.min((width - 12) / 2, (height - 34 - top) / 2)));
        centerX = width / 2;
        centerY = top + radius;
        mainIconSize = radius >= 120 ? 20 : 16;
        mainOuter = Math.max(2, radius - 16);
        mainInner = Math.min(mainOuter - 4, Math.max(28, radius - 94));
    }

    public double inner(int zone) {
        if (zone == TerminalWheelGeometry.MAIN) return mainInner;
        if (zone == TerminalWheelGeometry.INNER) return Math.max(2, mainInner - 18);
        return Math.max(2, radius - 14.2);
    }

    public double outer(int zone) {
        if (zone == TerminalWheelGeometry.MAIN) return mainOuter;
        if (zone == TerminalWheelGeometry.INNER) return Math.max(3, mainInner - 4);
        return radius;
    }

    public double centerRadius(int zone) {
        return (inner(zone) + outer(zone)) / 2;
    }

    public double centerDiscRadius() {
        return Math.max(0, inner(TerminalWheelGeometry.INNER) - 4);
    }

    public boolean contains(int index, int zone, double x, double y) {
        return TerminalWheelGeometry.contains(index, zone, x - centerX, y - centerY, inner(zone), outer(zone));
    }

    /** Find an unscaled icon/name card wholly inside A, including the inner circular boundary. */
    public Bounds content(int index, int width, int height) {
        double angle = TerminalWheelGeometry.angle(index);
        double preferred = (mainInner + mainOuter) / 2;
        for (double distance = 0; distance <= mainOuter - mainInner; distance += 0.5) {
            for (int direction : new int[] { 1, -1 }) {
                double radius = preferred + distance * direction;
                Bounds bounds = new Bounds(
                    (int) Math.round(centerX + Math.cos(angle) * radius - width / 2.0),
                    (int) Math.round(centerY + Math.sin(angle) * radius - height / 2.0),
                    width,
                    height);
                if (fits(index, bounds)) return bounds;
            }
        }
        return null;
    }

    public boolean fits(int index, Bounds bounds) {
        double left = bounds.x - centerX, top = bounds.y - centerY;
        double right = left + bounds.width, bottom = top + bounds.height;
        for (double x : new double[] { left, right }) for (double y : new double[] { top, bottom })
            if (!TerminalWheelGeometry.contains(index, TerminalWheelGeometry.MAIN, x, y, mainInner + 1, mainOuter - 1))
                return false;
        double nearestX = Math.max(left, Math.min(0, right));
        double nearestY = Math.max(top, Math.min(0, bottom));
        return Math.hypot(nearestX, nearestY) >= mainInner + 1;
    }

    public static final class Bounds {

        public final int x, y, width, height;

        public Bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
}
