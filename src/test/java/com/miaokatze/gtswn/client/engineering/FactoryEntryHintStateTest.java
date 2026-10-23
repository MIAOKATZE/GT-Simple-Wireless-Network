package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FactoryEntryHintStateTest {

    @Test
    public void existingAccessoryImmediatelyQualifiesAnUnconfirmedPlayer() {
        FactoryEntryHintState state = new FactoryEntryHintState(false);
        assertFalse(state.visible());
        state.observe("monitor:A");
        assertTrue(state.firstPending());
        assertTrue(state.visible());
        state.observe("monitor:A");
        assertTrue(state.visible());
    }

    @Test
    public void bindingWithoutCurrentAccessoryCannotConsumeDiscovery() {
        FactoryEntryHintState state = new FactoryEntryHintState(false);
        assertFalse(state.confirmBinding());
        state.observe(null);
        assertFalse(state.confirmBinding());
        state.observe("monitor:A");
        state.observe(null);
        assertFalse(state.confirmBinding());
        assertTrue(state.firstPending());
        state.observe("device:B");
        assertTrue(state.visible());
        assertTrue(state.confirmBinding());
        assertFalse(state.visible());
    }

    @Test
    public void pendingDiscoverySurvivesRemovalAndWorldEquipmentResync() {
        FactoryEntryHintState state = new FactoryEntryHintState(false);
        state.observe("monitor:A");
        state.observe(null);
        assertFalse(state.visible());
        assertTrue(state.firstPending());
        state.observe("device:B");
        assertTrue(state.visible());
        assertTrue(state.confirmBinding());
        assertFalse(state.firstPending());
        assertFalse(state.confirmBinding());
        state.observe(null);
        state.observe("monitor:C");
        assertFalse(state.visible());
    }

    @Test
    public void persistedConfirmationNeverRetriggersForEquipmentOrBindingChanges() {
        FactoryEntryHintState state = new FactoryEntryHintState(true);
        state.observe("monitor:A");
        assertFalse(state.visible());
        state.observe(null);
        state.observe("monitor:A");
        assertFalse(state.visible());
        state.observe("monitor:B");
        assertFalse(state.visible());
        assertFalse(state.firstPending());
        assertFalse(state.confirmBinding());
        FactoryEntryHintState reconnected = new FactoryEntryHintState(true);
        reconnected.observe("device:C");
        assertFalse(reconnected.visible());
    }

    @Test
    public void confirmingOnePlayerDoesNotConsumeAnotherPlayersDiscovery() {
        FactoryEntryHintState first = new FactoryEntryHintState(false);
        FactoryEntryHintState other = new FactoryEntryHintState(false);
        first.observe("monitor:A");
        other.observe("monitor:B");
        assertTrue(first.confirmBinding());
        assertFalse(first.visible());
        assertTrue(other.visible());
        assertTrue(other.confirmBinding());
        assertFalse(other.visible());
    }
}
