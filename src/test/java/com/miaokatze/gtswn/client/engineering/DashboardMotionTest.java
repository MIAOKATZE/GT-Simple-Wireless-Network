package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects;

public class DashboardMotionTest {

    @Test
    public void cardStateIsReusedAndRemovedWhenItsPageIsGone() {
        DashboardMotion motion = new DashboardMotion();
        motion.begin(0);
        DashboardMotion.State first = motion.card("card", WidgetEffects.DEFAULT);
        motion.end();
        motion.begin(100_000_000);
        assertSame(first, motion.card("card", WidgetEffects.DEFAULT));
        assertTrue(first.phase > 0);
        double phase = first.phase;
        assertSame(first, motion.card("card", WidgetEffects.DEFAULT));
        assertEquals(phase, first.phase, 0);
        motion.end();
        motion.begin(200_000_000);
        motion.card("other", WidgetEffects.DEFAULT);
        motion.end();
        assertEquals(1, motion.size());
        motion.clear();
        assertEquals(0, motion.size());
    }

    @Test
    public void disabledCardKeepsItsPhaseWithoutReopenCatchup() {
        DashboardMotion motion = new DashboardMotion();
        motion.begin(0);
        DashboardMotion.State card = motion.card("card", WidgetEffects.DEFAULT);
        motion.begin(100_000_000);
        motion.card("card", WidgetEffects.DEFAULT);
        double phase = card.phase;
        motion.begin(200_000_000);
        motion.card("card", WidgetEffects.DEFAULT.withAnimated(false));
        assertEquals(phase, card.phase, 0);
        motion.begin(100_000_000_000L);
        motion.card("card", WidgetEffects.DEFAULT);
        assertEquals(.018 - .18 * (1 - Math.exp(-.5)) / 5, card.phase - phase, 1e-12);
    }

    @Test
    public void changingSpeedKeepsAccumulatedPhaseAndEasingIsFrameRateIndependent() {
        DashboardMotion.State fast = new DashboardMotion.State(), slow = new DashboardMotion.State();
        fast.advance(0, WidgetEffects.DEFAULT);
        slow.advance(0, WidgetEffects.DEFAULT);
        for (int i = 1; i <= 100; i++) fast.advance(i * 10_000_000L, WidgetEffects.DEFAULT);
        for (int i = 1; i <= 20; i++) slow.advance(i * 50_000_000L, WidgetEffects.DEFAULT);
        assertEquals(fast.phase, slow.phase, 1e-12);
        double phase = fast.phase, velocity = fast.velocity;
        fast.advance(1_000_000_000L, WidgetEffects.DEFAULT.withSpeed(3));
        assertEquals(phase, fast.phase, 0);
        fast.advance(1_010_000_000L, WidgetEffects.DEFAULT.withSpeed(3));
        assertTrue(fast.velocity > velocity);
        assertTrue(fast.velocity < .54);
        phase = fast.phase;
        fast.advance(1_020_000_000L, WidgetEffects.DEFAULT.withSpeed(0));
        assertEquals(phase, fast.phase, 0);
    }

    @Test
    public void barGeometryIsReusedUntilSnapshotIdentityOrViewportWidthChanges() {
        DashboardMotion.State card = new DashboardMotion.State();
        double[] values = { .2, .4, .8 };
        long[] ticks = { 0, 10, 20 };
        FactoryChartGeometry.Bars first = card.bars(values, ticks, 300);
        assertSame(first, card.bars(values, ticks, 300));
        FactoryChartGeometry.Bars resized = card.bars(values, ticks, 320);
        assertNotSame(first, resized);
        assertSame(resized, card.bars(values, ticks, 320));
        assertNotSame(resized, card.bars(values.clone(), ticks, 320));
        FactoryChartGeometry.Bars next = card.bars(values, ticks, 320);
        assertNotSame(next, card.bars(values, ticks.clone(), 320));
    }
}
