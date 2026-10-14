package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.miaokatze.gtswn.client.PortableMonitorClientCache.PinnedLine;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;
import com.miaokatze.gtswn.network.PortableMonitorIcon;

public class PortableHudLayoutTest {

    private static PinnedLine row(String text, PortableHudLayout.Group group, String source) {
        return new PinnedLine(text, (PortableMonitorIcon) null).grouped(group, source);
    }

    @Test
    public void threeEUValuesRotateAsOneSubscriptionAndNeverSplitAcrossPages() {
        PinnedLine instant = row("[1] Instant", PortableHudLayout.Group.EU_TREND, "first");
        PinnedLine average = row("Average", PortableHudLayout.Group.EU_TREND, "first");
        PinnedLine energy = row("EU", PortableHudLayout.Group.EU_TREND, "first");
        PinnedLine other = row("[2] Instant", PortableHudLayout.Group.EU_TREND, "second");
        List<PinnedLine> lines = Arrays.asList(instant, average, energy, other);
        List<List<PinnedLine>> pages = PortableHudLayout.pages(lines, 1, 200, 0, "[EU trend]", "[AE trend]");
        assertEquals(2, pages.size());
        assertEquals(
            4,
            pages.get(0)
                .size());
        assertEquals(
            "[EU trend]",
            pages.get(0)
                .get(0).text);
        assertSame(
            instant,
            pages.get(0)
                .get(1));
        assertSame(
            energy,
            pages.get(0)
                .get(3));
        assertEquals(
            2,
            pages.get(1)
                .size());
        assertSame(
            other,
            pages.get(1)
                .get(1));
        assertEquals(48, PortableHudLayout.requiredHeight(lines, 0));
        // The renderer uses requiredHeight to scale; layout must still never discard part of an oversized unit.
        List<List<PinnedLine>> constrained = PortableHudLayout.pages(lines, 16, 24, 0, "EU", "AE");
        assertEquals(2, constrained.size());
        assertEquals(
            4,
            constrained.get(0)
                .size());
    }

    @Test
    public void eachTrendFamilyGetsOneHeadingAndEachSourceKeepsItsOwnPlot() {
        PinnedLine euFirst = new PinnedLine("", new double[] { 0, 1 }, new double[0], 0)
            .grouped(PortableHudLayout.Group.EU_TREND, "first");
        PinnedLine ae = new PinnedLine("", new double[] { 1, 0 }, new double[0], 0)
            .grouped(PortableHudLayout.Group.AE_TREND, "first");
        PinnedLine euSecond = new PinnedLine("", new double[] { 0, 0.5 }, new double[0], 0)
            .grouped(PortableHudLayout.Group.EU_TREND, "second");
        PinnedLine stock = row("[AE stock] Iron", PortableHudLayout.Group.AE_STOCK, "first");
        List<List<PinnedLine>> pages = PortableHudLayout
            .pages(Arrays.asList(ae, euFirst, stock, euSecond), 4, 200, 0, "[EU trend]", "[AE trend]");
        assertEquals(1, pages.size());
        List<PinnedLine> page = pages.get(0);
        assertEquals(6, page.size());
        assertEquals("[EU trend]", page.get(0).text);
        assertSame(euFirst, page.get(1));
        assertSame(euSecond, page.get(2));
        assertEquals("[AE trend]", page.get(3).text);
        assertSame(ae, page.get(4));
        assertSame(stock, page.get(5));
        assertSame(euFirst.chart, page.get(1).chart);
        assertSame(euSecond.chart, page.get(2).chart);
    }

