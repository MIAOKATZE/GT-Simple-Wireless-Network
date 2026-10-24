package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

public class FactoryDashboardTest {

    @Test
    public void repeatedOverscrollNeverAccumulatesInvisibleDebt() {
        ScrollModel scroll = new ScrollModel();
        scroll.measure(100, 20, 0);
        for (int i = 0; i < 100; i++) scroll.move(10);
        assertEquals(80, scroll.offset());
        assertEquals(79, scroll.move(-1));
        assertEquals(0, scroll.measure(2, 20, scroll.offset()));
        assertEquals(0, scroll.move(-1));
    }

    @Test
    public void narrowLayoutReflowsKpisAndKeepsEveryCardInBounds() {
        for (int width : new int[] { 380, 600, 1000 }) for (int preset = 0; preset < 3; preset++) {
            FactoryDashboardLayout layout = new FactoryDashboardLayout(width, preset, true, false);
            for (Rect rect : layout.kpis) assertRect(rect, width, layout.contentHeight);
            for (Rect rect : new Rect[] { layout.trend, layout.region, layout.resources, layout.attention })
                assertRect(rect, width, layout.contentHeight);
            if (width < 540) assertTrue(layout.kpis[2].y > layout.kpis[0].y);
            else assertEquals(layout.kpis[0].y, layout.kpis[3].y);
        }
    }

    @Test
    public void hugeEnergyAndMissingPointsNeverOverflowOrBecomeZero() {
        BigInteger huge = BigInteger.TEN.pow(400);
        FactoryTrend plot = new FactoryTrend(new BigInteger[] { huge, null, huge.multiply(BigInteger.valueOf(2)) });
        assertEquals(.5, plot.normalized[0], 1e-10);
        assertTrue(Double.isNaN(plot.normalized[1]));
        assertEquals(1, plot.normalized[2], 1e-10);
        assertEquals(0, plot.zero, 0);
        FactoryTrend signed = new FactoryTrend(new BigInteger[] { BigInteger.valueOf(-10), BigInteger.TEN });
        assertEquals(.5, signed.zero, 0);
        FactoryTrend tiny = new FactoryTrend(
            new java.math.BigDecimal[] { new java.math.BigDecimal("0.0000000003"),
                new java.math.BigDecimal("0.0000000006") });
        assertEquals(.5, tiny.normalized[0], 1e-10);
        assertEquals(1, tiny.normalized[1], 1e-10);
    }

    @Test
    public void crossDimensionRecordAndSourceStayIndependentOfWorldLocation() {
        UUID source = UUID.randomUUID();
        Entry local = entry(0), remote = entry(-1);
        SelectedMachineRecord selection = new SelectedMachineRecord(source, remote);
        assertTrue(selection.matches(source, remote));
        assertFalse(selection.matches(source, local));
        assertFalse(selection.matches(UUID.randomUUID(), remote));
        assertFalse(selection.locatable(0));
        assertTrue(selection.locatable(-1));
    }

    @Test
    public void localSearchIsCaseInsensitiveAndBounded() {
        FactoryTextSearch search = new FactoryTextSearch();
        search.value = "DIM -1";
        assertTrue(search.matches("Machine DIM -1 10/20/30"));
        assertFalse(search.matches("Machine DIM 0 10/20/30"));
        search.backspace();
        assertEquals("DIM -", search.value);
        for (int i = 0; i < 200; i++) search.character('a');
        assertEquals(96, search.value.length());
        search.character('\n');
        assertEquals(96, search.value.length());
    }

    @Test
    public void narrowObjectsUseAFullHeightListAndSeparateInspectionPage() {
        WorkbenchLayout list = new WorkbenchLayout(
            480,
            300,
            false,
            true,
            false,
            false,
            200,
            108,
            false,
            true,
            true,
            false);
        assertNull(list.inspector);
        assertTrue(list.rail.h >= 200);
        WorkbenchLayout inspect = new WorkbenchLayout(
            480,
            300,
            false,
            true,
            false,
            false,
            200,
            108,
            false,
            true,
            true,
            true);
        assertEquals(inspect.rail.x, inspect.inspector.x);
        assertEquals(inspect.rail.y, inspect.inspector.y);
        assertEquals(inspect.rail.h, inspect.inspector.h);
        assertTrue(inspect.contains(inspect.inspector.x + 10, inspect.inspector.y + 10));
    }

    private static Entry entry(int dim) {
        return new Entry("id", "machine", (byte) 1, (byte) 0, 1, 2, 1, 2, dim, 10, 20, 30, "", "");
    }

    @Test
    public void delayedScreensAndReorderingCannotChangeSelectedIdentity() {
        com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel first = new com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel();
        com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel second = new com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel();
        UUID selected = UUID.randomUUID();
        first.binding = new com.miaokatze.gtswn.common.hud.MonitorBindings.Binding(0, 1, 2, 3, UUID.randomUUID());
        second.binding = new com.miaokatze.gtswn.common.hud.MonitorBindings.Binding(0, 4, 5, 6, selected);
        assertNull(FactoryDashboard.selectedPanel(java.util.Collections.singletonList(first), selected.toString()));
        assertSame(second, FactoryDashboard.selectedPanel(java.util.Arrays.asList(first, second), selected.toString()));
        assertSame(second, FactoryDashboard.selectedPanel(java.util.Arrays.asList(second, first), selected.toString()));
        assertNull(FactoryDashboard.selectedPanel(java.util.Collections.emptyList(), selected.toString()));
    }

    private static void assertRect(Rect rect, int width, int height) {
        assertTrue(rect.x >= 0 && rect.y >= 0 && rect.w > 0 && rect.h > 0);
        assertTrue(rect.x + rect.w <= width);
        assertTrue(rect.y + rect.h <= height);
    }
}
