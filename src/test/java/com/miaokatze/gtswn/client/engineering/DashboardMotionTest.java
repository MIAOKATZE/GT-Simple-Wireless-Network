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
        assertEquals(.018, card.phase - phase, 1e-12);
    }
}
