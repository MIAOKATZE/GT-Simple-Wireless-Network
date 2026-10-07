package com.miaokatze.gtswn.common.charging;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Decimal network accounting and the HUD's real-time quiet intervals. */
public final class ChargeMath {

    private ChargeMath() {}

    public static BigInteger deduction(double amount, float loss) {
        if (!Double.isFinite(amount) || amount <= 0 || !Float.isFinite(loss) || loss < 0) {
            throw new IllegalArgumentException("Invalid energy or loss");
        }
        return BigDecimal.valueOf(amount)
            .multiply(BigDecimal.ONE.add(new BigDecimal(Float.toString(loss))))
            .setScale(0, RoundingMode.CEILING)
            .toBigIntegerExact();
    }

    public static int quietStatus(int ticks) {
        return ticks < 20 ? 2 : ticks < 120 ? 3 : 1;
    }
}
