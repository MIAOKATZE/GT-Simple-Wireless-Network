package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import org.junit.Test;

public class DesktopResourcePagesTest {

    @Test
    public void shorterCardDoesNotResetAnotherCardsPage() {
        DesktopResourcePages pages = new DesktopResourcePages();
        pages.next("short", 30, 3);
        pages.next("short", 30, 3);
        assertEquals(6, pages.start("short", 30, 3));
        assertEquals(0, pages.start("tall", 30, 40));
        assertEquals(6, pages.start("short", 30, 3));
    }

    @Test
    public void resizingDoesNotLoseTheRowAnchorAndChangedFiltersResetIt() {
        DesktopResourcePages pages = new DesktopResourcePages();
        pages.next("card", 25, 8);
        assertEquals(6, pages.start("card", 25, 3));
        assertEquals(8, pages.start("card", 25, 8));
        assertEquals(0, pages.start("card", 2, 8));
        assertEquals(8, pages.start("card", 25, 8));
        pages.clear();
        assertEquals(0, pages.start("card", 25, 8));
    }
}
