// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client.engineering;

import java.util.Locale;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Local, bounded text filter. Never changes server sampling or authorization. */
final class FactoryTextSearch {

    String value = "";
    boolean focused;

    boolean matches(String text) {
        String haystack = normalize(text);
        for (String term : normalize(value).trim()
            .split("\\s+")) if (!haystack.contains(term)) return false;
        return true;
    }

    boolean matchesDevice(Entry entry, String source, boolean fresh) {
        return matches(
            entry.name + " "
                + entry.displayName
                + " "
                + entry.key
                + " "
                + entry.dim
                + " "
                + entry.x
                + "/"
                + entry.y
                + "/"
                + entry.z
                + " "
                + source
                + " "
                + FactoryMachineFilter.status(entry, fresh));
    }

    private static String normalize(String value) {
        return value == null ? ""
            : value.toLowerCase(Locale.ROOT)
                .replace(',', ' ')
                .replace('/', ' ');
    }

    void character(char character) {
        if (character >= 32 && character != 127 && value.length() < 96) value += character;
    }

    void backspace() {
        if (!value.isEmpty()) value = value.substring(0, value.length() - 1);
    }
}
