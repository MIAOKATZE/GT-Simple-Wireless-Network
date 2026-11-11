// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.util;

import static org.junit.Assert.assertEquals;

import java.math.BigInteger;
import java.util.Random;

import org.junit.Test;

public class SaturatingLongTest {

    @Test
    public void signedLimitsAndRandomPairsMatchExactIntegerClamping() {
        long[] limits = { Long.MIN_VALUE, Long.MIN_VALUE + 1, -1, 0, 1, Long.MAX_VALUE - 1, Long.MAX_VALUE };
        for (long value : limits) for (long step : limits) check(value, step);
        Random random = new Random(20261109);
        for (int i = 0; i < 10000; i++) check(random.nextLong(), random.nextLong());
    }

    private static void check(long value, long step) {
        BigInteger expected = BigInteger.valueOf(value)
            .add(BigInteger.valueOf(step))
            .min(BigInteger.valueOf(Long.MAX_VALUE))
            .max(BigInteger.valueOf(Long.MIN_VALUE));
        assertEquals(expected.longValue(), SaturatingLong.add(value, step));
    }
}
