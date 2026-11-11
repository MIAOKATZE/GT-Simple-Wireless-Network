// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Preserve native item details while keeping the device's status before its description and brand last. */
public final class DeviceTooltipOrder {

    private DeviceTooltipOrder() {}

    /** The first paragraph is the machine summary; replace only a complete, owned description. */
    public static boolean matrixPage(List<String> lines, List<String> own, String footer, boolean expanded) {
        int start = Collections.indexOfSubList(lines, own);
        int summary = own.indexOf("");
        if (start < 0 || summary < 1 || !footer.equals(own.get(own.size() - 1))) return false;
        if (!expanded) lines.subList(start + summary, start + own.size() - 1)
            .clear();
        return true;
    }

    public static boolean hasSections(List<String> lines, String state, String description, String controls,
        String footer) {
        int stateIndex = lines.indexOf(state);
        int descriptionIndex = lines.indexOf(description);
        int controlsIndex = lines.indexOf(controls);
        int footerIndex = lines.indexOf(footer);
        // Exact, ordered markers restrict this operation to our complete device tooltips.
        return stateIndex >= 0 && descriptionIndex > stateIndex
            && controlsIndex > descriptionIndex
            && footerIndex > controlsIndex
            && footerIndex == lines.lastIndexOf(footer);
    }

    public static void collapse(List<String> lines, String description, String controls, String footer,
        List<String> parameters) {
        int first = lines.indexOf(description) + 1;
        List<String> details = lines.subList(Math.min(first + 1, lines.indexOf(controls)), lines.indexOf(controls));
        details.removeIf(line -> !parameters.contains(line));
        lines.subList(lines.indexOf(controls), lines.indexOf(footer))
            .clear();
    }

    public static void normalize(List<String> lines, String state, String description, String controls, String footer) {
        if (!hasSections(lines, state, description, controls, footer)) return;
        int stateIndex = lines.indexOf(state), descriptionIndex = lines.indexOf(description),
            footerIndex = lines.indexOf(footer);

        List<String> nativeDetails = new ArrayList<>();
        for (int i = footerIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isEmpty()) nativeDetails.add(line);
        }
        lines.subList(footerIndex, lines.size())
            .clear();
        int insertionIndex = descriptionIndex;
        while (insertionIndex > stateIndex + 1 && lines.get(insertionIndex - 1)
            .isEmpty()) insertionIndex--;
        lines.addAll(insertionIndex, nativeDetails);
        if (!lines.get(lines.size() - 1)
            .isEmpty()) lines.add("");
        lines.add(footer);

        // Native handlers can add empty padding; retain just one blank between our sections.
        for (int i = lines.size() - 2; i > stateIndex; i--) {
            if (lines.get(i)
                .isEmpty()
                && lines.get(i - 1)
                    .isEmpty())
                lines.remove(i);
        }
    }
}
