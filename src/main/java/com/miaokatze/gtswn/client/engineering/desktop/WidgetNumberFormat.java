package com.miaokatze.gtswn.client.engineering.desktop;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.miaokatze.gtswn.common.util.FormatUtil;

/** Per-component notation. Decimal arithmetic retains exact wireless stock and very large rates. */
public enum WidgetNumberFormat {

    NORMAL,
    SCIENTIFIC,
    METRIC;

    private static final String[] PREFIXES = { "", "K", "M", "G", "T", "P" };

    public WidgetNumberFormat next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static WidgetNumberFormat read(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return METRIC;
        }
    }

    public String format(BigDecimal value) {
        return format(value, this == SCIENTIFIC ? 2 : 1);
    }

    /** Axes may request extra digits to keep adjacent ticks distinct. */
    public String format(BigDecimal value, int decimals) {
        if (value == null) return "\u2014";
        if (this == NORMAL) {
            BigDecimal stripped = value.stripTrailingZeros();
            if (stripped.scale() <= 0) return FormatUtil.formatNormal(stripped.toBigIntegerExact());
            String exact = stripped.toPlainString();
            int point = exact.indexOf('.');
            String integer = FormatUtil.formatNormal(stripped.toBigInteger());
            return (stripped.signum() < 0 && stripped.toBigInteger()
                .signum() == 0 ? "-" : "") + integer + exact.substring(point);
        }
        int digits = Math.max(0, Math.min(12, decimals));
        if (this == SCIENTIFIC) {
            int exponent = value.signum() == 0 ? 0 : value.precision() - value.scale() - 1;
            BigDecimal mantissa = value.movePointLeft(exponent)
                .setScale(digits, RoundingMode.HALF_UP);
            if (mantissa.abs()
                .compareTo(BigDecimal.TEN) >= 0) {
                exponent++;
                mantissa = mantissa.movePointLeft(1)
                    .setScale(digits, RoundingMode.HALF_UP);
            } else if (sharedFormatterSafe(value, digits)) {
                return value.stripTrailingZeros()
                    .scale() <= 0 ? FormatUtil.formatScientific(value.toBigIntegerExact(), digits)
                        : FormatUtil.formatScientificDouble(value.doubleValue(), digits);
            }
            return mantissa.toPlainString() + "E" + exponent;
        }
        int group = 0;
        BigDecimal magnitude = value.abs();
        if (sharedFormatterSafe(value, digits)) return value.stripTrailingZeros()
            .scale() <= 0 ? FormatUtil.formatMetric(value.toBigIntegerExact(), digits)
                : FormatUtil.formatMetricDouble(value.doubleValue(), digits);
        while (group + 1 < PREFIXES.length && magnitude.compareTo(BigDecimal.TEN.pow((group + 1) * 3)) >= 0) group++;
        return value.movePointLeft(group * 3)
            .setScale(digits, RoundingMode.HALF_UP)
            .toPlainString() + PREFIXES[group];
    }

    private static boolean sharedFormatterSafe(BigDecimal value, int decimals) {
        return value.signum() != 0 && decimals <= 6
            && value.precision() <= 12
            && value.abs()
                .compareTo(BigDecimal.TEN.pow(15)) < 0
            && value.abs()
                .compareTo(new BigDecimal("1E-100")) >= 0;
    }
}
