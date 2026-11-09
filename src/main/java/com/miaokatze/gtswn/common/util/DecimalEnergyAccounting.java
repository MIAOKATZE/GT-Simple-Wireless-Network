package com.miaokatze.gtswn.common.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Exact decimal arithmetic only; each caller owns its input validation, native payment and buffer rules. */
public final class DecimalEnergyAccounting {

    private DecimalEnergyAccounting() {}

    public static BigInteger deduction(BigDecimal amount, BigDecimal loss) {
        return amount.multiply(BigDecimal.ONE.add(loss))
            .setScale(0, RoundingMode.CEILING)
            .toBigIntegerExact();
    }
}
