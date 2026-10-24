package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Stable round-robin choice; the caller retains the player-level throttle. */
final class DesktopDevicePolling {

    private DesktopDevicePolling() {}

    /** Factory and the open selector have priority; all cards of one source consume only one slot. */
    static Set<UUID> sources(UUID factory, UUID selection, Collection<UUID> visible, Predicate<UUID> held) {
        Set<UUID> selected = new LinkedHashSet<>();
        if (factory != null && held.test(factory)) selected.add(factory);
        if (selection != null && held.test(selection)) selected.add(selection);
        for (UUID source : visible) {
            if (selected.size() >= 4) break;
            if (source != null && held.test(source)) selected.add(source);
        }
        return selected;
    }

    static UUID next(Collection<UUID> sources, UUID previous, Predicate<UUID> due) {
        return next(sources, previous, false, due);
    }

    static UUID next(Collection<UUID> sources, UUID previous, boolean nativeViewer, Predicate<UUID> due) {
        if (nativeViewer) return null;
        List<UUID> ordered = new ArrayList<>(sources);
        int start = ordered.indexOf(previous) + 1;
        for (int offset = 0; offset < ordered.size(); offset++) {
            UUID id = ordered.get((start + offset) % ordered.size());
            if (due.test(id)) return id;
        }
        return null;
    }
}
