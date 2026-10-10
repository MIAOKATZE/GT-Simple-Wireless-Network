package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TerminalChargeProgressTest {

    @Test
    public void sendsBeginBeforeOneSecondHoldAndCompletesOnce() {
        TerminalChargeProgress progress = new TerminalChargeProgress();
        progress.start();
        assertEquals(TerminalChargeProgress.Step.BEGIN, progress.tick(true));
        for (int tick = 1; tick < 20; tick++) {
            assertEquals(TerminalChargeProgress.Step.HOLD, progress.tick(true));
        }
        assertEquals(TerminalChargeProgress.Step.COMPLETE, progress.tick(true));
        assertEquals(TerminalChargeProgress.Step.IDLE, progress.tick(true));
    }

    @Test
    public void interruptedGestureCannotCompleteAndRestartRequiresFullHold() {
        TerminalChargeProgress progress = new TerminalChargeProgress();
        progress.start();
        for (int tick = 0; tick < 19; tick++) progress.tick(true);
        assertEquals(TerminalChargeProgress.Step.IDLE, progress.tick(false));
        assertEquals(TerminalChargeProgress.Step.IDLE, progress.tick(true));
        progress.start();
        assertEquals(TerminalChargeProgress.Step.BEGIN, progress.tick(true));
        for (int tick = 1; tick < 20; tick++) {
            assertEquals(TerminalChargeProgress.Step.HOLD, progress.tick(true));
        }
        assertEquals(TerminalChargeProgress.Step.COMPLETE, progress.tick(true));
    }

    @Test
    public void explicitCancelDropsAllAccumulatedTicks() {
        TerminalChargeProgress progress = new TerminalChargeProgress();
        progress.start();
        progress.tick(true);
        progress.cancel();
        assertEquals(TerminalChargeProgress.Step.IDLE, progress.tick(true));
    }
}
