package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class TerminalInputReleaseBarrierTest {

    @Test
    public void openWheelSuppressesMovementAttackUseAndDropBindings() {
        TerminalInputReleaseBarrier barrier = new TerminalInputReleaseBarrier();
        barrier.open();
        for (int code : new int[] { 17, 30, 31, 32, 57, 16, -100, -99 }) {
            assertTrue(barrier.suppresses(code, ignored -> true));
        }
    }

    @Test
    public void closingSpaceAndShiftCannotLeakButNewMovementIsImmediatelyAvailable() {
        TerminalInputReleaseBarrier barrier = new TerminalInputReleaseBarrier();
        Set<Integer> down = new HashSet<>();
        down.add(57);
        down.add(42);
        barrier.open();
        assertTrue(barrier.close(new int[] { 57, 42, 30 }, down::contains));
        assertTrue(barrier.suppresses(57, down::contains));
        assertTrue(barrier.suppresses(42, down::contains));
        down.add(30);
        assertFalse(barrier.suppresses(30, down::contains));
        down.remove(57);
        assertFalse(barrier.suppresses(57, down::contains));
        down.add(57);
        assertFalse(barrier.suppresses(57, down::contains));
        assertTrue(barrier.suppresses(42, down::contains));
    }

    @Test
    public void releaseEventAllowsAnImmediateRepressWithoutWaitingForAnotherTick() {
        TerminalInputReleaseBarrier barrier = new TerminalInputReleaseBarrier();
        barrier.open();
        barrier.close(new int[] { 46, -100 }, ignored -> true);
        assertTrue(barrier.suppresses(46, ignored -> true));
        barrier.released(46);
        assertFalse(barrier.suppresses(46, ignored -> true));
        assertTrue(barrier.suppresses(-100, ignored -> true));
    }

    @Test
    public void repeatedCloseDoesNotCaptureNewlyPressedKeys() {
        TerminalInputReleaseBarrier barrier = new TerminalInputReleaseBarrier();
        barrier.open();
        barrier.close(new int[] { 46 }, ignored -> true);
        assertFalse(barrier.close(new int[] { 30 }, ignored -> true));
        assertFalse(barrier.suppresses(30, ignored -> true));
    }

    @Test
    public void physicalReleaseWhileAnotherGuiIsOpenDoesNotLeaveAStaleFence() {
        TerminalInputReleaseBarrier barrier = new TerminalInputReleaseBarrier();
        barrier.open();
        barrier.close(new int[] { 46 }, ignored -> true);
        barrier.poll(ignored -> false);
        assertFalse(barrier.suppresses(46, ignored -> true));
    }
}
