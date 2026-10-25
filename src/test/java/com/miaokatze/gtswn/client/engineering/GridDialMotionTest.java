package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.Test;

public class GridDialMotionTest {

    private static void frames(GridDialMotion motion, int from, int to, BigDecimal rate) {
        for (int i = from; i <= to; i++) motion.advance(i * 10_000_000L, rate, true, 1);
    }

    @Test
    public void changingMagnitudeDoesNotRecalculateAbsoluteClockPhase() {
        GridDialMotion motion = new GridDialMotion();
        frames(motion, 0, 100, BigDecimal.TEN);
        double before = motion.phase();
        assertEquals(before, motion.advance(1_000_000_000L, new BigDecimal("1e12"), true, 1), 0);
        double after = motion.advance(1_010_000_000L, new BigDecimal("1e12"), true, 1);
        assertTrue(after > before);
        assertTrue(after - before < .0025);
    }

    @Test
    public void reversalDeceleratesThroughZeroAndUnknownStopsAtAccumulatedAngle() {
        GridDialMotion motion = new GridDialMotion();
        frames(motion, 0, 100, BigDecimal.TEN);
        double velocity = motion.velocity();
        motion.advance(1_010_000_000L, BigDecimal.TEN.negate(), true, 1);
        assertTrue(motion.velocity() > 0 && motion.velocity() < velocity);
        frames(motion, 102, 180, BigDecimal.TEN.negate());
        assertTrue(motion.velocity() < 0);
        frames(motion, 181, 600, null);
        assertEquals(0, motion.velocity(), 0);
        double stopped = motion.phase();
        assertTrue(stopped > 0);
        frames(motion, 601, 700, BigDecimal.ZERO);
        assertEquals(stopped, motion.phase(), 0);
    }

    @Test
    public void integrationIsFrameRateIndependentAndBoundedAfterLongGap() {
        GridDialMotion fast = new GridDialMotion(), slow = new GridDialMotion();
        frames(fast, 0, 100, BigDecimal.TEN);
        for (int i = 0; i <= 20; i++) slow.advance(i * 50_000_000L, BigDecimal.TEN, true, 1);
        assertEquals(fast.phase(), slow.phase(), 1e-12);
        assertEquals(fast.velocity(), slow.velocity(), 1e-12);
        double before = fast.phase();
        fast.advance(100_000_000_000L, BigDecimal.TEN, true, 1);
        assertTrue(fast.phase() - before < .025);
        assertEquals(.25, GridDialMotion.targetSpeed(new BigDecimal(BigInteger.TEN.pow(400))), 0);
    }

    @Test
    public void disablingFreezesCurrentAngleAndResumingHasNoJump() {
        GridDialMotion motion = new GridDialMotion();
        frames(motion, 0, 100, BigDecimal.TEN);
        double before = motion.phase();
        assertEquals(before, motion.advance(2_000_000_000L, BigDecimal.TEN, false, 1), 0);
        assertEquals(before, motion.advance(3_000_000_000L, BigDecimal.TEN, true, 0), 0);
        assertEquals(before, motion.advance(3_000_000_000L, BigDecimal.TEN, true, 1), 0);
        assertTrue(motion.advance(3_010_000_000L, BigDecimal.TEN, true, 1) - before < .001);
    }
}
