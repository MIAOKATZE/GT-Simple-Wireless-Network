package com.miaokatze.gtswn.client.engineering.desktop;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Compatibility presentation for retired recipe fields in saved dashboards. */
public final class DesktopDeviceFields {

    private DesktopDeviceFields() {}

    public static String recipeSide(Entry entry, boolean input) {
        return "—";
    }

    public static String recipeLine(Entry entry) {
        return "—";
    }
}
