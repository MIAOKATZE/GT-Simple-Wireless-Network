package com.miaokatze.gtswn.common.util;

import com.miaokatze.gtswn.config.Config;

/**
 * GTswn 覆盖板数学工具（SWN-BUG-06 + SWN-OPT-17/C-4 单源收敛）。
 * <p>
 * 覆盖板缓冲容量公式「电压 × 安培 ×（基础值 + 冗余值）tick」的唯一定义点
 * （Config.interactionRateTicks + Config.bufferRedundancyTicks，默认 600+200=800）——
 * 链路终端的聊天预告与能源覆盖板的实配计算必须共享同一实现，防止公式再次分叉漂移
 * （历史教训：终端侧曾与覆盖板各持一份实现并漏 (long) 提升，导致预告在 GT MAX 档溢出为负）。
 */
public final class CoverMaths {

    private CoverMaths() {}

    /** Minimum editable current; all values must also be finite. */
    public static final double MIN_AMPERAGE = 0.1;

    public static boolean validAmperage(double amperage) {
        return Double.isFinite(amperage) && amperage >= MIN_AMPERAGE;
    }

    public static double restoredAmperage(double amperage) {
        return validAmperage(amperage) ? amperage : MIN_AMPERAGE;
    }

    public static long bufferCapacity(long voltage, long amperage) {
        long ticks = Math.max(0L, (long) Config.interactionRateTicks + Config.bufferRedundancyTicks);
        return multiplySaturated(multiplySaturated(voltage, amperage), ticks);
    }

    public static long multiplySaturated(long first, long second) {
        if (first <= 0 || second <= 0) return 0;
        return first > Long.MAX_VALUE / second ? Long.MAX_VALUE : first * second;
    }

    public static long bufferCapacity(long voltage, double amperage) {
        if (voltage <= 0 || !validAmperage(amperage)) return 0;
        long ticks = Math.max(0L, (long) Config.interactionRateTicks + Config.bufferRedundancyTicks);
        return saturatedFloor(
            java.math.BigDecimal.valueOf(voltage)
                .multiply(java.math.BigDecimal.valueOf(amperage))
                .multiply(java.math.BigDecimal.valueOf(ticks)));
    }

    private static long saturatedFloor(java.math.BigDecimal amount) {
        if (amount.compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE)) >= 0) return Long.MAX_VALUE;
        return Math.max(0L, amount.longValue());
    }

    public static java.math.BigInteger downlinkDeduction(long needed, double loss) {
        if (needed <= 0) return java.math.BigInteger.ZERO;
        if (!Double.isFinite(loss) || loss < 0) throw new IllegalArgumentException("Invalid downlink loss");
        java.math.BigDecimal amount = java.math.BigDecimal.valueOf(needed);
        return amount.add(amount.multiply(java.math.BigDecimal.valueOf(loss)))
            .setScale(0, java.math.RoundingMode.CEILING)
            .toBigIntegerExact();
    }

    public static long afterUplinkLoss(long stored, double loss) {
        if (stored <= 0 || !Double.isFinite(loss) || loss < 0 || loss > 1) return 0;
        return saturatedFloor(
            java.math.BigDecimal.valueOf(stored)
                .multiply(java.math.BigDecimal.ONE.subtract(java.math.BigDecimal.valueOf(loss))));
    }

    /** Fractional EU carry is bounded below one EU, never an accumulated energy backlog. */
    public static final class TickBudget {

        private java.math.BigDecimal remainder = java.math.BigDecimal.ZERO;
        private java.math.BigDecimal fractionalStep = java.math.BigDecimal.ZERO;
        private long lastVoltage;
        private double lastAmperage;
        private long wholeStep;
        private java.math.BigDecimal pendingRemainder = java.math.BigDecimal.ZERO;

        public long peek(long voltage, double amperage) {
            pendingRemainder = remainder;
            if (voltage <= 0 || !validAmperage(amperage)) return 0;
            if (voltage != lastVoltage || amperage != lastAmperage) {
                java.math.BigDecimal amount = java.math.BigDecimal.valueOf(voltage)
                    .multiply(java.math.BigDecimal.valueOf(amperage));
                wholeStep = saturatedFloor(amount);
                fractionalStep = wholeStep == Long.MAX_VALUE ? java.math.BigDecimal.ZERO
                    : amount.subtract(java.math.BigDecimal.valueOf(wholeStep));
                lastVoltage = voltage;
                lastAmperage = amperage;
            }
            if (wholeStep == Long.MAX_VALUE) {
                pendingRemainder = java.math.BigDecimal.ZERO;
                return wholeStep;
            }
            // Integral currents take the allocation-free path after the first tick.
            if (fractionalStep.signum() == 0) return wholeStep;
            pendingRemainder = remainder.add(fractionalStep);
            if (pendingRemainder.compareTo(java.math.BigDecimal.ONE) >= 0) {
                pendingRemainder = pendingRemainder.subtract(java.math.BigDecimal.ONE);
                return wholeStep + 1;
            }
            return wholeStep;
        }

        public void commit() {
            remainder = pendingRemainder;
        }

        public long next(long voltage, double amperage) {
            long result = peek(voltage, amperage);
            commit();
            return result;
        }

        public double remainder() {
            return remainder.doubleValue();
        }

        public void restore(double value) {
            remainder = Double.isFinite(value) && value >= 0 && value < 1 ? java.math.BigDecimal.valueOf(value)
                : java.math.BigDecimal.ZERO;
        }
    }
}
