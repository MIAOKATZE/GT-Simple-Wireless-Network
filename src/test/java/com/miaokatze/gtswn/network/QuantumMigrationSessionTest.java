package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QuantumMigrationSessionTest {

    @Test
    public void earlyCompleteWaitsForExactlySixHundredServerTicksAndConsumesOnce() {
        QuantumMigrationSession session = new QuantumMigrationSession(500);
        session.complete();
        for (int tick = 500; tick < 1100; tick++) {
            session.heartbeat(tick);
            assertTrue(session.validate(tick, true));
            assertFalse("tick " + tick, session.consume(tick));
        }
        session.heartbeat(1100);
        assertTrue(session.validate(1100, true));
        assertTrue(session.consume(1100));
        session.complete();
        session.heartbeat(1100);
        assertFalse(session.consume(1100));
    }

    @Test
    public void cancellationOnCompletionTickWinsAndCannotBeResumed() {
        QuantumMigrationSession session = readyAtFiveHundredNinetyNine();
        session.cancel();
        session.complete();
        session.heartbeat(600);
        assertFalse(session.validate(600, true));
        assertFalse(session.consume(600));
    }

    @Test
    public void oneInvalidRayStackSourceSlotDimensionOrUseTickPermanentlyInvalidatesCredential() {
        QuantumMigrationSession session = readyAtFiveHundredNinetyNine();
        assertFalse(session.validate(600, false));
        session.complete();
        session.heartbeat(601);
        assertFalse(session.validate(601, true));
        assertFalse(session.consume(601));
    }

    @Test
    public void beginAndCompleteWithoutContinuingHeartbeatCannotMigrate() {
        QuantumMigrationSession session = new QuantumMigrationSession(0);
        session.complete();
        for (int tick = 1; tick <= 10; tick++) assertTrue(session.validate(tick, true));
        assertFalse(session.validate(11, true));
        session.heartbeat(600);
        assertFalse(session.validate(600, true));
        assertFalse(session.consume(600));
    }

    @Test
    public void heartbeatAndClientCompletionCannotReplaceMissingServerValidation() {
        QuantumMigrationSession session = new QuantumMigrationSession(0);
        session.heartbeat(600);
        session.complete();
        assertFalse(session.consume(600));
        assertFalse(session.validate(600, true));
        assertFalse(session.consume(600));
    }

    @Test
    public void sixHundredValidTicksStillRequireExplicitCompletionIntent() {
        QuantumMigrationSession session = new QuantumMigrationSession(0);
        for (int tick = 1; tick <= 600; tick++) {
            session.heartbeat(tick);
            assertTrue(session.validate(tick, true));
        }
        assertFalse(session.consume(600));
        session.complete();
        assertTrue(session.consume(600));
    }

    @Test
    public void skippedValidationAtEndCannotBeReplacedByHeartbeatOrExtraElapsedTime() {
        QuantumMigrationSession session = readyAtFiveHundredNinetyNine();
        session.complete();
        session.heartbeat(601);
        assertFalse(session.validate(601, true));
        assertFalse(session.consume(601));
        session.heartbeat(602);
        assertFalse(session.validate(602, true));
        assertFalse(session.consume(602));
    }

    private static QuantumMigrationSession readyAtFiveHundredNinetyNine() {
        QuantumMigrationSession session = new QuantumMigrationSession(0);
        for (int tick = 1; tick <= 599; tick++) {
            session.heartbeat(tick);
            assertTrue(session.validate(tick, true));
        }
        return session;
    }
}
