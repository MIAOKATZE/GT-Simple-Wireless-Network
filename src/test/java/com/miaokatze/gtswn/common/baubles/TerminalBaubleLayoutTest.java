package com.miaokatze.gtswn.common.baubles;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Comparator;

import org.junit.Test;

public class TerminalBaubleLayoutTest {

    @Test
    public void baselineEighteenOldSlotsAndEightTerminalsReadInCanonicalOrder() {
        int[] categories = new int[26];
        Arrays.fill(categories, -1);
        for (int i = 18; i < 26; i++) categories[i] = Math.min(i - 18, 3);
        int[] display = TerminalBaubleLayout.displayOrdinals(categories, 4, false);
        for (int i = 0; i < 18; i++) assertEquals(i, display[i]);
        assertArrayEquals(new int[] { 19, 18, 23, 22, 21, 20, 25, 24 }, Arrays.copyOfRange(display, 18, 26));
        assertOrderedAndBijective(categories, display, 4, false);
    }

    @Test
    public void mixedManualSlotsAndExtraTerminalsRemainStableAcrossEverySupportedLayout() {
        int[] categories = { -1, 3, -1, 2, 3, 0, -1, 1, 3, 0, 3, -1, 2, 3, 3, -1 };
        for (boolean old : new boolean[] { false, true })
            for (int columns = 1; columns <= 6; columns++) assertOrderedAndBijective(
                categories,
                TerminalBaubleLayout.displayOrdinals(categories, columns, old),
                columns,
                old);
        assertEquals(-1, TerminalBaubleLayout.category("gtswn_monitor_extra"));
        assertEquals(-1, TerminalBaubleLayout.category("universal"));
    }

    @Test
    public void scrollingRoundsLikeBaublesWithoutChangingTheCoordinatePermutation() {
        assertEquals(0, TerminalBaubleLayout.scrollRows(40, 4, 0, true));
        assertEquals(1, TerminalBaubleLayout.scrollRows(40, 4, .5F, true));
        assertEquals(2, TerminalBaubleLayout.scrollRows(40, 4, 1, true));
        assertEquals(0, TerminalBaubleLayout.scrollRows(40, 4, 1, false));
        assertEquals(0, TerminalBaubleLayout.scrollRows(26, 4, 1, false));
        assertEquals(2, TerminalBaubleLayout.scrollRows(40, 4, 2, true));
    }

    private static void assertOrderedAndBijective(int[] categories, int[] display, int columns, boolean old) {
        boolean[] used = new boolean[display.length];
        for (int i = 0; i < display.length; i++) {
            assertFalse(used[display[i]]);
            used[display[i]] = true;
            if (categories[i] < 0) assertEquals(i, display[i]);
            else assertTrue(categories[display[i]] >= 0);
        }
        Integer[] slots = new Integer[display.length];
        for (int i = 0; i < slots.length; i++) slots[i] = i;
        Arrays.sort(
            slots,
            Comparator.comparingInt((Integer index) -> old ? display[index] % 4 : display[index] / columns)
                .thenComparingInt(index -> old ? display[index] / 4 : -(display[index] % columns)));
        int previousCategory = -1, previousIndex = -1;
        for (int slot : slots) if (categories[slot] >= 0) {
            assertTrue(categories[slot] >= previousCategory);
            if (categories[slot] == previousCategory) assertTrue(slot > previousIndex);
            previousCategory = categories[slot];
            previousIndex = slot;
        }
    }
}
