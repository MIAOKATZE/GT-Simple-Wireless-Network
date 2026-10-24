package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.junit.Test;

public class FactoryDashboardHintStateTest {

    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);

    @Test
    public void closingAndReopeningDoesNotConsumeDiscovery() {
        FactoryDashboardHintState state = new FactoryDashboardHintState();
        assertFalse(state.visible());
        assertFalse(state.acknowledge(A));
        state.open(A, false);
        assertTrue(state.visible());
        state.close();
        state.open(A, false);
        assertTrue(state.visible());
    }

    @Test
    public void onlySuccessfulOpeningForCurrentPlayerCompletesDiscovery() {
        FactoryDashboardHintState state = new FactoryDashboardHintState();
        state.open(A, false);
        assertFalse(state.acknowledge(B));
        assertTrue(state.visible());
        assertTrue(state.acknowledge(A));
        state.open(A, false);
        assertFalse(state.visible());
        assertFalse(state.acknowledge(A));
    }

    @Test
    public void completionSurvivesReconnectAndIsolatesPlayers() {
        FactoryDashboardHintState state = new FactoryDashboardHintState();
        state.open(A, true);
        assertFalse(state.visible());
        state.close();
        state.open(A, true);
        assertFalse(state.visible());
        state.open(B, false);
        assertTrue(state.visible());
        state.open(A, true);
        assertFalse(state.visible());
    }

    @Test
    public void samePlayerAcrossWorldDimensionAndTerminalChangesCannotRestartDiscovery() {
        FactoryDashboardHintState state = new FactoryDashboardHintState();
        state.open(A, false);
        assertTrue(state.acknowledge(A));
        // Source changes have no key input: the same UUID remains completed even before preferences reload.
        state.open(new UUID(0, 1), false);
        assertFalse(state.visible());
        // A dimension/world transition may close the host; its persisted player completion is restored.
        state.close();
        state.open(new UUID(0, 1), true);
        assertFalse(state.visible());
    }
}
