package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QuantumMigrationSessionTest {

    @Test
    public void earlyCompleteWaitsForExactlyThreeHundredServerTicksAndConsumesOnce() {
        QuantumMigrationSession session = new QuantumMigrationSession(500);
        session.complete();
        for (int tick = 500; tick < 800; tick++) {
            session.heartbeat(tick);
            assertTrue(session.validate(tick, true));
            assertFalse("tick " + tick, session.consume(tick));
        }
        session.heartbeat(800);
        assertTrue(session.validate(800, true));
        assertTrue(session.consume(800));
        session.complete();
        session.heartbeat(800);
        assertFalse(session.consume(800));
    }

    @Test
    public void cancellationOnCompletionTickWinsAndCannotBeResumed() {
        QuantumMigrationSession session = readyAtTwoHundredNinetyNine();
        session.cancel();
        session.complete();
        session.heartbeat(300);
        assertFalse(session.validate(300, true));
        assertFalse(session.consume(300));
    }

    @Test
    public void oneInvalidRayStackSourceSlotDimensionOrUseTickPermanentlyInvalidatesCredential() {
        QuantumMigrationSession session = readyAtTwoHundredNinetyNine();
        assertFalse(session.validate(300, false));
        session.complete();
        session.heartbeat(301);
        assertFalse(session.validate(301, true));
        assertFalse(session.consume(301));
    }

    @Test
    public void beginAndCompleteWithoutContinuingHeartbeatCannotMigrate() {
        QuantumMigrationSession session = new QuantumMigrationSession(0);
        session.complete();
        for (int tick = 1; tick <= 10; tick++) assertTrue(session.validate(tick, true));
        assertFalse(session.validate(11, true));
        session.heartbeat(300);
        assertFalse(session.validate(300, true));
        assertFalse(session.consume(300));
    }

    @Test
    public void heartbeatAndClientCompletionCannotReplaceMissingServerValidation() {
        QuantumMigrationSession session = new QuantumMigrationSession(0);
        session.heartbeat(300);
        session.complete();
        assertFalse(session.consume(300));
        assertFalse(session.validate(300, true));
        assertFalse(session.consume(300));
    }

    @Test
    public void threeHundredValidTicksStillRequireExplicitCompletionIntent() {
        QuantumMigrationSession session = new QuantumMigrationSession(0);
        for (int tick = 1; tick <= 300; tick++) {
            session.heartbeat(tick);
            assertTrue(session.validate(tick, true));
        }
        assertFalse(session.consume(300));
        session.complete();
        assertTrue(session.consume(300));
    }

    @Test
    public void skippedValidationAtEndCannotBeReplacedByHeartbeatOrExtraElapsedTime() {
        QuantumMigrationSession session = readyAtTwoHundredNinetyNine();
        session.complete();
        session.heartbeat(301);
        assertFalse(session.validate(301, true));
        assertFalse(session.consume(301));
        session.heartbeat(302);
        assertFalse(session.validate(302, true));
        assertFalse(session.consume(302));
    }

    private static QuantumMigrationSession readyAtTwoHundredNinetyNine() {
        QuantumMigrationSession session = new QuantumMigrationSession(0);
        for (int tick = 1; tick <= 299; tick++) {
            session.heartbeat(tick);
            assertTrue(session.validate(tick, true));
        }
        return session;
    }
}
