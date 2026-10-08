package com.miaokatze.gtswn.common.util;

import static org.junit.Assert.*;

import java.math.BigInteger;

import org.junit.Test;

public class CoverMathsTest {

    @Test
    public void idleAndFailedTransfersDoNotAccumulateFractionalBudget() {
        CoverMaths.TickBudget budget = new CoverMaths.TickBudget();
        assertEquals(3, budget.peek(32, 0.1));
        // Full/empty hosts skip work entirely; rejected transfers never commit their peek.
        for (int i = 0; i < 100; i++) assertEquals(3, budget.peek(32, 0.1));
        assertEquals(0, budget.remainder(), 0);
        long sum = 0;
        for (int i = 0; i < 5; i++) {
            sum += budget.peek(32, 0.1);
            budget.commit();
        }
        assertEquals(16, sum);
        // A live sub-EU tick must commit even when it sends zero whole EU.
        CoverMaths.TickBudget tiny = new CoverMaths.TickBudget();
        for (int i = 0; i < 9; i++) assertEquals(0, tiny.next(1, 0.1));
        assertEquals(1, tiny.next(1, 0.1));
    }

    @Test
    public void decimalCurrentKeepsFractionalEnergyAcrossTicks() {
        CoverMaths.TickBudget budget = new CoverMaths.TickBudget();
        long sum = 0;
        for (int i = 0; i < 100; i++) sum += budget.next(32, 0.1);
        assertEquals(320, sum);
        assertEquals(0, budget.remainder(), 0.00000001);
    }

    @Test
    public void fractionSurvivesRestoreAndCurrentChange() {
        CoverMaths.TickBudget first = new CoverMaths.TickBudget();
        assertEquals(3, first.next(32, 0.1));
        CoverMaths.TickBudget restored = new CoverMaths.TickBudget();
        restored.restore(first.remainder());
        long sum = 3;
        for (int i = 0; i < 4; i++) sum += restored.next(32, 0.1);
        assertEquals(16, sum);
        restored.restore(Double.NaN);
        assertEquals(0, restored.remainder(), 0);
    }

    @Test
    public void highVoltageAndCurrentSaturateWithoutWrapping() {
        assertEquals(Long.MAX_VALUE, CoverMaths.bufferCapacity(Long.MAX_VALUE, Double.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, new CoverMaths.TickBudget().next(Long.MAX_VALUE, Double.MAX_VALUE));
        assertEquals(922337203685477580L, new CoverMaths.TickBudget().next(Long.MAX_VALUE, 0.1));
    }

    @Test
    public void minimumAndFiniteCurrentAreEnforced() {
        assertTrue(CoverMaths.validAmperage(0.1));
        assertFalse(CoverMaths.validAmperage(0.099));
        assertFalse(CoverMaths.validAmperage(Double.NaN));
        assertFalse(CoverMaths.validAmperage(Double.POSITIVE_INFINITY));
        assertEquals(0.1, CoverMaths.restoredAmperage(0), 0);
    }

    @Test
    public void lossesDoNotOverflowOrCreateEnergy() {
        assertEquals(new BigInteger("10606877842382992179"), CoverMaths.downlinkDeduction(Long.MAX_VALUE, 0.15));
        assertEquals(
            2,
            CoverMaths.downlinkDeduction(1, 0.15)
                .longValue());
        assertEquals(7839866231326559435L, CoverMaths.afterUplinkLoss(Long.MAX_VALUE, 0.15));
        assertEquals(0, CoverMaths.afterUplinkLoss(Long.MAX_VALUE, Double.NaN));
    }
}
