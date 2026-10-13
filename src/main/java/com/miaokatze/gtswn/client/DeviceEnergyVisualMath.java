package com.miaokatze.gtswn.client;

/** Pure numeric helpers for the terminal heat view. */
public final class DeviceEnergyVisualMath {

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

    private static double finite(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0D : value;
    }
}
