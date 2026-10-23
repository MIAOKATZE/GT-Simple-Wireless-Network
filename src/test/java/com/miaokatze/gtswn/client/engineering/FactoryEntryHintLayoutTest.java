package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.FactoryEntryHintLayout.Bounds;

public class FactoryEntryHintLayoutTest {

    @Test
    public void wideScreenUsesGapBetweenActualTerminalAndReservedMap() {
        Bounds terminal = new Bounds(350, 8, 500, 39);
        Bounds hint = FactoryEntryHintLayout.place(1280, 720, 190, terminal);
        assertTrue(hint.x >= terminal.right() + 8);
        assertTrue(hint.right() <= 1280 - 144 - 8);
        assertTrue(hint.y == terminal.y);
    }

    @Test
    public void narrowScreenUsesNextRowAndRemainsLeftOfMap() {
        Bounds terminal = new Bounds(10, 8, 300, 39);
        Bounds hint = FactoryEntryHintLayout.place(320, 240, 208, terminal);
        assertTrue(hint.y >= terminal.bottom() + 6);
        assertTrue(hint.right() <= 320 - 144 - 8);
        assertOnScreen(hint, 320, 240);
    }

    @Test
    public void noHeldTerminalAndVeryLongKeyStayInsideSmallScreen() {
        Bounds hint = FactoryEntryHintLayout.place(180, 160, 2_000, null);
        assertTrue(hint.y >= 90 + 16);
        assertOnScreen(hint, 180, 160);
        assertOnScreen(FactoryEntryHintLayout.place(854, 480, 208, null), 854, 480);
    }

    private static void assertOnScreen(Bounds hint, int width, int height) {
        assertTrue(hint.x >= 0 && hint.y >= 0);
        assertTrue(hint.right() <= width && hint.bottom() <= height);
    }
}
