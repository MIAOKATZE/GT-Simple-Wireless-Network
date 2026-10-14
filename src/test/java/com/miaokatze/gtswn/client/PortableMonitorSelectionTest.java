package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;
import com.miaokatze.gtswn.network.PortablePanelRenderData;

public class PortableMonitorSelectionTest {

    @Test
    public void shortcutsChoosePhysicalSourcesAcrossReverseBindingOrderAndRepeatedRemoval() {
        UUID euId = UUID.randomUUID(), aeId = UUID.randomUUID();
        for (int cycle = 0; cycle < 3; cycle++) {
            // AE's unused EU configuration is 8h; it must never become the EU shortcut's source.
            PacketSyncPortableMonitor.Panel ae = panel(aeId, 2, 2, true);
            PacketSyncPortableMonitor.Panel eu = panel(euId, 0, 0, false);
            List<PacketSyncPortableMonitor.Panel> bindings = new ArrayList<>(Arrays.asList(ae, eu));
            int selected = PortableMonitorSelection.sourceIndex(bindings, 0, 0);
            assertEquals(euId, bindings.get(selected).binding.panelId);
            assertEquals("5m", new PortablePanelView(bindings.get(selected)).getWindowName());
            selected = PortableMonitorSelection.sourceIndex(bindings, selected, 2);
            assertEquals(aeId, bindings.get(selected).binding.panelId);
            assertTrue(new PortablePanelView(bindings.get(selected)).isAEConnected());
            bindings.remove(selected);
            assertEquals(1, bindings.size());
            assertEquals(-1, PortableMonitorSelection.sourceIndex(bindings, 0, 2));
            selected = PortableMonitorSelection.sourceIndex(bindings, 0, 0);
            assertEquals(euId, bindings.get(selected).binding.panelId);
            assertEquals("5m", new PortablePanelView(bindings.get(selected)).getWindowName());
            bindings.clear();
            assertEquals(-1, PortableMonitorSelection.sourceIndex(bindings, 0, 0));
        }
    }

    @Test
    public void shortcutsPreserveCurrentScreenAmongMultipleSourcesAndNeverInventUnavailableTypes() {
        PacketSyncPortableMonitor.Panel first = panel(UUID.randomUUID(), 0, 0, false);
        PacketSyncPortableMonitor.Panel second = panel(UUID.randomUUID(), 0, 1, false);
        PacketSyncPortableMonitor.Panel ae = panel(UUID.randomUUID(), 1, 2, true);
        List<PacketSyncPortableMonitor.Panel> bindings = Arrays.asList(first, second, ae);
        assertEquals(1, PortableMonitorSelection.sourceIndex(bindings, 1, 0));
        assertEquals(0, PortableMonitorSelection.sourceIndex(bindings, 2, 0));
        assertEquals(2, PortableMonitorSelection.sourceIndex(bindings, 0, 1));
        assertEquals(-1, PortableMonitorSelection.sourceIndex(bindings, 0, 2));
        // Unloaded/missing screens have no authoritative tab metadata, including default tab zero.
        first.state = 1;
        second.state = 2;
        assertEquals(-1, PortableMonitorSelection.sourceIndex(bindings, 2, 0));
    }

    private static PacketSyncPortableMonitor.Panel panel(UUID id, int tab, int window, boolean connected) {
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.binding = new MonitorBindings.Binding(0, tab, 64, 0, id);
        panel.tab = tab;
        panel.aeConnected = connected;
        panel.render = new PortablePanelRenderData();
        panel.render.config.setInteger("trackingWindow", window);
        panel.currentEnergy = "0";
        panel.render.currentEnergy = "0";
        return panel;
    }
}
