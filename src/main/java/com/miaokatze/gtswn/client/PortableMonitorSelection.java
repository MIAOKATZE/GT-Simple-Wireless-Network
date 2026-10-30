package com.miaokatze.gtswn.client;

import java.util.List;
import java.util.UUID;

import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;

/** Type shortcuts select a bound physical screen, using its server-authored display tab. */
public final class PortableMonitorSelection {

    private PortableMonitorSelection() {}

    /** Preserve physical source identity across reordered/reduced binding snapshots. */
    public static int retainIndex(List<PacketSyncPortableMonitor.Panel> panels, int selected, UUID panelId) {
        if (panels.isEmpty()) return -1;
        if (panelId != null)
            for (int i = 0; i < panels.size(); i++) if (panelId.equals(panels.get(i).binding.panelId)) return i;
        return Math.max(0, Math.min(selected, panels.size() - 1));
    }

    /** Bind controls to the displayed physical source, while permitting harmless same-source data refreshes. */
    public static boolean currentView(PacketSyncPortableMonitor displayed, UUID panelId,
        PacketSyncPortableMonitor current, PacketSyncPortableMonitor.Panel panel) {
        if (displayed == null || current == null
            || displayed.terminalId == null
            || !displayed.terminalId.equals(current.terminalId)
            || panelId == null
            || panel == null
            || !panelId.equals(panel.binding.panelId)) return false;
        if (!current.panels.contains(panel)) return false;
        for (PacketSyncPortableMonitor.Panel old : displayed.panels) {
            if (panelId.equals(old.binding.panelId))
                return old.binding.dimension == panel.binding.dimension && old.binding.x == panel.binding.x
                    && old.binding.y == panel.binding.y
                    && old.binding.z == panel.binding.z;
        }
        return false;
    }

    public static int sourceIndex(List<PacketSyncPortableMonitor.Panel> panels, int selected, int tab) {
        if (selected >= 0 && selected < panels.size() && matches(panels.get(selected), tab)) return selected;
        for (int i = 0; i < panels.size(); i++) {
            int index = Math.floorMod(selected + 1 + i, panels.size());
            if (matches(panels.get(index), tab)) return index;
        }
        return -1;
    }

    private static boolean matches(PacketSyncPortableMonitor.Panel panel, int tab) {
        return panel.state == 0 && panel.tab == tab;
    }
}
