package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Row;

/** Shared authorized screen projection. This class never polls, requests or decodes icons. */
public final class WidgetResources {

    private static UUID source;
    private static List<Panel> panels = Collections.emptyList();

    private WidgetResources() {}

    public static void update(UUID currentSource, List<Panel> currentPanels) {
        source = currentSource;
        panels = currentPanels == null ? Collections.<Panel>emptyList() : currentPanels;
    }

    public static List<ResourceBinding> choices() {
        List<ResourceBinding> result = new ArrayList<>();
        if (source != null) for (Panel panel : panels) {
            if (!PortableSuperDashboard.available(panel) || !panel.aeConnected) continue;
            for (Row row : panel.rows) {
                ResourceBinding binding = binding(panel, row);
                if (binding != null) result.add(binding);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static ResourceBinding binding(Panel panel, Row row) {
        if (source == null || panel == null || panel.binding == null || row == null) return null;
        try {
            return new ResourceBinding(source, panel.binding.panelId, row.kind, row.key, row.name);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public static Reading read(ResourceBinding binding) {
        return readCached(binding, source, panels, PortableSuperDashboard::available);
    }

    static Reading readCached(ResourceBinding binding, UUID source, List<Panel> panels, Predicate<Panel> available) {
        if (binding == null || !binding.source.equals(source)) return new Reading(null, null, "source");
        for (Panel panel : panels) {
            if (panel.binding == null || !binding.screen.equals(panel.binding.panelId)) continue;
            if (!available.test(panel)) return new Reading(panel, null, "unavailable");
            if (!panel.aeConnected) return new Reading(panel, null, "offline");
            for (Row row : panel.rows) if (binding.matches(row.kind, row.key)) return new Reading(panel, row, "ok");
            return new Reading(panel, null, "missing");
        }
        return new Reading(null, null, "unavailable");
    }

    /** An unmonitored row or a cold zero is unknown, not evidence for zero production. */
    public static boolean rateKnown(Panel panel, Row row) {
        return panel != null && row != null
            && row.monitored
            && Double.isFinite(row.rate)
            && (row.rate != 0 || row.key.equals(panel.chartKey) && panel.render != null && panel.render.ae.size() >= 2);
    }

    public static final class Reading {

        public final Panel panel;
        public final Row row;
        public final String reason;

        private Reading(Panel panel, Row row, String reason) {
            this.panel = panel;
            this.row = row;
            this.reason = reason;
        }
    }
}
