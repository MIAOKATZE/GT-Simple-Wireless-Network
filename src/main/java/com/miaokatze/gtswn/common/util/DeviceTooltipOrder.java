package com.miaokatze.gtswn.common.util;

import java.util.ArrayList;
import java.util.List;

/** Preserve native item details while keeping the device's status before its description and brand last. */
public final class DeviceTooltipOrder {

    private DeviceTooltipOrder() {}

    public static void normalize(List<String> lines, String state, String description, String controls, String footer) {
        int stateIndex = lines.indexOf(state);
        int descriptionIndex = lines.indexOf(description);
        int controlsIndex = lines.indexOf(controls);
        int footerIndex = lines.indexOf(footer);
        // Exact, ordered markers restrict this operation to our complete device tooltips.
        if (stateIndex < 0 || descriptionIndex <= stateIndex
            || controlsIndex <= descriptionIndex
            || footerIndex <= controlsIndex
            || footerIndex != lines.lastIndexOf(footer)) return;

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
