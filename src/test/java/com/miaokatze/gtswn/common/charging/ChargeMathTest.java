package com.miaokatze.gtswn.common.charging;

import static org.junit.Assert.assertEquals;

import java.math.BigInteger;

import org.junit.Test;

public class ChargeMathTest {

    @Test
    public void configuredFifteenPercentDoesNotOverchargeExactHundred() {
        assertEquals(BigInteger.valueOf(115), ChargeMath.deduction(100, 0.15f));
        assertEquals(BigInteger.valueOf(2), ChargeMath.deduction(1, 0.15f));
        assertEquals(BigInteger.valueOf(1), ChargeMath.deduction(0.5, 0.15f));
        assertEquals(BigInteger.valueOf(100), ChargeMath.deduction(100, 0));
    }

    @Test
    public void networkAccountingCanExceedLongRange() {
        assertEquals(new BigInteger("10606877842382992400"), ChargeMath.deduction((double) Long.MAX_VALUE, 0.15f));
    }

    @Test(expected = IllegalArgumentException.class)
    public void infiniteAmountCannotWithdraw() {
        ChargeMath.deduction(Double.POSITIVE_INFINITY, 0.15f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidLossCannotWithdraw() {
        ChargeMath.deduction(100, Float.NaN);
    }

    @Test
    public void completionWaitsOneSecondAndLastsFiveSeconds() {
        assertEquals(2, ChargeMath.quietStatus(0));
        assertEquals(2, ChargeMath.quietStatus(19));
        assertEquals(3, ChargeMath.quietStatus(20));
        assertEquals(3, ChargeMath.quietStatus(119));
        assertEquals(1, ChargeMath.quietStatus(120));
    }
}
