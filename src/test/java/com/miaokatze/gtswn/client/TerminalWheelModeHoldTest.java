package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalWheelModeHoldTest {

    @Test
    public void oneContinuousSecondChangesModeExactlyOnce() {
        TerminalWheelModeHold hold = new TerminalWheelModeHold();
        hold.press(100);
        assertFalse(hold.update(1099, true));
        assertTrue(hold.update(1100, true));
        assertFalse(hold.update(5000, true));
        assertEquals(1, hold.progress(), 0);
        hold.cancel();
        hold.press(6000);
        assertTrue(hold.update(7000, true));
    }

    @Test
    public void leavingReleasingOrLosingFocusCancelsUntilANewPress() {
        TerminalWheelModeHold hold = new TerminalWheelModeHold();
        hold.press(0);
        assertFalse(hold.update(500, true));
        assertFalse(hold.update(800, false));
        assertEquals(0, hold.progress(), 0);
        assertFalse(hold.update(2000, true));
        hold.press(2000);
        assertFalse(hold.update(2500, true));
        hold.cancel();
        assertFalse(hold.update(4000, true));
    }
}
