package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Stable round-robin choice; the caller retains the player-level throttle. */
final class DesktopDevicePolling {

    private DesktopDevicePolling() {}

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
