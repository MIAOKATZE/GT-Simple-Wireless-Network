package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QuantumMigrationGestureTest {

    @Test
    public void duplicateMouseInteractAndItemUseEntrancesCannotRestartCharge() {
        QuantumMigrationGesture gesture = new QuantumMigrationGesture();
        assertTrue(gesture.press(1));
        assertEquals(QuantumMigrationGesture.Step.BEGIN, gesture.tick());
        for (int tick = 1; tick < 300; tick++) {
            assertFalse(gesture.press(2));
            assertEquals(QuantumMigrationGesture.Step.HOLD, gesture.tick());
        }
        assertEquals(QuantumMigrationGesture.Step.COMPLETE, gesture.tick());
        assertEquals(1, gesture.session());
        for (int tick = 0; tick < 200; tick++) assertEquals(QuantumMigrationGesture.Step.HOLD, gesture.tick());
    }

    @Test
    public void cancelledOrRejectedAttemptCannotRestartUntilPhysicalRelease() {
        QuantumMigrationGesture gesture = new QuantumMigrationGesture();
        assertTrue(gesture.press(1));
        gesture.tick();
        gesture.cancel();
        assertEquals(QuantumMigrationGesture.Step.IDLE, gesture.tick());
        assertFalse(gesture.press(2));
        gesture.release();
        assertTrue(gesture.press(2));
        assertEquals(QuantumMigrationGesture.Step.BEGIN, gesture.tick());
        assertTrue(gesture.acknowledge(2));
        assertFalse(gesture.press(3));
    }

    @Test
    public void delayedResultCannotClearNewAttemptAndRepeatedResultIsInert() {
        QuantumMigrationGesture gesture = new QuantumMigrationGesture();
        assertTrue(gesture.press(1));
        gesture.release();
        assertTrue(gesture.press(2));
        assertFalse(gesture.acknowledge(1));
        assertEquals(QuantumMigrationGesture.Step.BEGIN, gesture.tick());
        assertTrue(gesture.acknowledge(2));
        assertFalse(gesture.acknowledge(2));
        assertEquals(QuantumMigrationGesture.Step.IDLE, gesture.tick());
    }
}
