package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FactoryEntryHintStateTest {

    @Test
    public void openingBeforeFirstAccessoryCannotSkipItsDiscoveryHint() {
        FactoryEntryHintState state = new FactoryEntryHintState(false);
        assertFalse(state.opened(0));
        state.observe(null, 1, true);
        assertFalse(state.opened(2));
        state.observe("monitor:A", 3, false);
        assertTrue(state.firstPending());
        assertTrue(state.visible(600_000));
        assertTrue(state.opened(600_001));
        assertFalse(state.visible(600_001));
        assertFalse(state.firstPending());
    }

    @Test
    public void firstEquipmentPersistsUntilConfirmedOpenAndSurvivesTemporaryRemoval() {
        FactoryEntryHintState state = new FactoryEntryHintState(false);
        state.observe("monitor:A", 0, true);
        assertTrue(state.visible(600_000));
        state.observe(null, 600_001, false);
        assertFalse(state.visible(600_001));
        assertTrue(state.firstPending());
        state.observe("device:B", 600_002, false);
        assertTrue(state.visible(1_200_000));
        assertTrue(state.opened(1_200_001));
        assertFalse(state.visible(1_200_001));
        assertFalse(state.firstPending());
        assertFalse(state.opened(1_200_002));
    }

    @Test
    public void completedPlayerGetsBriefEquipmentAndBindingRemindersWithLongCooldown() {
        FactoryEntryHintState state = new FactoryEntryHintState(true);
        state.observe("monitor:A", 0, true);
        assertFalse(state.visible(0));
        state.observe(null, 1, false);
        state.observe("monitor:A", 2, false);
        assertTrue(state.visible(2));
        assertFalse(state.visible(6_002));
        state.observe("monitor:B", 20_000, false);
        assertFalse(state.visible(20_000));
        state.observe("monitor:C", 60_002, false);
        assertTrue(state.visible(60_002));
        assertFalse(state.firstPending());
    }

    @Test
    public void worldBaselineCannotRetriggerCompletedDiscoveryOrEraseFirstPending() {
        FactoryEntryHintState completed = new FactoryEntryHintState(true);
        completed.observe("monitor:A", 0, true);
        completed.observe(null, 100_000, true);
        completed.observe("device:B", 100_100, true);
        assertFalse(completed.visible(100_100));
        FactoryEntryHintState first = new FactoryEntryHintState(false);
        first.observe("monitor:A", 0, true);
        first.observe(null, 10, true);
        first.observe("device:B", 20, true);
        assertTrue(first.firstPending());
        assertTrue(first.visible(20));
    }

    @Test
    public void playerDiscoveryAndCooldownAreIndependent() {
        FactoryEntryHintState first = new FactoryEntryHintState(false);
        FactoryEntryHintState other = new FactoryEntryHintState(false);
        first.observe("monitor:A", 0, true);
        other.observe("monitor:B", 0, true);
        first.opened(100);
        assertFalse(first.visible(100));
        assertTrue(other.visible(100));
        other.opened(200);
        first.observe("device:C", 60_100, false);
        assertTrue(first.visible(60_100));
        assertFalse(other.visible(60_100));
    }
}
