package com.miaokatze.gtswn.common.panel;

/** Immutable EU colours shared by the screen renderer and the compact portable chart. */
public final class TrendLinePalette {

    public static final TrendLinePalette DEFAULT = new TrendLinePalette(
        true,
        0x1F6FFF,
        0x18BFD3,
        0x7166CC,
        0xFF7A18,
        0x8A9A35,
        0xF06432);
    // Applied to normalized data coordinates, before screen Y inversion.
    public static final double FLAT_EPSILON = 1e-9;

    public final boolean enabled;
    public final int energyFlat, energyUp, energyDown, rateFlat, rateUp, rateDown;

    public TrendLinePalette(boolean enabled, int energyFlat, int energyUp, int energyDown, int rateFlat, int rateUp,
        int rateDown) {
        this.enabled = enabled;
        this.energyFlat = energyFlat;
        this.energyUp = energyUp;
        this.energyDown = energyDown;
        this.rateFlat = rateFlat;
        this.rateUp = rateUp;
        this.rateDown = rateDown;
    }

    public int segmentColor(double previous, double next, boolean rate) {
        int flat = rate ? rateFlat : energyFlat;
        if (!enabled || !Double.isFinite(previous) || !Double.isFinite(next)) return flat;
        if (next > previous + FLAT_EPSILON) return rate ? rateUp : energyUp;
        if (next < previous - FLAT_EPSILON) return rate ? rateDown : energyDown;
        return flat;
    }
}
