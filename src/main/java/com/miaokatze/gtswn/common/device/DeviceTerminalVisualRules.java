package com.miaokatze.gtswn.common.device;

/** Shared client/server geometry: label marker, bounded distance and a four-degree aiming cone. */
public final class DeviceTerminalVisualRules {

    public static final double MAX_RANGE = 128.0;
    public static final double MIN_ALIGNMENT = Math.cos(Math.toRadians(4.0));
    public static final double MARKER_Y = 1.6;

    private DeviceTerminalVisualRules() {}

    /** Forge 1.7 clients use stance posY/defaultEye=.12, while MP uses feet posY/defaultEye=1.62. */
    public static double eyeY(double posY, double yOffset, double eyeHeight, double defaultEyeHeight) {
        return posY - yOffset + 1.62D + (eyeHeight - defaultEyeHeight);
    }

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

    /**
     * Travel accepts the marker cone or a ray through the bound machine's unit block, using the same eye on both ends.
     */
    public static boolean isAimedAtMachine(double eyeX, double eyeY, double eyeZ, double lookX, double lookY,
        double lookZ, int blockX, int blockY, int blockZ) {
        double dx = blockX + 0.5D - eyeX, dy = blockY + MARKER_Y - eyeY, dz = blockZ + 0.5D - eyeZ;
        double distance = dx * dx + dy * dy + dz * dz;
        if (!Double.isFinite(distance) || distance <= 0D || distance > MAX_RANGE * MAX_RANGE) return false;
        return isAimedAt(eyeX, eyeY, eyeZ, lookX, lookY, lookZ, blockX + 0.5D, blockY + MARKER_Y, blockZ + 0.5D)
            || isAimedAtMachineBody(eyeX, eyeY, eyeZ, lookX, lookY, lookZ, blockX, blockY, blockZ);
    }

    /** Allocation-free ray/slab test; the 0.05-block margin only tolerates native look/position packet ordering. */
    public static boolean isAimedAtMachineBody(double eyeX, double eyeY, double eyeZ, double lookX, double lookY,
        double lookZ, int blockX, int blockY, int blockZ) {
        double length = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        if (!Double.isFinite(eyeX) || !Double.isFinite(eyeY)
            || !Double.isFinite(eyeZ)
            || !Double.isFinite(length)
            || length <= 0D) return false;
        double dx = lookX / length, dy = lookY / length, dz = lookZ / length;
        double minX = blockX - 0.05D, maxX = blockX + 1.05D;
        double minY = blockY - 0.05D, maxY = blockY + 1.05D;
        double minZ = blockZ - 0.05D, maxZ = blockZ + 1.05D;
        boolean parallelX = Math.abs(dx) < 1E-12D, parallelY = Math.abs(dy) < 1E-12D, parallelZ = Math.abs(dz) < 1E-12D;
        if ((parallelX && (eyeX < minX || eyeX > maxX)) || (parallelY && (eyeY < minY || eyeY > maxY))
            || (parallelZ && (eyeZ < minZ || eyeZ > maxZ))) return false;
        double nearX = parallelX ? Double.NEGATIVE_INFINITY : Math.min((minX - eyeX) / dx, (maxX - eyeX) / dx);
        double farX = parallelX ? Double.POSITIVE_INFINITY : Math.max((minX - eyeX) / dx, (maxX - eyeX) / dx);
        double nearY = parallelY ? Double.NEGATIVE_INFINITY : Math.min((minY - eyeY) / dy, (maxY - eyeY) / dy);
        double farY = parallelY ? Double.POSITIVE_INFINITY : Math.max((minY - eyeY) / dy, (maxY - eyeY) / dy);
        double nearZ = parallelZ ? Double.NEGATIVE_INFINITY : Math.min((minZ - eyeZ) / dz, (maxZ - eyeZ) / dz);
        double farZ = parallelZ ? Double.POSITIVE_INFINITY : Math.max((minZ - eyeZ) / dz, (maxZ - eyeZ) / dz);
        double near = Math.max(0D, Math.max(nearX, Math.max(nearY, nearZ)));
        double far = Math.min(MAX_RANGE, Math.min(farX, Math.min(farY, farZ)));
        return near <= far;
    }
}
