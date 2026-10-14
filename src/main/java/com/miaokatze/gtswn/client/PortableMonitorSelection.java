package com.miaokatze.gtswn.client;

import java.util.List;

import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;

/** Type shortcuts select a bound physical screen, using its server-authored display tab. */
public final class PortableMonitorSelection {

    private PortableMonitorSelection() {}

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
