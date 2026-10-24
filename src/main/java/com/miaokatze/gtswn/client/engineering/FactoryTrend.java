package com.miaokatze.gtswn.client.engineering;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/** Plot geometry derives from real values without converting huge EU stocks to double. */
final class FactoryTrend {

    final double[] normalized;
    final String minimum, maximum;
    final double zero;
    final int knownPoints;

    FactoryTrend(BigInteger[] values) {
        this(decimals(values));
    }

    FactoryTrend(BigDecimal[] values) {
        BigDecimal min = BigDecimal.ZERO, max = BigDecimal.ZERO;
        int known = 0;
        for (BigDecimal value : values) if (value != null) {
            min = min.min(value);
            max = max.max(value);
            known++;
        }
        knownPoints = known;
        minimum = min.toPlainString();
        maximum = max.toPlainString();
        normalized = new double[values.length];
        BigDecimal span = max.subtract(min);
        zero = position(BigDecimal.ZERO, min, span);
        for (int i = 0; i < values.length; i++)
            normalized[i] = values[i] == null ? Double.NaN : position(values[i], min, span);
    }

    private static BigDecimal[] decimals(BigInteger[] values) {
        BigDecimal[] result = new BigDecimal[values.length];
        for (int i = 0; i < values.length; i++) if (values[i] != null) result[i] = new BigDecimal(values[i]);
        return result;
    }

    private static double position(BigDecimal value, BigDecimal min, BigDecimal span) {
        return span.signum() == 0 ? .5
            : value.subtract(min)
                .divide(span, MathContext.DECIMAL64)
                .doubleValue();
    }

    /** Irregular sample intervals retain their real positions; a lone observation sits at now. */
    static double timePosition(int index, int count, long[] ticks) {
        if (count <= 1) return 1;
        if (ticks != null && ticks.length == count && ticks[count - 1] > ticks[0]) {
            long span = ticks[count - 1] - ticks[0], elapsed = ticks[index] - ticks[0];
            if (span > 0 && elapsed >= 0) return elapsed / (double) span;
            return BigDecimal.valueOf(ticks[index])
                .subtract(BigDecimal.valueOf(ticks[0]))
                .divide(
                    BigDecimal.valueOf(ticks[count - 1])
                        .subtract(BigDecimal.valueOf(ticks[0])),
                    MathContext.DECIMAL64)
                .doubleValue();
        }
        return index / (double) (count - 1);
    }
}
