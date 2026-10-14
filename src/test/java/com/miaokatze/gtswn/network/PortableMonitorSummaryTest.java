package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.world.World;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.panel.TrendLinePalette;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PortableMonitorSummaryTest {

    @Test
    public void unselectedLoadedScreenKeepsRealMetadataWithoutFetchingHistoryOrRows() {
        for (boolean connected : new boolean[] { true, false }) {
            PacketSyncPortableMonitor packet = new PacketSyncPortableMonitor(UUID.randomUUID(), 1, 1);
            PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
            panel.binding = binding(UUID.randomUUID(), 1);
            PortableMonitorRequestQueue.fillSummary(panel, new SummaryOnlyPanel(connected));
            packet.panels.add(panel);
            ByteBuf bytes = Unpooled.buffer();
            try {
                packet.toBytes(bytes);
                PacketSyncPortableMonitor decoded = new PacketSyncPortableMonitor();
                decoded.fromBytes(bytes);
                assertTrue(decoded.valid);
                PacketSyncPortableMonitor.Panel summary = decoded.panels.get(0);
                assertEquals("Owner", summary.ownerName);
                assertEquals("5m", summary.window);
                assertEquals("8h", summary.aeWindow);
                assertEquals(2, summary.tab);
                assertEquals(1, summary.displayMode);
                assertEquals(connected, summary.aeConnected);
                assertEquals(TrendLinePalette.DEFAULT.energyFlat, summary.palette.energyFlat);
                assertFalse(summary.detail);
                assertTrue(summary.rows.isEmpty());
                assertEquals(0, summary.euChart.length);
                assertEquals(0, summary.aeChart.length);
                assertNull(summary.render);
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    public void successfulRemovalOfSelectedScreenDetailsClosestRemainingIndex() {
        UUID removed = UUID.randomUUID(), first = UUID.randomUUID(), last = UUID.randomUUID();
        PacketRequestPortableMonitor request = removal(removed, 1);
        List<MonitorBindings.Binding> remaining = Arrays.asList(binding(first, 0), binding(last, 2));
        assertEquals(1, PortableMonitorRequestQueue.selectedIndex(request, remaining, true, 1));
        request.selected = 2;
        assertEquals(1, PortableMonitorRequestQueue.selectedIndex(request, remaining, true, 1));
        assertEquals(-1, PortableMonitorRequestQueue.selectedIndex(request, Collections.emptyList(), true, 1));
    }

    @Test
    public void removalOfOtherScreenPreservesSelectedIdentityAfterIndexShift() {
        UUID removed = UUID.randomUUID(), selected = UUID.randomUUID();
        PacketRequestPortableMonitor request = removal(removed, 1);
        request.selectedPanel = selected;
        assertEquals(
            0,
            PortableMonitorRequestQueue
                .selectedIndex(request, Collections.singletonList(binding(selected, 1)), true, 1));
    }

    @Test
    public void deniedOrAbsentRemovalCannotSelectAnUnrelatedScreen() {
        PacketRequestPortableMonitor request = removal(UUID.randomUUID(), 0);
        List<MonitorBindings.Binding> remaining = Collections.singletonList(binding(UUID.randomUUID(), 1));
        assertEquals(-1, PortableMonitorRequestQueue.selectedIndex(request, remaining, false, 2));
        assertEquals(-1, PortableMonitorRequestQueue.selectedIndex(request, remaining, false, 1));
        assertEquals(-1, PortableMonitorRequestQueue.selectedIndex(request, remaining, true, 3));
    }

    @Test
    public void ordinaryStaleSelectionAndHiddenViewerNeverFallBackToAnIndex() {
        PacketRequestPortableMonitor request = removal(UUID.randomUUID(), 0);
        List<MonitorBindings.Binding> remaining = Collections.singletonList(binding(UUID.randomUUID(), 1));
        request.action = 0;
        assertEquals(-1, PortableMonitorRequestQueue.selectedIndex(request, remaining, true, 0));
        assertEquals(-1, PortableMonitorRequestQueue.selectedIndex(request, remaining));
        request.action = 2;
        assertEquals(-1, PortableMonitorRequestQueue.selectedIndex(request, remaining, true, 1));
        request.action = 1;
        request.selected = -1;
        assertEquals(-1, PortableMonitorRequestQueue.selectedIndex(request, remaining, true, 1));
    }

    private static PacketRequestPortableMonitor removal(UUID target, int index) {
        PacketRequestPortableMonitor request = new PacketRequestPortableMonitor(UUID.randomUUID(), 1, 1, index);
        request.targetPanel = target;
        request.selectedPanel = target;
        request.selected = index;
        return request;
    }

    private static MonitorBindings.Binding binding(UUID id, int x) {
        return new MonitorBindings.Binding(0, x, 64, 0, id);
    }

    private static final class SummaryOnlyPanel extends TileEntityNetworkInfoPanel {

        private final boolean connected;

        SummaryOnlyPanel(boolean connected) {
            this.connected = connected;
        }

        @Override
        public String getOwnerName() {
            return "Owner";
        }

        @Override
        public String getWindowName() {
            return "5m";
        }

        @Override
        public int getAETrackingWindow() {
            return 2;
        }

        @Override
        public int getDisplayMode() {
            return 1;
        }

        @Override
        public int getCurrentTab() {
            return 2;
        }

        @Override
        public boolean isAEConnected() {
            return connected;
        }

        @Override
        public List<NetworkInfoSample> getCachedSamples() {
            throw new AssertionError("Summary fetched history");
        }

        @Override
        public World getWorldObj() {
            throw new AssertionError("Summary fetched world data");
        }
    }
}
