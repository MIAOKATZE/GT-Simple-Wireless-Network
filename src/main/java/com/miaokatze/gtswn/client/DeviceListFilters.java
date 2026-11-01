package com.miaokatze.gtswn.client;

import java.util.Locale;
import java.util.UUID;

import com.miaokatze.gtswn.common.device.DeviceLinkSummary;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Additional local filters compose with the existing power/state predicates, without polling. */
public final class DeviceListFilters {

    public String query = "";
    public int kind, link, owner;
    public Integer dimension, tier;

    public boolean matches(Entry entry, UUID player) {
        String needle = query.trim()
            .toLowerCase(Locale.ROOT);
        if (!needle.isEmpty()) {
            String searchable = DeviceTerminalNames.plainName(entry) + "\n"
                + entry.name
                + "\n"
                + DeviceTerminalNames.registryName(entry)
                + "\n"
                + entry.key
                + "\n"
                + entry.x
                + "/"
                + entry.y
                + "/"
                + entry.z
                + "\n"
                + entry.x
                + " "
                + entry.y
                + " "
                + entry.z
                + "\n"
                + (entry.machineOwner == null ? "" : entry.machineOwner.toString());
            if (!searchable.toLowerCase(Locale.ROOT)
                .contains(needle)) return false;
        }
        if (kind != 0 && (kind == 4 ? entry.machineKind != 0 : entry.machineKind != kind)) return false;
        if (dimension != null && entry.dim != dimension || tier != null && entry.machineTier != tier) return false;
        if (owner == 1 && !java.util.Objects.equals(player, entry.machineOwner)
            || owner == 2 && (entry.machineOwner == null || java.util.Objects.equals(player, entry.machineOwner))
            || owner == 3 && entry.machineOwner != null) return false;
        DeviceLinkSummary summary = entry.linkSummary;
        if (link == 4) return summary.state == DeviceLinkSummary.UNKNOWN;
        if (link == 5) return summary.state == DeviceLinkSummary.NOT_APPLICABLE;
        if (link != 0 && summary.state != DeviceLinkSummary.KNOWN) return false;
        return link == 0 || link == 1 && summary.connected() == 0
            || link == 2 && summary.connected() > 0 && summary.connected() < summary.total()
            || link == 3 && summary.connected() == summary.total();
    }
}
