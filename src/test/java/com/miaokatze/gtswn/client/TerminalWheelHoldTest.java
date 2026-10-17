package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalWheelHoldTest {

    @Test
    public void openingTapDoesNotCycleOrConfirm() {
        TerminalWheelHold hold = new TerminalWheelHold();
        assertFalse(hold.press());
        for (int tick = 0; tick < 10; tick++) assertFalse(hold.tick(true));
        assertFalse(hold.tick(false));
        assertEquals(0, hold.progress(), 0);
    }

    @Test
    public void openingHoldConfirmsAtTwentyTicksWithoutACycle() {
        TerminalWheelHold hold = new TerminalWheelHold();
        for (int tick = 1; tick < 20; tick++) {
            assertFalse(hold.press());
            assertFalse(hold.tick(true));
        }
        assertTrue(hold.tick(true));
        for (int tick = 0; tick < 80; tick++) {
            assertFalse(hold.press());
            assertFalse(hold.tick(true));
        }
    }

    @Test
    public void laterPressCyclesExactlyOnceAndHoldConfirmsExactlyOnce() {
        TerminalWheelHold hold = new TerminalWheelHold();
        hold.release();
        assertTrue(hold.press());
        for (int tick = 1; tick < 20; tick++) {
            assertFalse(hold.press());
            assertFalse(hold.tick(true));
        }
        assertTrue(hold.tick(true));
        assertFalse(hold.tick(true));
        hold.release();
        assertTrue(hold.press());
    }

    @Test
    public void separateShortPressesDoNotAccumulateHoldTime() {
        TerminalWheelHold hold = new TerminalWheelHold();
        for (int press = 0; press < 4; press++) {
            hold.release();
            assertTrue(hold.press());
            for (int tick = 0; tick < 12; tick++) assertFalse(hold.tick(true));
        }
    }

    @Test
    public void eitherShiftTakesTheCurrentSelectionWithoutCyclingAndOpeningDoesNotTake() {
        TerminalWheelHold hold = new TerminalWheelHold();
        assertEquals(TerminalWheelHold.IGNORED, hold.press(true, true));
        hold.release();
        assertEquals(TerminalWheelHold.TAKE, hold.press(true, false));
        assertEquals(TerminalWheelHold.IGNORED, hold.press(true, false));
        hold.release();
        assertEquals(TerminalWheelHold.TAKE, hold.press(false, true));
        for (int tick = 0; tick < 25; tick++) assertFalse(hold.tick(true));
        hold.release();
        assertEquals(TerminalWheelHold.CYCLE, hold.press(false, false));
    }
}
