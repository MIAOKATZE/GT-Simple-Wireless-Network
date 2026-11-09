package com.miaokatze.gtswn.common.util;

/** Signed arithmetic shared by cover buffers and monitor UI steps. */
public final class SaturatingLong {

    private SaturatingLong() {}

    public static long add(long value, long step) {
        if (step > 0L && value > Long.MAX_VALUE - step) return Long.MAX_VALUE;
        if (step < 0L && value < Long.MIN_VALUE - step) return Long.MIN_VALUE;
        return value + step;
    }
}
