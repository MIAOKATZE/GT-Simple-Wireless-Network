package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.FactoryShortcuts.Action;

public class FactoryShortcutPagesTest {

    @Test
    public void globalSettingsOnlyBindFactoryAndCameraControls() {
        Set<Action> actions = set(FactoryShortcutPages.global());
        assertEquals(
            EnumSet
                .of(Action.TOGGLE, Action.CAMERA, Action.XRAY, Action.RETURN, Action.FOLLOW, Action.PIN, Action.CLEAR),
            actions);
    }

    @Test
    public void terminalBindingsCoverTheirOwnOpenFunctionsAndReviewWithoutDuplicates() {
        Set<Action> reachable = set(FactoryShortcutPages.global());
        for (int slot = 0; slot < 8; slot++) {
            Action[] page = FactoryShortcutPages.bindings(slot);
            Set<Action> actions = set(page);
            assertEquals(page.length, actions.size());
            assertTrue(actions.contains(FactoryShortcutPages.terminal(slot)));
            assertTrue(actions.containsAll(set(FactoryShortcutPages.functions(slot))));
            assertTrue(actions.containsAll(set(FactoryShortcutPages.review(slot))));
            for (int other = 0; other < 8; other++) {
                if (other != slot) assertFalse(actions.contains(FactoryShortcutPages.terminal(other)));
            }
            reachable.addAll(actions);
        }
        assertEquals(EnumSet.allOf(Action.class), reachable);
    }

    @Test
    public void portableAndNodeTerminalsDoNotOfferDeviceOnlyBindings() {
        Set<Action> deviceOnly = EnumSet.of(
            Action.SCAN,
            Action.VISUAL,
            Action.AUDIT,
            Action.GRID_MC,
            Action.GRID_GT,
            Action.SELECT_AREA,
            Action.SCOPE,
            Action.FILTER,
            Action.ALERTS,
            Action.BASELINE);
        for (int slot = 0; slot < 8; slot++) {
            Set<Action> actions = set(FactoryShortcutPages.bindings(slot));
            if (slot == 2) assertTrue(actions.containsAll(deviceOnly));
            else {
                actions.retainAll(deviceOnly);
                assertTrue(actions.isEmpty());
            }
        }
        assertTrue(set(FactoryShortcutPages.bindings(0)).contains(Action.DASHBOARD));
        assertTrue(set(FactoryShortcutPages.bindings(1)).contains(Action.MAP));
    }

    private static Set<Action> set(Action[] actions) {
        return EnumSet.copyOf(Arrays.asList(actions));
    }
}
