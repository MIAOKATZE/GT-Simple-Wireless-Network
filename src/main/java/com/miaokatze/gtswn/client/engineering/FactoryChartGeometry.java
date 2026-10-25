package com.miaokatze.gtswn.client.engineering;

import java.math.BigInteger;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;

/** Deterministic chart geometry. Labels have their own lanes outside the data viewport. */
final class FactoryChartGeometry {

    final Rect plot;
    final int axisX, axisWidth, valueY, metadataY, timeY, footerY;

    FactoryChartGeometry(Rect card) {
        axisX = card.x + 10;
        axisWidth = Math.min(68, Math.max(42, card.w / 8));
        valueY = card.y + 30;
        metadataY = card.y + 53;
        footerY = card.y + card.h - 13;
        timeY = footerY - 15;
        int plotY = card.y + 72;
        plot = new Rect(axisX + axisWidth, plotY, Math.max(1, card.w - axisWidth - 22), Math.max(1, timeY - 7 - plotY));
    }

    /** Real tick spans, expressed as readable game time without overflowing long subtraction. */
    static String age(long newest, long sample) {
        BigInteger ticks = BigInteger.valueOf(newest)
            .subtract(BigInteger.valueOf(sample))
            .max(BigInteger.ZERO);
        if (ticks.signum() == 0) return "0 s";
        java.math.BigDecimal seconds = new java.math.BigDecimal(ticks).divide(java.math.BigDecimal.valueOf(20));
        java.math.BigDecimal divisor = seconds.compareTo(java.math.BigDecimal.valueOf(3600)) >= 0
            ? java.math.BigDecimal.valueOf(3600)
            : seconds.compareTo(java.math.BigDecimal.valueOf(60)) >= 0 ? java.math.BigDecimal.valueOf(60)
                : java.math.BigDecimal.ONE;
        String unit = divisor.intValue() == 3600 ? " h" : divisor.intValue() == 60 ? " min" : " s";
        return "-" + seconds.divide(divisor, 1, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString() + unit;
    }

    static String span(long newest, long sample) {
        String age = age(newest, sample);
        return age.startsWith("-") ? age.substring(1) : age;
    }

    static long middleTick(long first, long last) {
        return BigInteger.valueOf(first)
            .add(BigInteger.valueOf(last))
            .shiftRight(1)
            .longValue();
    }

    static int nearest(long[] ticks, double position) {
        if (ticks.length == 0) return -1;
        int nearest = 0;
        for (int i = 1; i < ticks.length; i++)
            if (Math.abs(FactoryTrend.timePosition(i, ticks.length, ticks) - position)
                < Math.abs(FactoryTrend.timePosition(nearest, ticks.length, ticks) - position)) nearest = i;
        return nearest;
    }

    /** Linear time coordinate with room for complete endpoint bars. Shared by line, bars and hover. */
    static double timeX(double position, int width) {
        double inset = Math.min(9, width * .1);
        return inset + position * Math.max(0, width - 2 * inset);
    }

    static double timePosition(double x, int width) {
        double inset = Math.min(9, width * .1);
        return Math.max(0, Math.min(1, (x - inset) / Math.max(1, width - 2 * inset)));
    }

    /** One width for the entire series, even with irregularly spaced timestamps. */
    static double barHalfWidth(int index, int count, long[] ticks, int width) {
        double gap = 1;
        for (int i = 1; i < count; i++) gap = Math
            .min(gap, FactoryTrend.timePosition(i, count, ticks) - FactoryTrend.timePosition(i - 1, count, ticks));
        return Math.max(0, Math.min(Math.min(9, width * .1), gap * (width - 2 * Math.min(9, width * .1)) * .38));
    }

    /** Dense data uses equal real-time bins. Each occupied bin preserves the observed min/max envelope. */
    static final class Bars {

        final double[] x, low, high;
        final int[] counts;
        final double halfWidth;
        final boolean aggregated;

        Bars(double[] values, long[] ticks, int width) {
            double half = barHalfWidth(0, values.length, ticks, width);
            aggregated = half < .85 && values.length > 1;
            int bins = aggregated ? Math.max(1, (int) ((timeX(1, width) - timeX(0, width)) / 3)) : values.length;
            x = new double[bins];
            low = new double[bins];
            high = new double[bins];
            counts = new int[bins];
            boolean[] missing = new boolean[bins];
            java.util.Arrays.fill(low, Double.NaN);
            java.util.Arrays.fill(high, Double.NaN);
            double pitch = (timeX(1, width) - timeX(0, width)) / Math.max(1, bins);
            halfWidth = aggregated ? pitch * .38 : half;
            for (int i = 0; i < bins; i++) x[i] = aggregated ? timeX(0, width) + (i + .5) * pitch
                : timeX(FactoryTrend.timePosition(i, values.length, ticks), width);
            for (int i = 0; i < values.length; i++) {
                int bin = aggregated
                    ? Math.min(bins - 1, (int) (FactoryTrend.timePosition(i, values.length, ticks) * bins))
                    : i;
                if (!Double.isFinite(values[i])) {
                    missing[bin] = true;
                    continue;
                }
                low[bin] = counts[bin] == 0 ? values[i] : Math.min(low[bin], values[i]);
                high[bin] = counts[bin] == 0 ? values[i] : Math.max(high[bin], values[i]);
                counts[bin]++;
            }
            // A bin containing missing evidence remains a gap; it never bridges a missing sample.
            for (int i = 0; i < bins; i++) if (missing[i]) low[i] = high[i] = Double.NaN;
        }
    }

    static String axisLabel(java.math.BigDecimal value, java.math.BigDecimal range,
        com.miaokatze.gtswn.client.engineering.desktop.WidgetNumberFormat format) {
        int exponent = 0;
        if (value.signum() != 0) {
            int order = value.precision() - value.scale() - 1;
            if (format == com.miaokatze.gtswn.client.engineering.desktop.WidgetNumberFormat.SCIENTIFIC)
                exponent = order;
            else if (format == com.miaokatze.gtswn.client.engineering.desktop.WidgetNumberFormat.METRIC)
                exponent = Math.min(15, Math.max(0, order / 3 * 3));
        }
        java.math.BigDecimal step = range.abs()
            .movePointLeft(exponent)
            .stripTrailingZeros();
        int decimals = step.signum() == 0 ? 1 : Math.max(1, 1 - (step.precision() - step.scale() - 1));
        if (decimals > 12)
            return com.miaokatze.gtswn.client.engineering.desktop.WidgetNumberFormat.NORMAL.format(value);
        return format.format(value, decimals);
    }

    /** Pick precision from the visible range, so nearby large values get distinct labels. */
    static String axisLabel(java.math.BigDecimal value, java.math.BigDecimal range) {
        java.math.BigDecimal magnitude = value.abs()
            .max(range.abs());
        String[] units = { "", "k", "M", "G", "T", "P", "E" };
        int group = 0;
        java.math.BigDecimal scale = java.math.BigDecimal.ONE;
        while (group < units.length - 1
            && magnitude.compareTo(scale.multiply(java.math.BigDecimal.valueOf(1000))) >= 0) {
            group++;
            scale = scale.multiply(java.math.BigDecimal.valueOf(1000));
        }
        java.math.BigDecimal step = range.abs()
            .divide(scale, java.math.MathContext.DECIMAL64);
        int decimals = step.signum() == 0 ? 0 : Math.max(0, 1 - (step.precision() - step.scale() - 1));
        return value.divide(scale, decimals, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString() + units[group];
    }
}