    @Test
    public void titlesPlotsAndValuesShareOneSourceWhileInventoryWordsCountSeparately() {
        PinnedLine title = row("[1] EU / EU/t", PortableHudLayout.Group.EU_TREND, "panel");
        PinnedLine plot = new PinnedLine("", new double[61], new double[61], 0)
            .grouped(PortableHudLayout.Group.EU_TREND, "panel");
        PinnedLine value = row("EU: 100", PortableHudLayout.Group.EU_TREND, "panel");
        PinnedLine iron = row("[AE stock] Iron", PortableHudLayout.Group.AE_STOCK, "panel");
        PinnedLine gold = row("[AE stock] Gold", PortableHudLayout.Group.AE_STOCK, "panel");
        List<List<PinnedLine>> pages = PortableHudLayout
            .pages(Arrays.asList(title, plot, value, iron, gold), 1, 200, 0, "EU", "AE");
        assertEquals(3, pages.size());
        assertEquals(
            4,
            pages.get(0)
                .size());
        assertEquals(
            1,
            pages.get(1)
                .size());
        assertEquals(
            1,
            pages.get(2)
                .size());
        assertEquals(84, PortableHudLayout.requiredHeight(Arrays.asList(title, plot, value), 0));
        assertEquals(96, PortableHudLayout.requiredHeight(Arrays.asList(title, plot, value), 1));
    }

    @Test
    public void onlyFirstEnabledEUValueCarriesTheSourceNumber() {
        PacketSyncPortableMonitor.Row eu = new PacketSyncPortableMonitor.Row();
        eu.amount = "256";
        eu.rate = 32;
        eu.average = 16;
        List<String> values = PortableMonitorClientCache
            .pinnedEUValueLines(eu, 0, true, true, true, false, "[1] ", "Instant", "Average");
        assertEquals(3, values.size());
        assertTrue(
            values.get(0)
                .startsWith("[1] "));
        assertTrue(
            values.get(0)
                .contains("Instant"));
        assertTrue(
            values.get(1)
                .contains("Average"));
        assertFalse(
            values.get(1)
                .contains("[1]"));
        assertFalse(
            values.get(2)
                .contains("[1]"));
        assertTrue(
            PortableMonitorClientCache.pinnedEUValueLines(eu, 0, true, false, true, false, "[2] ", "Instant", "Average")
                .get(0)
                .startsWith("[2] "));
        assertTrue(
            PortableMonitorClientCache
                .pinnedEUValueLines(eu, 0, false, false, false, false, "[1] ", "Instant", "Average")
                .isEmpty());
    }

    @Test
    public void iconColumnUsesExactlyOneRowOfHeightAndKeepsIndependentWidth() {
        PinnedLine fluid = new PinnedLine(
            "Water",
            PortableMonitorIcon.of(com.miaokatze.gtswn.test.FluidRegistryTestBootstrap.water(1000)));
        assertEquals(14, PortableHudLayout.iconColumn(fluid, 0));
        assertEquals(18, PortableHudLayout.iconColumn(fluid, 1));
        assertEquals(54, PortableHudLayout.width(fluid, 40, 240, 0));
        assertEquals(58, PortableHudLayout.width(fluid, 40, 240, 1));
        assertEquals(40, PortableHudLayout.width(new PinnedLine("Water", (PortableMonitorIcon) null), 40, 240, 0));
        assertEquals(20, PortableHudLayout.width(fluid, 40, 20, 0));
    }

    @Test
    public void plotDoesNotStretchListValuesAndMixedRowsKeepTheirOwnHeight() {
        PinnedLine plot = new PinnedLine("", new double[61], new double[61], 0);
        PinnedLine value = new PinnedLine("Iron: 256", new double[0]);
        assertEquals(48, PortableHudLayout.height(plot, 0));
        assertEquals(12, PortableHudLayout.height(value, 0));
        assertEquals(240, PortableHudLayout.width(plot, 0, 240));
        assertEquals(53, PortableHudLayout.width(value, 53, 240));
        List<List<PinnedLine>> pages = PortableHudLayout.pages(Arrays.asList(plot, value, value), 16, 72, 0);
        assertEquals(1, pages.size());
        assertEquals(
            3,
            pages.get(0)
                .size());
        assertEquals(
            2,
            PortableHudLayout.pages(Arrays.asList(plot, value, value), 16, 60, 0)
                .size());
    }
}
