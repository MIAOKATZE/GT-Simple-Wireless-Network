package com.miaokatze.gtswn.client.engineering;

import java.math.BigDecimal;

/** Per-card presentation clock. Rate changes adjust velocity, never the accumulated angle. */
final class GridDialMotion {

    private static final double RESPONSE = 5;
    private double phase, velocity;
    private long lastNanos;
    private boolean started;

    double advance(long nowNanos, BigDecimal rate, boolean animated, double speed) {
        if (!started) {
            lastNanos = nowNanos;
            started = true;
            return phase;
        }
        long elapsed = nowNanos - lastNanos;
        if (elapsed <= 0) return phase;
        lastNanos = nowNanos;
        // Hidden windows and paused frames do not accumulate a catch-up jump.
        double dt = Math.min(.1, elapsed / 1_000_000_000.0);
        if (!animated || speed == 0) {
            velocity = 0;
            return phase;
        }
        double target = targetSpeed(rate) * speed;
        double decay = Math.exp(-RESPONSE * dt);
        double distance = target * dt + (velocity - target) * (1 - decay) / RESPONSE;
        velocity = target + (velocity - target) * decay;
        if (target == 0 && Math.abs(velocity) < 1e-6) velocity = 0;
        phase += distance;
        phase -= Math.floor(phase);
        return phase;
    }

    double velocity() {
        return velocity;
    }

    double phase() {
        return phase;
    }

    static double targetSpeed(BigDecimal rate) {
        if (rate == null || rate.signum() == 0) return 0;
        double magnitude = rate.abs()
            .doubleValue();
        return Math.min(.25, .04 + .025 * Math.log10(1 + magnitude)) * rate.signum();
    }
}
