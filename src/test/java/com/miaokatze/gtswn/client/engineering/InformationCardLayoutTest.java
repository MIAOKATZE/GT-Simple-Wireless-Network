package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;

public class InformationCardLayoutTest {

    @Test
    public void defaultTwoRowPlayerShowsAllFourFieldsAndClockRetainsLargeReadingWhenItFits() {
        Rect card = new Rect(0, 0, 184, 120);
        InformationCardLayout player = new InformationCardLayout(card, 1, 4);
        assertEquals(4, player.cells.size());
        assertEquals(0, player.hidden);
        for (InformationCardLayout.Cell cell : player.cells) assertEquals(InformationCardLayout.Form.ROW, cell.form);
        InformationCardLayout clock = new InformationCardLayout(card, 1, 3);
        assertEquals(3, clock.cells.size());
        assertEquals(0, clock.hidden);
        assertEquals(InformationCardLayout.Form.HERO, clock.cells.get(0).form);
        assertEquals(InformationCardLayout.Form.ROW, clock.cells.get(1).form);
    }

    @Test
    public void requestedColumnsPlaceFieldsAcrossTheRowBeforeStartingTheNext() {
        InformationCardLayout layout = new InformationCardLayout(new Rect(30, 40, 504, 120), 3, 5);
        assertEquals(5, layout.cells.size());
        assertEquals(0, layout.hidden);
        assertEquals(layout.cells.get(0).bounds.y, layout.cells.get(2).bounds.y);
        assertTrue(layout.cells.get(2).bounds.x > layout.cells.get(1).bounds.x);
        assertEquals(layout.cells.get(0).bounds.x, layout.cells.get(3).bounds.x);
        assertTrue(layout.cells.get(3).bounds.y > layout.cells.get(0).bounds.y);
        for (InformationCardLayout.Cell cell : layout.cells) assertEquals(InformationCardLayout.Form.STACK, cell.form);
    }

    @Test
    public void sparseAndShortCardsKeepEveryCellInBodyAndExplicitlyCountOverflow() {
        for (int columns = 1; columns <= 4; columns++)
            for (int height : new int[] { 45, 64, 120, 184, 248 }) for (int count = 0; count <= 9; count++) {
                Rect card = new Rect(-20, 70, columns * 128, height);
                InformationCardLayout layout = new InformationCardLayout(card, columns, count);
                assertEquals(count, layout.cells.size() + layout.hidden);
                for (int index = 0; index < layout.cells.size(); index++) {
                    Rect cell = layout.cells.get(index).bounds;
                    assertTrue(cell.x >= card.x + 12);
                    assertTrue(cell.x + cell.w <= card.x + card.w - 12);
                    assertTrue(cell.y >= card.y + 30);
                    assertTrue(cell.y + cell.h <= card.y + card.h - 8);
                    if (layout.hidden > 0) assertTrue(cell.y + cell.h <= card.y + card.h - 22);
                    assertTrue(cell.w > 0 && cell.h >= 12);
                    for (int earlier = 0; earlier < index; earlier++) {
                        Rect other = layout.cells.get(earlier).bounds;
                        assertTrue(
                            cell.x >= other.x + other.w || other.x >= cell.x + cell.w
                                || cell.y >= other.y + other.h
                                || other.y >= cell.y + cell.h);
                    }
                }
            }
        assertTrue(new InformationCardLayout(new Rect(0, 0, 184, 64), 1, 9).hidden > 0);
    }
}
