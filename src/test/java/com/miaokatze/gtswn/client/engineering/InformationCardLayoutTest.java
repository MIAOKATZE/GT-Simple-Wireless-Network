package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;

public class InformationCardLayoutTest {

    @Test
    public void compactCardsKeepConsistentTypographyAndEveryFieldInTheirNaturalLayout() {
        Rect card = new Rect(0, 0, 128, 128);
        InformationCardLayout player = new InformationCardLayout(card, 1, 4);
        InformationCardLayout clock = new InformationCardLayout(card, 1, 3);
        assertEquals(4, player.cells.size());
        assertEquals(3, clock.cells.size());
        assertEquals(InformationCardLayout.Form.HERO, player.cells.get(0).form);
        assertEquals(InformationCardLayout.Form.HERO, clock.cells.get(0).form);
        assertEquals(32, player.cells.get(0).bounds.h);
        assertEquals(26, player.cells.get(1).bounds.h);
        assertTrue(player.contentHeight > card.h - 30);
        assertEquals(118, player.contentHeight);
        assertTrue(clock.contentHeight + 5 <= card.h - 30);
    }

    @Test
    public void requestedColumnsPlaceFieldsAcrossTheRowBeforeStartingTheNext() {
        InformationCardLayout layout = new InformationCardLayout(new Rect(30, 40, 256, 64), 3, 5);
        assertEquals(5, layout.cells.size());
        assertEquals(72, layout.contentHeight);
        assertEquals(layout.cells.get(0).bounds.y, layout.cells.get(2).bounds.y);
        assertTrue(layout.cells.get(2).bounds.x > layout.cells.get(1).bounds.x);
        assertEquals(layout.cells.get(0).bounds.x, layout.cells.get(3).bounds.x);
        assertTrue(layout.cells.get(3).bounds.y > layout.cells.get(0).bounds.y);
        for (InformationCardLayout.Cell cell : layout.cells) assertEquals(InformationCardLayout.Form.STACK, cell.form);
    }

    @Test
    public void shortViewportDoesNotDiscardFieldsAndScrollingCanReachLastRow() {
        for (int columns = 1; columns <= 4; columns++) for (int count = 1; count <= 9; count++) {
            Rect card = new Rect(-20, 70, columns * 128, 64);
            InformationCardLayout layout = new InformationCardLayout(card, columns, count);
            assertEquals(count, layout.cells.size());
            assertEquals(
                layout.contentHeight,
                new InformationCardLayout(new Rect(card.x, card.y, card.w, 256), columns, count).contentHeight);
            int offset = Math.max(0, layout.contentHeight - (card.h - 30));
            InformationCardLayout scrolled = new InformationCardLayout(
                new Rect(card.x, card.y - offset, card.w, card.h),
                columns,
                count);
            Rect last = scrolled.cells.get(count - 1).bounds;
            assertTrue(last.y + last.h <= card.y + card.h - 8);
            for (int index = 0; index < layout.cells.size(); index++) {
                Rect cell = layout.cells.get(index).bounds;
                assertTrue(cell.x >= card.x + 12);
                assertTrue(cell.x + cell.w <= card.x + card.w - 12);
                for (int earlier = 0; earlier < index; earlier++) {
                    Rect other = layout.cells.get(earlier).bounds;
                    assertTrue(
                        cell.x >= other.x + other.w || other.x >= cell.x + cell.w
                            || cell.y >= other.y + other.h
                            || other.y >= cell.y + cell.h);
                }
            }
        }
    }
}
