package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QuantumMigrationSessionTest {

    @Test
    public void earlyCompleteWaitsForExactlyOneHundredServerTicksAndConsumesOnce() {
        QuantumMigrationSession session = new QuantumMigrationSession(500);
        session.complete();
        for (int tick = 500; tick < 600; tick++) {
            session.heartbeat(tick);
            assertTrue(session.validate(tick, true));
            assertFalse("tick " + tick, session.consume(tick));
        }
        session.heartbeat(600);
        assertTrue(session.validate(600, true));
        assertTrue(session.consume(600));
        session.complete();
        session.heartbeat(600);
        assertFalse(session.consume(600));
    }

    @Test
    public void cancellationOnCompletionTickWinsAndCannotBeResumed() {
        QuantumMigrationSession session = readyAtNinetyNine();
        session.cancel();
        session.complete();
        session.heartbeat(100);
        assertFalse(session.validate(100, true));
        assertFalse(session.consume(100));
    }

    @Test
    public void oneInvalidRayStackSourceSlotDimensionOrUseTickPermanentlyInvalidatesCredential() {
        QuantumMigrationSession session = readyAtNinetyNine();
        assertFalse(session.validate(100, false));
        session.complete();
        session.heartbeat(101);
        assertFalse(session.validate(101, true));
        assertFalse(session.consume(101));
    }

    @Test
    public void beginAndCompleteWithoutContinuingHeartbeatCannotMigrate() {
        QuantumMigrationSession session = new QuantumMigrationSession(0);
        session.complete();
        for (int tick = 1; tick <= 10; tick++) assertTrue(session.validate(tick, true));
        assertFalse(session.validate(11, true));
        session.heartbeat(100);
        assertFalse(session.validate(100, true));
        assertFalse(session.consume(100));
    }

    @Test
    public void heartbeatAndClientCompletionCannotReplaceMissingServerValidation() {
        QuantumMigrationSession session = new QuantumMigrationSession(0);
        session.heartbeat(100);
        session.complete();
        assertFalse(session.consume(100));
        assertFalse(session.validate(100, true));
        assertFalse(session.consume(100));
    }

    @Test
    public void hundredValidTicksStillRequireExplicitCompletionIntent() {
        QuantumMigrationSession session = new QuantumMigrationSession(0);
        for (int tick = 1; tick <= 100; tick++) {
            session.heartbeat(tick);
            assertTrue(session.validate(tick, true));
        }
        assertFalse(session.consume(100));
        session.complete();
        assertTrue(session.consume(100));
    }

    private static QuantumMigrationSession readyAtNinetyNine() {
        QuantumMigrationSession session = new QuantumMigrationSession(0);
        for (int tick = 1; tick <= 99; tick++) {
            session.heartbeat(tick);
            assertTrue(session.validate(tick, true));
        }
        return session;
    }
}
