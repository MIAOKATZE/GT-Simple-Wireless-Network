package com.miaokatze.gtswn.common.baubles;

/** Exact slot boundary shared by the optional Baubles mixin and its regressions. */
public final class TerminalBaubleSlotPolicy {

    private TerminalBaubleSlotPolicy() {}

    /** Null delegates to Baubles, preserving its normal types and configuration overrides. */
    public static Boolean overrideAcceptance(String itemType, String slotType) {
        if ("gtswn_monitor".equals(slotType) || "gtswn_link".equals(slotType)
            || "gtswn_device".equals(slotType)
            || "gtswn_quantum".equals(slotType)) {
            return slotType.equals(itemType);
        }
        return null;
    }

    /** Hidden unknown cells still occupy inventory indices, although they have no visible container slot. */
    public static int inventoryIndex(int visibleOrdinal, String[] assignments, boolean showUnusedSlots) {
        if (showUnusedSlots || visibleOrdinal < 0) return visibleOrdinal;
        int visible = 0;
        for (int index = 0; index < assignments.length; index++) {
            if ("unknown".equals(assignments[index])) continue;
            if (visible++ == visibleOrdinal) return index;
        }
        return visibleOrdinal;
    }

    /** Preserve manual slot order and append only missing terminal capacity. */
    public static String[] withRequiredSlots(String[] assignments) {
        String[] required = { "gtswn_monitor", "gtswn_link", "gtswn_device", "gtswn_quantum" };
        java.util.List<String> merged = new java.util.ArrayList<>(java.util.Arrays.asList(assignments));
        for (String type : required) {
            int minimum = "gtswn_quantum".equals(type) ? 5 : 1;
            int existing = 0;
            for (String slot : assignments) if (type.equals(slot)) existing++;
            for (int count = existing; count < minimum; count++) merged.add(type);
        }
        return merged.size() == assignments.length ? assignments : merged.toArray(new String[0]);
    }
}
