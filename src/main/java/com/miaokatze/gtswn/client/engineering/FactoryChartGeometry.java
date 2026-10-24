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

    /** Half-width follows local time spacing; irregular neighbors cannot paint through each other. */
    static double barHalfWidth(int index, int count, long[] ticks, int width) {
        double x = FactoryTrend.timePosition(index, count, ticks);
        double before = index == 0 ? 1 : x - FactoryTrend.timePosition(index - 1, count, ticks);
        double after = index + 1 == count ? 1 : FactoryTrend.timePosition(index + 1, count, ticks) - x;
        return Math.max(0, Math.min(9, Math.min(before, after) * width * .36));
    }
}
