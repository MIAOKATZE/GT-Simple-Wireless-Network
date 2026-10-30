package com.miaokatze.gtswn.common.covers;

import static org.junit.Assert.*;

import java.math.BigInteger;

import org.junit.Test;

import com.miaokatze.gtswn.common.util.CoverMaths;

/** Use the real configuration's float type, not a different double-literal loss. */
public class CoverLossAccountingTest {

    @Test
    public void defaultRuntimeFixtureHasExactConfiguredDecimalDebit() {
        // WirelessEuScenario configures LV 32V x 1A with default 600+200 tick buffer.
        // Independent expected result: 25,600 * 1.15 = 29,440, not 29,441.
        long buffer = 32L * (600 + 200);
        assertEquals(BigInteger.valueOf(29440), CoverMaths.downlinkDeduction(buffer, 0.15f));
        assertEquals(21760L, CoverMaths.afterUplinkLoss(buffer, 0.15f));
    }

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
    public void configuredFloatBelowOneCanLeaveTheRuntimeFixtureUncreditable() {
        // Independent boundary: floor(256 * 0.001) = 0, although the loss is below 100%.
        assertEquals(0L, CoverMaths.afterUplinkLoss(256, 0.999f));
        // A nearby configured loss still produces positive whole-EU credit: floor(1.024) = 1.
        assertEquals(1L, CoverMaths.afterUplinkLoss(256, 0.996f));
        assertEquals(256L, CoverMaths.afterUplinkLoss(256, 0.0f));
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
