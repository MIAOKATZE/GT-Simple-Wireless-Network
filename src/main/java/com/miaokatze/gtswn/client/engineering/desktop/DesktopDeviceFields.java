package com.miaokatze.gtswn.client.engineering.desktop;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Shared presentation of the existing Entry recipe strings; never starts a read demand. */
public final class DesktopDeviceFields {

    private DesktopDeviceFields() {}

    public static String recipeSide(Entry entry, boolean input) {
        String text = entry == null || entry.state == 3 ? "" : input ? entry.recipeIn : entry.recipeOut;
        return text == null || text.trim()
            .isEmpty() ? "—"
                : text.trim()
                    .replace('\n', ' ')
                    .replace('\r', ' ');
    }

    public static String recipeLine(Entry entry) {
        String input = recipeSide(entry, true), output = recipeSide(entry, false);
        return "—".equals(input) && "—".equals(output) ? "—" : input + " → " + output;
    }
}
