package com.miaokatze.gtswn.common.baubles;

import static org.junit.Assert.*;

import org.junit.Test;

public class TerminalBaubleSlotPolicyTest {

    private static final String[] TERMINAL = { "gtswn_monitor", "gtswn_link", "gtswn_device", "gtswn_quantum" };

    @Test
    public void terminalCategoriesAreMutuallyExclusive() {
        for (String slot : TERMINAL) for (String item : TERMINAL) assertEquals(
            item + " -> " + slot,
            Boolean.valueOf(item.equals(slot)),
            TerminalBaubleSlotPolicy.overrideAcceptance(item, slot));
    }

    @Test
    public void legacyUniversalAndEveryStandardTypeCannotEnterDedicatedSlots() {
        String[] ordinary = { null, "", "universal", "ring", "amulet", "belt", "head", "body", "charm", "cape",
            "shield", "quiver", "gauntlet", "earring", "wings", "unknown", "external", "gtswn_future" };
        for (String slot : TERMINAL) for (String item : ordinary)
            assertEquals(item + " -> " + slot, Boolean.FALSE, TerminalBaubleSlotPolicy.overrideAcceptance(item, slot));
    }

    @Test
    public void ordinarySlotsAndFuturePrefixedSlotsDelegateAllRulesToBaubles() {
        String[] ordinary = { null, "", "universal", "ring", "amulet", "belt", "external", "gtswn_future",
            "gtswn_monitor_extra" };
        for (String slot : ordinary) {
            for (String item : TERMINAL) assertNull(TerminalBaubleSlotPolicy.overrideAcceptance(item, slot));
            for (String item : ordinary) assertNull(TerminalBaubleSlotPolicy.overrideAcceptance(item, slot));
        }
    }

    @Test
    public void manualAssignmentsKeepExactOrderAndGainOnlyMissingCapacity() {
        String[] original = { "amulet", "ring", "ring", "belt", "external", "gtswn_quantum", "gtswn_monitor" };
        String[] merged = TerminalBaubleSlotPolicy.withRequiredSlots(original);
        assertArrayEquals(
            new String[] { "amulet", "ring", "ring", "belt", "external", "gtswn_quantum", "gtswn_monitor", "gtswn_link",
                "gtswn_device", "gtswn_quantum", "gtswn_quantum", "gtswn_quantum", "gtswn_quantum" },
            merged);
        assertArrayEquals(
            new String[] { "amulet", "ring", "ring", "belt", "external", "gtswn_quantum", "gtswn_monitor" },
            original);
        assertSame(merged, TerminalBaubleSlotPolicy.withRequiredSlots(merged));
    }

    @Test
    public void manualExtraCapacityAndFutureSlotNamesAreRetained() {
        String[] original = { "gtswn_monitor", "gtswn_monitor", "gtswn_link", "gtswn_device", "gtswn_quantum",
            "gtswn_quantum", "gtswn_quantum", "gtswn_quantum", "gtswn_quantum", "gtswn_quantum", "gtswn_future" };
        assertSame(original, TerminalBaubleSlotPolicy.withRequiredSlots(original));
        String[] ordinary = { "amulet", "ring", "belt" };
        String[] merged = TerminalBaubleSlotPolicy.withRequiredSlots(ordinary);
        assertEquals(ordinary.length + 8, merged.length);
        assertArrayEquals(ordinary, java.util.Arrays.copyOf(merged, ordinary.length));
    }
}
