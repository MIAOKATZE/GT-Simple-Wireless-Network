package com.miaokatze.gtswn.client;

import com.miaokatze.gtswn.common.device.DeviceTerminalVisualRules;

/** Pure client target ordering; aim and range are shared with the server. */
public final class DeviceTerminalAnchorMath {

    public static final int MAX_RANGE = (int) DeviceTerminalVisualRules.MAX_RANGE;
    public static final double MIN_ALIGNMENT = DeviceTerminalVisualRules.MIN_ALIGNMENT;

    private DeviceTerminalAnchorMath() {}

    public static double distanceSquared(double x, double y, double z, double ex, double ey, double ez) {
        double dx = x - ex;
        double dy = y - ey;
        double dz = z - ez;
        double value = dx * dx + dy * dy + dz * dz;
        return Double.isNaN(value) ? Double.POSITIVE_INFINITY : value;
    }

    public static double alignment(double tx, double ty, double tz, double ex, double ey, double ez, double lx,
        double ly, double lz) {
        return DeviceTerminalVisualRules.alignment(ex, ey, ez, lx, ly, lz, tx, ty, tz);
    }

    public static boolean targetable(double squaredDistance, double alignment, int range) {
        return DeviceTerminalVisualRules.targetable(squaredDistance, alignment, range);
    }

    public static boolean betterTarget(double alignment, double distance, String key, double previousAlignment,
        double previousDistance, String previousKey) {
        if (!Double.isFinite(alignment) || !Double.isFinite(distance) || distance <= 0D || key == null) return false;
        if (previousKey == null) return true;
        double difference = alignment - previousAlignment;
        if (Math.abs(difference) > 1E-10D) return difference > 0D;
        int order = Double.compare(distance, previousDistance);
        return order < 0 || order == 0 && key.compareTo(previousKey) < 0;
    }
}
