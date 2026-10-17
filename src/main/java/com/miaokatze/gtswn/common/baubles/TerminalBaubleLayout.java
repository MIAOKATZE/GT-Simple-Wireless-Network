package com.miaokatze.gtswn.common.baubles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Display-only permutation: inventory identities and ordinary bauble coordinates remain untouched. */
public final class TerminalBaubleLayout {

    private TerminalBaubleLayout() {}

    public static int category(String type) {
        if ("gtswn_monitor".equals(type)) return 0;
        if ("gtswn_link".equals(type)) return 1;
        if ("gtswn_device".equals(type)) return 2;
        if ("gtswn_quantum".equals(type)) return 3;
        return -1;
    }

    /** For each container bauble ordinal, return the original cell ordinal used for its display. */
    public static int[] displayOrdinals(int[] categories, int columns, boolean oldLayout) {
        columns = Math.max(1, columns);
        int[] result = new int[categories.length];
        List<Integer> terminals = new ArrayList<>();
        for (int i = 0; i < result.length; i++) {
            result[i] = i;
            if (categories[i] >= 0 && categories[i] < 4) terminals.add(i);
        }
        List<Integer> cells = new ArrayList<>(terminals);
        final int width = columns;
        cells.sort(
            Comparator.comparingInt((Integer ordinal) -> oldLayout ? ordinal % 4 : ordinal / width)
                .thenComparingInt(ordinal -> oldLayout ? ordinal / 4 : -(ordinal % width)));
        terminals.sort(
            Comparator.comparingInt((Integer ordinal) -> categories[ordinal])
                .thenComparingInt(ordinal -> ordinal));
        for (int rank = 0; rank < terminals.size(); rank++) result[terminals.get(rank)] = cells.get(rank);
        return result;
    }

    /** Same scroll-row rounding and eight-row window used by ContainerPlayerExpanded. */
    public static int scrollRows(int totalSlots, int columns, float scroll, boolean canScroll) {
        if (!canScroll) return 0;
        int rows = (totalSlots + Math.max(1, columns) - 1) / Math.max(1, columns);
        return Math.max(0, (int) (Math.max(0, Math.min(1, scroll)) * (rows - 8) + 0.5F));
    }
}
