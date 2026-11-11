// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Terminal-owned labels share the alias text rules and have a bounded edit/snapshot payload. */
public final class DeviceTerminalTagRules {

    public static final int MAX_TAGS = 8;
    public static final int MAX_TAG_LENGTH = 24;
    public static final int MAX_INPUT_LENGTH = MAX_TAGS * MAX_TAG_LENGTH + (MAX_TAGS - 1) * 2;

    private DeviceTerminalTagRules() {}

    /** Commas separate labels; spaces are trimmed and duplicate labels keep their first spelling. */
    public static String normalize(String input) {
        if (input == null || input.length() > MAX_INPUT_LENGTH) return null;
        List<String> labels = new ArrayList<>();
        for (String part : input.replace('，', ',')
            .split(",", -1)) {
            String label = DeviceTerminalNameRules.normalize(part);
            if (label == null || label.length() > MAX_TAG_LENGTH) return null;
            if (label.isEmpty()) continue;
            boolean duplicate = false;
            for (String previous : labels) if (previous.equalsIgnoreCase(label)) {
                duplicate = true;
                break;
            }
            if (!duplicate) labels.add(label);
            if (labels.size() > MAX_TAGS) return null;
        }
        return String.join(", ", labels);
    }

    public static List<String> labels(String normalized) {
        return normalized.isEmpty() ? Collections.emptyList() : java.util.Arrays.asList(normalized.split(", "));
    }

    public static boolean contains(String normalized, String label) {
        for (String value : labels(normalized)) if (value.equalsIgnoreCase(label)) return true;
        return false;
    }
}
