package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;

public class FactoryChartGeometryTest {

    @Test
    public void readableGameTimeChoosesSecondsMinutesAndHoursWithoutLongOverflow() {
        assertEquals("0 s", FactoryChartGeometry.age(100, 100));
        assertEquals("-0.1 s", FactoryChartGeometry.age(2, 0));
        assertEquals("-30 s", FactoryChartGeometry.age(600, 0));
        assertEquals("-2 min", FactoryChartGeometry.age(2400, 0));
        assertEquals("-1.5 h", FactoryChartGeometry.age(108000, 0));
        assertEquals("0 s", FactoryChartGeometry.age(100, 200));
        assertEquals("0 s", FactoryChartGeometry.span(100, 100));
        assertEquals("2 min", FactoryChartGeometry.span(2400, 0));
        assertTrue(
            FactoryChartGeometry.age(Long.MAX_VALUE, Long.MIN_VALUE)
                .endsWith(" h"));
    }

    @Test
    public void minimumAndDefaultCardsKeepMetricMetadataAxisTimeAndSourceInSeparateLanes() {
        for (int width : new int[] { 205, 320, 640, 1200 }) for (int height : new int[] { 124, 192, 320 }) {
            Rect card = new Rect(17, 31, width, height);
            FactoryChartGeometry g = new FactoryChartGeometry(card);
            assertTrue(g.valueY + 18 < g.metadataY);
            assertTrue(g.metadataY + 9 < g.plot.y - 4);
            assertTrue(g.axisX + g.axisWidth <= g.plot.x);
            assertTrue(g.plot.x + g.plot.w < card.x + card.w);
            assertTrue(g.plot.y + g.plot.h + 4 < g.timeY);
            assertTrue(g.timeY + 9 < g.footerY);
            assertTrue(g.footerY + 9 < card.y + card.h);
            assertTrue(g.plot.w > 0 && g.plot.h > 0);
        }
    }

    @Test
    public void hoverAndBarWidthFollowRealIrregularTimeRatherThanSampleIndex() {
        long[] ticks = { 0, 10, 1000 };
        assertEquals(1, FactoryChartGeometry.nearest(ticks, .1));
        assertEquals(2, FactoryChartGeometry.nearest(ticks, .9));
        assertEquals(-1, FactoryChartGeometry.nearest(new long[0], .5));
        assertEquals(0, FactoryChartGeometry.nearest(new long[] { 99 }, .5));
        double first = FactoryChartGeometry.barHalfWidth(0, 3, ticks, 100);
        double second = FactoryChartGeometry.barHalfWidth(1, 3, ticks, 100);
        assertTrue(first + second < 1);
        assertTrue(FactoryChartGeometry.barHalfWidth(2, 3, ticks, 100) <= 9);
        assertEquals(0, FactoryChartGeometry.barHalfWidth(1, 3, new long[] { 0, 0, 100 }, 100), 0);
    }

    @Test
    public void nearestSelectionSurvivesSignedLongRangeAndPreservesLonePoint() {
        long[] ticks = { Long.MIN_VALUE, 0, Long.MAX_VALUE };
        assertEquals(1, FactoryChartGeometry.nearest(ticks, .5));
        assertEquals(0, FactoryChartGeometry.nearest(ticks, 0));
        assertEquals(2, FactoryChartGeometry.nearest(ticks, 1));
        assertEquals(9, FactoryChartGeometry.barHalfWidth(0, 1, new long[] { 0 }, 100), 0);
        assertEquals(500, FactoryChartGeometry.middleTick(0, 1000));
        assertEquals(-1, FactoryChartGeometry.middleTick(Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, FactoryChartGeometry.middleTick(Long.MAX_VALUE, Long.MAX_VALUE));
    }
}
