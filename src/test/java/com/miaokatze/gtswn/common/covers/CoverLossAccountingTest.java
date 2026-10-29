package com.miaokatze.gtswn.common.covers;

import static org.junit.Assert.*;

import java.math.BigInteger;

import org.junit.Test;

import com.miaokatze.gtswn.common.util.CoverMaths;

/** Use the real configuration's float type, not a different double-literal loss. */
public class CoverLossAccountingTest {

    @Test
    public void configuredFloatDownlinkHonorsDecimalRoundingBoundaries() {
        assertEquals(BigInteger.valueOf(23), CoverMaths.downlinkDeduction(20, 0.15f));
        assertEquals(BigInteger.valueOf(115), CoverMaths.downlinkDeduction(100, 0.15f));
        assertEquals(new BigInteger("10606877842382992179"), CoverMaths.downlinkDeduction(Long.MAX_VALUE, 0.15f));
    }

    @Test
    public void configuredFloatUplinkDoesNotDiscardAnExtraEuOrBillionsAtMaximumCapacity() {
        assertEquals(17, CoverMaths.afterUplinkLoss(20, 0.15f));
        assertEquals(85, CoverMaths.afterUplinkLoss(100, 0.15f));
        assertEquals(7839866231326559435L, CoverMaths.afterUplinkLoss(Long.MAX_VALUE, 0.15f));
    }

    @Test
    public void copiedBuffersNormalizeTheFloatBeforeItWidensThroughAnIntermediateHelper() {
        assertEquals(20, WirelessCoverCopy.fundedBuffer(20, 0.15f, cost -> {
            assertEquals(BigInteger.valueOf(23), cost);
            return true;
        }));
        assertEquals(100, WirelessCoverCopy.refillBuffer(80, 100, 0.15f, cost -> {
            assertEquals(BigInteger.valueOf(23), cost);
            return true;
        }));
    }

    @Test
    public void invalidFloatUplinkRemainsRejected() {
        assertEquals(0, CoverMaths.afterUplinkLoss(20, Float.NaN));
        assertEquals(0, CoverMaths.afterUplinkLoss(20, Float.POSITIVE_INFINITY));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidFloatDownlinkRemainsRejected() {
        CoverMaths.downlinkDeduction(20, Float.NaN);
    }
}
