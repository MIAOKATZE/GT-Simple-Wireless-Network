package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Local editor history, independent of the explicit disk-save checkpoint and viewport preferences. */
public final class DesktopHistory {

    public static final int LIMIT = 64;

    private final Deque<DesktopLayout> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private DesktopLayout current;
    private Properties signature;
    private Object group;

    public DesktopHistory(DesktopLayout initial) {
        reset(initial);
    }

    public void reset(DesktopLayout initial) {
        undo.clear();
        redo.clear();
        current = initial.copy();
        signature = DesktopStore.encode(current);
        endGroup();
    }

    /** Null commits a separate action; a stable input token coalesces one continuous text edit. */
    public boolean record(DesktopLayout layout, Object inputGroup) {
        Properties next = DesktopStore.encode(layout);
        if (signature.equals(next)) return false;
        if (inputGroup == null || inputGroup != group) {
            undo.addLast(current);
            if (undo.size() > LIMIT) undo.removeFirst();
        }
        redo.clear();
        current = layout.copy();
        signature = next;
        group = inputGroup;
        return true;
    }

    public void endGroup() {
        group = null;
    }

    public DesktopLayout undo() {
        return restore(undo, redo);
    }

    public DesktopLayout redo() {
        return restore(redo, undo);
    }

    private DesktopLayout restore(Deque<DesktopLayout> from, Deque<DesktopLayout> to) {
        endGroup();
        if (from.isEmpty()) return null;
        to.addLast(current);
        current = from.removeLast();
        signature = DesktopStore.encode(current);
        // Layout containers are mutable; WidgetSpec, its field list and all bindings are immutable values.
        return current.copy();
    }

    /**
     * Initialization is not a user command. Carry only its resolved pending bindings across existing snapshots;
     * explicit clears have pending=false and must never be silently rebound by history or by the next tick.
     */
    public void acceptAutomatic(DesktopLayout before, DesktopLayout after) {
        Map<String, WidgetSpec> resolved = new HashMap<>();
        for (WidgetSpec old : before.widgets()) {
            WidgetSpec next = after.find(old.id);
            if (next != null && (old.autoDevicePending && !next.autoDevicePending
                || old.autoResourcePending && !next.autoResourcePending)) resolved.put(old.id, next);
        }
        rebase(current, resolved);
        for (DesktopLayout snapshot : undo) rebase(snapshot, resolved);
        for (DesktopLayout snapshot : redo) rebase(snapshot, resolved);
        signature = DesktopStore.encode(current);
    }

    private static void rebase(DesktopLayout snapshot, Map<String, WidgetSpec> resolved) {
        for (WidgetSpec old : snapshot.widgets()) {
            WidgetSpec next = resolved.get(old.id);
            if (next == null) continue;
            WidgetSpec patched = old;
            if (old.autoDevicePending && !next.autoDevicePending) patched = patched.boundDevice(next.device);
            if (old.autoResourcePending && !next.autoResourcePending) {
                patched = patched.boundScreen(next.screen);
                if (next.resource != null) patched = patched.boundResource(next.resource);
            }
            if (patched != old) snapshot.replace(patched);
        }
    }
}
