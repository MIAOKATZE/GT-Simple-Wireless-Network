package com.miaokatze.gtswn.client.engineering;

/** Engineering navigation policy. The projection and keyboard motion share the actual zoom. */
public final class EngineeringMotion {

    private EngineeringMotion() {}

    public static double[] delta(double yaw, double zoom, double forward, double right, double up) {
        return delta(yaw, zoom, forward, right, up, 1);
    }

    public static double[] delta(double yaw, double zoom, double forward, double right, double up, double sensitivity) {
        if (!Double.isFinite(yaw) || !Double.isFinite(zoom)
            || zoom <= 0
            || !Double.isFinite(forward)
            || !Double.isFinite(right)
            || !Double.isFinite(up)
            || !Double.isFinite(sensitivity)
            || sensitivity <= 0) return new double[] { 0, 0, 0 };
        return heading(yaw, forward, right, up, .75 * sensitivity / zoom);
    }

    /** Minecraft forward uses +Z at yaw zero; camera forward uses -Z. */
    static float playerYaw(double cameraYaw) {
        return (float) ((cameraYaw + 180) % 360);
    }

    /** Player walking speed is world speed; zoom never enters this contract. */
    public static double[] playerDelta(double yaw, double forward, double right, double walkingSpeed) {
        if (!Double.isFinite(yaw) || !Double.isFinite(forward)
            || !Double.isFinite(right)
            || !Double.isFinite(walkingSpeed)
            || walkingSpeed < 0) return new double[] { 0, 0, 0 };
        return heading(yaw, forward, right, 0, walkingSpeed);
    }

    private static double[] heading(double yaw, double forward, double right, double up, double speed) {
        speed /= Math.max(1, Math.sqrt(forward * forward + right * right + up * up));
        double radians = Math.toRadians(yaw);
        return new double[] { (forward * Math.sin(radians) + right * Math.cos(radians)) * speed, up * speed,
            (right * Math.sin(radians) - forward * Math.cos(radians)) * speed };
    }
}
