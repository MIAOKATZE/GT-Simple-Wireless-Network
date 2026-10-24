package com.miaokatze.gtswn.client.render;

/** Geometry shared by bar drawing and its headless checks; no sampling or GL state. */
public final class NetworkChartMath {

    private NetworkChartMath() {}

    /** Bars always start at actual zero, including when an axis limit was configured. */
    public static double[] includeZero(double[] range) {
        return range == null ? null : new double[] { Math.min(0, range[0]), Math.max(0, range[1]) };
    }

    public static double normalize(double value, double[] range) {
        if (!Double.isFinite(value) || range == null) return Double.NaN;
        double span = range[1] - range[0];
        double numerator = value - range[0];
        if (Double.isFinite(span) && Double.isFinite(numerator)) return numerator / span;
        double scale = Math.max(Math.abs(range[0]), Math.abs(range[1]));
        return (value / scale - range[0] / scale) / (range[1] / scale - range[0] / scale);
    }

    /** Keep both series visible at the first/last sample, aligned with the line's sample X. */
    public static double[] barBounds(int index, int count, int group, int groups) {
        double center = count == 1 ? .5 : index / (double) (count - 1);
        double halfWidth = count == 1 ? .4 : .4 / (count - 1);
        double left = Math.max(0, center - halfWidth), right = Math.min(1, center + halfWidth);
        double slot = (right - left) / groups;
        return new double[] { left + slot * (group + .08), left + slot * (group + .92) };
    }
}
