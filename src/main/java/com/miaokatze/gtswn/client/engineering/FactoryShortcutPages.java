package com.miaokatze.gtswn.client.engineering;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import com.miaokatze.gtswn.client.engineering.FactoryShortcuts.Action;

/** One action catalog for terminal function menus and their corresponding binding pages. */
final class FactoryShortcutPages {

    private FactoryShortcutPages() {}

    static Action[] global() {
        return new Action[] { Action.TOGGLE, Action.CAMERA, Action.XRAY, Action.RETURN, Action.FOLLOW, Action.PIN,
            Action.CLEAR };
    }

    static Action[] functions(int slot) {
        if (slot == 0) return new Action[] { Action.DASHBOARD, Action.POWER, Action.MONITOR };
        if (slot == 2) return new Action[] { Action.MONITOR, Action.FILTER, Action.SCAN, Action.VISUAL, Action.AUDIT,
            Action.GRID_MC, Action.GRID_GT, Action.SELECT_AREA, Action.SCOPE, Action.ALERTS };
        return new Action[] { Action.MAP, Action.MONITOR, Action.OVERVIEW };
    }

    static Action[] review(int slot) {
        if (slot == 2) return new Action[] { Action.REFRESH, Action.BASELINE, Action.PIN, Action.CLEAR, Action.STOP,
            Action.OVERVIEW };
        return new Action[] { Action.REFRESH, Action.PIN, Action.CLEAR, Action.STOP, Action.OVERVIEW };
    }

    static Action terminal(int slot) {
        return Action.values()[Action.TERMINAL_0.ordinal() + slot];
    }

    static Action[] bindings(int slot) {
        Set<Action> actions = new LinkedHashSet<>();
        actions.add(terminal(slot));
        actions.addAll(Arrays.asList(functions(slot)));
        actions.addAll(Arrays.asList(review(slot)));
        return actions.toArray(new Action[0]);
    }
}
