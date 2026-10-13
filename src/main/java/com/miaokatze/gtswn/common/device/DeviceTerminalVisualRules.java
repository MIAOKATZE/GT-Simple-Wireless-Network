package com.miaokatze.gtswn.common.device;

/** Shared client/server geometry: label marker, bounded distance and a four-degree aiming cone. */
public final class DeviceTerminalVisualRules {

    public static final double MAX_RANGE = 128.0;
    public static final double MIN_ALIGNMENT = Math.cos(Math.toRadians(4.0));
    public static final double MARKER_Y = 1.6;

    private DeviceTerminalVisualRules() {}

    public static double alignment(double eyeX, double eyeY, double eyeZ, double lookX, double lookY, double lookZ,
        double targetX, double targetY, double targetZ) {
        double x = targetX - eyeX, y = targetY - eyeY, z = targetZ - eyeZ;
        double distance = x * x + y * y + z * z;
        double look = lookX * lookX + lookY * lookY + lookZ * lookZ;
        if (!Double.isFinite(distance) || !Double.isFinite(look) || distance <= 0 || look <= 0) return -1;
        return (x * lookX + y * lookY + z * lookZ) / Math.sqrt(distance * look);
    }

    public static boolean targetable(double distanceSquared, double alignment, double range) {
        return Double.isFinite(distanceSquared) && Double.isFinite(alignment)
            && Double.isFinite(range)
            && range > 0
            && distanceSquared > 0
            && distanceSquared <= Math.min(range, MAX_RANGE) * Math.min(range, MAX_RANGE)
            && alignment >= MIN_ALIGNMENT;
    }

    public static boolean isAimedAt(double eyeX, double eyeY, double eyeZ, double lookX, double lookY, double lookZ,
        double targetX, double targetY, double targetZ) {
        double x = targetX - eyeX, y = targetY - eyeY, z = targetZ - eyeZ;
        return targetable(
            x * x + y * y + z * z,
            alignment(eyeX, eyeY, eyeZ, lookX, lookY, lookZ, targetX, targetY, targetZ),
            MAX_RANGE);
    }
}
