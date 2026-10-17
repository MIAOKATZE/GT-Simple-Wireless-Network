package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TerminalWheelHoldTest {

    @Test
    public void openingTapOnlyOpensWithoutCyclingOnPressOrRelease() {
        TerminalWheelHold hold = new TerminalWheelHold();
        assertEquals(TerminalWheelHold.IGNORED, hold.press(false, false));
        for (int tick = 0; tick < 3; tick++) assertEquals(TerminalWheelHold.IGNORED, hold.tick(true));
        assertEquals(TerminalWheelHold.IGNORED, hold.release());
        assertEquals(TerminalWheelHold.IGNORED, hold.tick(false));
        assertEquals(TerminalWheelHold.IGNORED, hold.release());
        assertEquals(0, hold.progress(), 0);
    }

    @Test
    public void openingHoldConfirmsAtTenTicksWithoutACycle() {
        TerminalWheelHold hold = new TerminalWheelHold();
        for (int tick = 1; tick < TerminalWheelHold.CONFIRM_TICKS; tick++) {
            assertEquals(TerminalWheelHold.IGNORED, hold.press(false, false));
            assertEquals(TerminalWheelHold.IGNORED, hold.tick(true));
        }
        assertEquals(TerminalWheelHold.CONFIRM, hold.tick(true));
        for (int tick = 0; tick < 80; tick++) {
            assertEquals(TerminalWheelHold.IGNORED, hold.press(false, false));
            assertEquals(TerminalWheelHold.IGNORED, hold.tick(true));
        }
        assertEquals(TerminalWheelHold.IGNORED, hold.release());
        assertEquals(TerminalWheelHold.IGNORED, hold.tick(false));
    }

    @Test
    public void laterTapCyclesOnReleaseAndHoldConfirmsTheUnchangedSelection() {
        TerminalWheelHold hold = new TerminalWheelHold();
        hold.release();
        assertEquals(TerminalWheelHold.IGNORED, hold.press(false, false));
        assertEquals(TerminalWheelHold.CYCLE, hold.release());
        assertEquals(TerminalWheelHold.IGNORED, hold.tick(false));
        assertEquals(TerminalWheelHold.IGNORED, hold.press(false, false));
        for (int tick = 1; tick < TerminalWheelHold.CONFIRM_TICKS; tick++) {
            assertEquals(TerminalWheelHold.IGNORED, hold.press(false, false));
            assertEquals(TerminalWheelHold.IGNORED, hold.tick(true));
        }
        assertEquals(TerminalWheelHold.CONFIRM, hold.tick(true));
        assertEquals(TerminalWheelHold.IGNORED, hold.tick(true));
        assertEquals(TerminalWheelHold.IGNORED, hold.release());
        assertEquals(TerminalWheelHold.IGNORED, hold.press(false, false));
    }

    @Test
    public void separateShortPressesDoNotAccumulateHoldTime() {
        TerminalWheelHold hold = new TerminalWheelHold();
        for (int press = 0; press < 4; press++) {
            hold.release();
            assertEquals(TerminalWheelHold.IGNORED, hold.press(false, false));
            for (int tick = 0; tick < 5; tick++) assertEquals(TerminalWheelHold.IGNORED, hold.tick(true));
            assertEquals(TerminalWheelHold.CYCLE, press % 2 == 0 ? hold.release() : hold.tick(false));
            assertEquals(TerminalWheelHold.IGNORED, press % 2 == 0 ? hold.tick(false) : hold.release());
        }
    }

    @Test
    public void eitherShiftTakesTheCurrentSelectionWithoutCyclingAndOpeningDoesNotTake() {
        TerminalWheelHold hold = new TerminalWheelHold();
        assertEquals(TerminalWheelHold.IGNORED, hold.press(true, true));
        assertEquals(TerminalWheelHold.IGNORED, hold.release());
        assertEquals(TerminalWheelHold.TAKE, hold.press(true, false));
        assertEquals(TerminalWheelHold.IGNORED, hold.press(true, false));
        assertEquals(TerminalWheelHold.IGNORED, hold.release());
        assertEquals(TerminalWheelHold.TAKE, hold.press(false, true));
        for (int tick = 0; tick < 25; tick++) assertEquals(TerminalWheelHold.IGNORED, hold.tick(true));
        assertEquals(TerminalWheelHold.IGNORED, hold.release());
        assertEquals(TerminalWheelHold.IGNORED, hold.press(false, false));
        assertEquals(TerminalWheelHold.CYCLE, hold.release());
    }
}
