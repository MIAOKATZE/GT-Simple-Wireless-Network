package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.client.PortableMonitorClientCache;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.hud.PortableMonitorPins;

public class PortableMonitorManagementTest {

    @Test
    public void twoStockFieldChoicesRemainOneCancellableTargetAfterItGoesOffline() {
        UUID terminal = UUID.randomUUID();
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.binding = new MonitorBindings.Binding(0, 0, 64, 0, UUID.randomUUID());
        panel.state = 1;
        String prefix = terminal + "/" + panel.binding.panelId + "/", key = "fluid:water";
        String[] pins = { prefix + PortableMonitorPins.stock(key, false),
            prefix + PortableMonitorPins.stock(key, true) };
        List<PacketSyncPortableMonitor.Row> rows = PortableMonitorClientCache
            .managementRows(terminal, panel, pins, false, "unavailable", 2);
        assertEquals(1, rows.size());
        assertEquals(key, rows.get(0).key);
        assertEquals(2, rows.get(0).kind);
        assertEquals("unavailable", rows.get(0).amount);
        assertTrue(PortableMonitorPins.selected(pins, prefix, PortableMonitorPins.stock(rows.get(0).key, false)));
        assertTrue(PortableMonitorPins.selected(pins, prefix, PortableMonitorPins.stock(rows.get(0).key, true)));
        pins = PortableMonitorPins.toggle(pins, prefix, PortableMonitorPins.stock(key, false), 128);
        rows = PortableMonitorClientCache.managementRows(terminal, panel, pins, false, "unavailable", 2);
        assertEquals(1, rows.size());
        assertFalse(PortableMonitorPins.selected(pins, prefix, PortableMonitorPins.stock(rows.get(0).key, false)));
        assertTrue(PortableMonitorPins.selected(pins, prefix, PortableMonitorPins.stock(rows.get(0).key, true)));
    }

    @Test
    public void chartOnlyItemIsSelectableAndOfflineRemovedSubscriptionsRemainCancellable() {
        UUID terminal = UUID.randomUUID();
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.binding = new MonitorBindings.Binding(0, 0, 64, 0, UUID.randomUUID());
        panel.detail = true;
        PacketSyncPortableMonitor.Row chartOnly = new PacketSyncPortableMonitor.Row();
        chartOnly.kind = 1;
        chartOnly.key = "item:minecraft:iron_ingot:0";
        chartOnly.name = "Iron";
        chartOnly.amount = "256";
        chartOnly.monitored = false;
        panel.rows.add(chartOnly);
        String prefix = terminal + "/" + panel.binding.panelId + "/";
        String[] pins = { prefix + chartOnly.key, prefix + "fluid:water",
            terminal + "/" + UUID.randomUUID() + "/fluid:lava" };
        List<PacketSyncPortableMonitor.Row> rows = PortableMonitorClientCache
            .managementRows(terminal, panel, pins, true, "unavailable");
        assertEquals(2, rows.size());
        assertSame(chartOnly, rows.get(0));
        assertFalse(rows.get(0).monitored); // Chart-bound items are verifiable without native monitor-list membership.
        assertEquals("fluid:water", rows.get(1).key);
        assertEquals("unavailable", rows.get(1).amount);
        for (int state = 0; state <= 3; state++) {
            panel.state = state;
            rows = PortableMonitorClientCache.managementRows(terminal, panel, pins, false, "unavailable");
            assertEquals(2, rows.size());
            assertEquals(chartOnly.key, rows.get(0).key);
            assertEquals("Iron", rows.get(0).name);
            assertEquals("unavailable", rows.get(0).amount); // No stale quantity is presented as live.
            assertEquals("fluid:water", rows.get(1).key);
            assertEquals(2, rows.get(1).kind);
            if (state != 0) {
                rows = PortableMonitorClientCache.managementRows(terminal, panel, pins, true, "unavailable");
                assertEquals(2, rows.size());
                assertEquals("unavailable", rows.get(0).amount);
            }
        }
        panel.rows.clear();
        panel.detail = false;
        rows = PortableMonitorClientCache.managementRows(terminal, panel, pins, true, "unavailable");
        assertEquals(2, rows.size());
        assertEquals(chartOnly.key, rows.get(0).key);
        assertEquals("unavailable", rows.get(0).amount);
    }
}
