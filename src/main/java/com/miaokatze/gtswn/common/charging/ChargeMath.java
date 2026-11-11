// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.charging;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Decimal network accounting and the HUD's real-time quiet intervals. */
public final class ChargeMath {

    private ChargeMath() {}

    public static BigInteger deduction(double amount, float loss) {
        if (!Double.isFinite(amount) || amount <= 0) throw new IllegalArgumentException("Invalid energy");
        return deduction(BigDecimal.valueOf(amount), loss);
    }

    public static BigInteger deduction(BigDecimal amount, float loss) {
        if (amount.signum() <= 0 || !Float.isFinite(loss) || loss < 0) {
            throw new IllegalArgumentException("Invalid energy or loss");
        }
        return com.miaokatze.gtswn.common.util.DecimalEnergyAccounting
            .deduction(amount, new BigDecimal(Float.toString(loss)));
    }

    public static int quietStatus(int ticks) {
        return ticks < 20 ? 2 : ticks < 120 ? 3 : 1;
    }
}
