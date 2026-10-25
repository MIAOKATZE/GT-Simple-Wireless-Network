package com.miaokatze.gtswn.client.engineering;

import java.math.BigDecimal;

/** Presentation only: direction is net charge/discharge; speed is bounded and never implies capacity. */
final class GridDialMotion {

    private GridDialMotion() {}

    static double phase(double seconds, BigDecimal rate) {
        if (rate == null || rate.signum() == 0 || !Double.isFinite(seconds)) return 0;
        double magnitude = rate.abs()
            .doubleValue();
        double speed = Math.min(.25, .04 + .025 * Math.log10(1 + magnitude));
        double phase = seconds * speed * rate.signum();
        return phase - Math.floor(phase);
    }
}
