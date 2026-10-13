package com.miaokatze.gtswn.client;

/** Pure numeric helpers for the terminal heat view. */
public final class DeviceEnergyVisualMath {

    public static final double MIN_ACTIVE_RADIUS = 1.1D;
    public static final double MAX_ACTIVE_RADIUS = 2.8D;
    public static final int CORE_VERTICES = 24;
    public static final int HALO_VERTICES = 32;
    public static final int HALO_SHELLS = 2;
    // 7680 vertices * 8 ints = 61440: below Forge's default 65536-int raw buffer.
    // Larger batches repeatedly grow the buffer, which native draw() then shrinks again.
    public static final int FILL_VERTEX_BUDGET = 320 * CORE_VERTICES;

    private DeviceEnergyVisualMath() {}

    public static double weighted(double instant, double average, double averageWeight) {
        double weight = Math.max(0D, Math.min(1D, finite(averageWeight)));
        return finite(instant) * (1D - weight) + finite(average) * weight;
    }

    public static double intensity(double magnitude, double maximum) {
        double value = Math.abs(finite(magnitude));
        double max = Math.max(value, Math.abs(finite(maximum)));
        return max <= 0D ? 0D : Math.log1p(value) / Math.log1p(max);
    }

    public static double smooth(double previous, double target, double retention) {
        double weight = Math.max(0D, Math.min(0.95D, finite(retention)));
        return finite(previous) * weight + finite(target) * (1D - weight);
    }

    /** Logarithmic color strength is squared for radius so low and high loads keep distinct sizes. */
    public static double radius(double intensity) {
        double strength = Math.max(0D, Math.min(1D, finite(intensity)));
        return strength <= 0D ? 0D : MIN_ACTIVE_RADIUS + (MAX_ACTIVE_RADIUS - MIN_ACTIVE_RADIUS) * strength * strength;
    }

    public static int vertices(boolean halo) {
        return CORE_VERTICES + (halo ? HALO_SHELLS * HALO_VERTICES : 0);
    }

    public static double interpolateRadius(double previous, double next, double progress) {
        double from = Math.max(0D, Math.min(MAX_ACTIVE_RADIUS, finite(previous)));
        double to = Math.max(0D, Math.min(MAX_ACTIVE_RADIUS, finite(next)));
        double fraction = Math.max(0D, Math.min(1D, finite(progress)));
        return from + (to - from) * fraction;
    }

    /** A layer is kept whole when it fits; batches never exceed the fixed Tessellator buffer. */
    public static boolean startsNewBatch(int existingVertices, int nextVertices) {
        return existingVertices > 0 && existingVertices + nextVertices > FILL_VERTEX_BUDGET;
    }

    /** A halo reaching into the view range stays visible even when its device center is outside it. */
    public static boolean withinRange(double dx, double dy, double dz, double range, double radius) {
        double reach = Math.max(0D, finite(range)) + Math.max(0D, finite(radius));
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    private static double finite(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0D : value;
    }
}
