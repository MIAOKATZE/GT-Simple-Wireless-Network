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
    private static List<String> screenIds = Collections.emptyList();

    private WidgetResources() {}

    public static void update(UUID currentSource, List<Panel> currentPanels) {
        List<String> identities = new ArrayList<>();
        if (currentPanels != null) for (Panel panel : currentPanels)
            if (panel.binding != null) identities.add(panel.binding.panelId.toString());
        update(currentSource, currentPanels, identities);
    }

    public static void update(UUID currentSource, List<Panel> currentPanels, List<String> identities) {
        source = currentSource;
        panels = currentPanels == null ? Collections.<Panel>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(currentPanels));
        screenIds = identities == null ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(identities));
    }

    public static List<ScreenBinding> screenChoices() {
        List<ScreenBinding> result = new ArrayList<>();
        if (source != null) for (String id : screenIds) {
            try {
                result.add(new ScreenBinding(source, UUID.fromString(id)));
            } catch (IllegalArgumentException invalid) {}
        }
        return Collections.unmodifiableList(result);
    }

    /** All received terminal screen summaries/details; callers still check readScreen before using values. */
    public static List<Panel> availableScreens() {
        return Collections.unmodifiableList(panels);
    }

    /** The only unambiguous automatic source is exactly one current mount, even before its first receipt. */
    public static ScreenBinding defaultScreen() {
        List<ScreenBinding> choices = screenChoices();
        return choices.size() == 1 ? choices.get(0) : null;
    }

    public static String screenLabel(ScreenBinding binding) {
        if (binding == null) return "";
        if (binding.source.equals(source)) for (Panel panel : panels)
            if (panel.binding != null && binding.screen.equals(panel.binding.panelId)) return panel.binding.label();
        return binding.source.toString()
            .substring(0, 8) + " / "
            + binding.screen.toString()
                .substring(0, 8);
    }

    public static ScreenReading readScreen(ScreenBinding binding) {
        return readScreenCached(
            binding == null ? defaultScreen() : binding,
            source,
            panels,
            screenIds,
            PortableSuperDashboard::available);
    }

    static ScreenReading readScreenCached(ScreenBinding binding, UUID currentSource, List<Panel> values,
        List<String> identities, Predicate<Panel> available) {
        if (binding == null) return new ScreenReading(null, "select");
        if (!binding.source.equals(currentSource)) return new ScreenReading(null, "source");
        if (!identities.contains(binding.screen.toString())) return new ScreenReading(null, "binding");
        for (Panel panel : values) {
            if (panel.binding == null || !binding.screen.equals(panel.binding.panelId)) continue;
            return available.test(panel) ? new ScreenReading(panel, "ok")
                : new ScreenReading(null, panel.state == 0 && !panel.detail ? "waiting" : "unavailable");
        }
        return new ScreenReading(null, "waiting");
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

    public static final class ScreenReading {

        public final Panel panel;
        /** ok / select / source / binding / waiting / unavailable. */
        public final String reason;

        private ScreenReading(Panel panel, String reason) {
            this.panel = panel;
            this.reason = reason;
        }
    }
}
