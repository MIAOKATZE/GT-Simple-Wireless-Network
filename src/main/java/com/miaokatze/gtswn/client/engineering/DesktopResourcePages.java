package com.miaokatze.gtswn.client.engineering;

import java.util.HashMap;
import java.util.Map;

/** Per-card row anchors; measuring one viewport cannot reset another card's page. */
final class DesktopResourcePages {

    private final Map<String, Integer> anchors = new HashMap<>();

    int start(String id, int count, int visible) {
        int rows = Math.max(1, visible);
        int last = Math.max(0, (count - 1) / rows * rows);
        return Math.min(last, Math.max(0, anchors.getOrDefault(id, 0)) / rows * rows);
    }

    void next(String id, int count, int visible) {
        int next = start(id, count, visible) + Math.max(1, visible);
        anchors.put(id, next >= count ? 0 : next);
    }

    void clear() {
        anchors.clear();
    }
}
