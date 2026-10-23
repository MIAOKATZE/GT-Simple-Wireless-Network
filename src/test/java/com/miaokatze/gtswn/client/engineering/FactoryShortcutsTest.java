package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.util.EnumMap;
import java.util.Map;

import org.junit.Test;
import org.lwjgl.input.Keyboard;

import com.miaokatze.gtswn.client.engineering.FactoryShortcuts.Action;

public class FactoryShortcutsTest {

    @Test
    public void factoryDefaultsExcludeTheGlobalToggleAndLeaveOptionalActionsUnbound() {
        Map<Action, Integer> bindings = FactoryShortcuts.Policy.defaults();
        assertFalse(bindings.containsKey(Action.TOGGLE));
        assertEquals(Integer.valueOf(Keyboard.KEY_M), bindings.get(Action.FOLLOW));
        assertEquals(Integer.valueOf(Keyboard.KEY_X), bindings.get(Action.XRAY));
        assertEquals(Integer.valueOf(Keyboard.KEY_NONE), bindings.get(Action.TERMINAL_0));
        assertEquals(Integer.valueOf(Keyboard.KEY_NONE), bindings.get(Action.CAMERA));
    }

    @Test
    public void xrayUsesTheSameRebindingAndConflictPolicyAsOtherFactoryKeys() {
        Map<Action, Integer> bindings = defaults();
        assertEquals(Action.XRAY, resolve(Keyboard.KEY_X, Keyboard.KEY_C, bindings));
        bindings.put(Action.XRAY, Keyboard.KEY_Z);
        assertNull(resolve(Keyboard.KEY_X, Keyboard.KEY_C, bindings));
        assertEquals(Action.XRAY, resolve(Keyboard.KEY_Z, Keyboard.KEY_C, bindings));
        assertNull(resolve(Keyboard.KEY_Z, Keyboard.KEY_Z, bindings));
        bindings.put(Action.MONITOR, Keyboard.KEY_Z);
        assertEquals(Action.MONITOR, resolve(Keyboard.KEY_Z, Keyboard.KEY_C, bindings));
    }

    @Test
    public void rebindingReplacesTheDefaultInsteadOfLeavingAHardCodedShortcut() {
        Map<Action, Integer> bindings = defaults();
        bindings.put(Action.FOLLOW, Keyboard.KEY_G);
        assertNull(resolve(Keyboard.KEY_M, Keyboard.KEY_C, bindings));
        assertEquals(Action.FOLLOW, resolve(Keyboard.KEY_G, Keyboard.KEY_C, bindings));
        bindings.put(Action.FOLLOW, Keyboard.KEY_NONE);
        assertNull(resolve(Keyboard.KEY_G, Keyboard.KEY_C, bindings));
        assertNull(resolve(Keyboard.KEY_NONE, Keyboard.KEY_C, bindings));
    }

    @Test
    public void wheelAndEscapeKeepTheirOwnInputEvenWhenAnotherActionUsesTheirKey() {
        Map<Action, Integer> bindings = defaults();
        bindings.put(Action.FOLLOW, Keyboard.KEY_C);
        assertNull(resolve(Keyboard.KEY_C, Keyboard.KEY_C, bindings));
        assertEquals(Action.FOLLOW, resolve(Keyboard.KEY_C, Keyboard.KEY_V, bindings));
        bindings.put(Action.TOGGLE, Keyboard.KEY_ESCAPE);
        assertNull(resolve(Keyboard.KEY_ESCAPE, Keyboard.KEY_V, bindings));
        bindings.put(Action.CAMERA, -99);
        assertNull(resolve(-99, Keyboard.KEY_V, bindings));
    }

    @Test
    public void toggleOnAMovementKeyTakesPriorityAndNoLongerListensToF12() {
        Map<Action, Integer> bindings = defaults();
        bindings.put(Action.TOGGLE, Keyboard.KEY_W);
        bindings.put(Action.FOLLOW, Keyboard.KEY_W);
        assertEquals(Action.TOGGLE, resolve(Keyboard.KEY_W, Keyboard.KEY_C, bindings));
        assertNull(resolve(Keyboard.KEY_F12, Keyboard.KEY_C, bindings));
    }

    private static Map<Action, Integer> defaults() {
        Map<Action, Integer> bindings = new EnumMap<>(Action.class);
        for (Action action : Action.values()) bindings.put(action, action.defaultKey);
        return bindings;
    }

    private static Action resolve(int code, int wheel, Map<Action, Integer> bindings) {
        return FactoryShortcuts.Policy.action(code, wheel, bindings::get);
    }
}
