package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;

public class PortableMonitorViewIdentityTest {

    @Test
    public void reorderedSnapshotsRetainTheSelectedPhysicalPanelNotItsOldIndex() {
        PacketSyncPortableMonitor.Panel first = panel(), selected = panel(), third = panel();
        assertEquals(0, PortableMonitorSelection.retainIndex(
            Arrays.asList(selected, third, first), 1, selected.binding.panelId));
        assertEquals(1, PortableMonitorSelection.retainIndex(
            Arrays.asList(first, selected), 2, selected.binding.panelId));
    }

    @Test
    public void missingSourcesFallBackSafelyButEmptyListsNeverInventAnIndex() {
        assertEquals(0, PortableMonitorSelection.retainIndex(Collections.singletonList(panel()), -1, null));
        assertEquals(0, PortableMonitorSelection.retainIndex(Collections.singletonList(panel()), 8, UUID.randomUUID()));
        assertEquals(-1, PortableMonitorSelection.retainIndex(Collections.emptyList(), 0, UUID.randomUUID()));
    }

    @Test
    public void oldControlsAllowSameSourceRefreshButRejectAnotherOrRemovedPanel() {
        UUID terminal = UUID.randomUUID();
        PacketSyncPortableMonitor shown = new PacketSyncPortableMonitor(terminal, 1, 0);
        PacketSyncPortableMonitor next = new PacketSyncPortableMonitor(terminal, 2, 0);
        PacketSyncPortableMonitor.Panel selected = panel(), other = panel();
        shown.panels.add(selected);
        next.panels.add(other);
        next.panels.add(selected);
        assertTrue(PortableMonitorSelection.currentView(shown, selected.binding.panelId, shown, selected));
        assertTrue(PortableMonitorSelection.currentView(shown, selected.binding.panelId, next, selected));
        assertFalse(PortableMonitorSelection.currentView(shown, selected.binding.panelId, next, other));
        next.panels.remove(selected);
        assertFalse(PortableMonitorSelection.currentView(shown, selected.binding.panelId, next, selected));
        assertFalse(PortableMonitorSelection.currentView(shown, selected.binding.panelId, null, null));
    }

    @Test
    public void reusedPanelIdAtAnotherLocationOrTerminalCannotInheritOldControls() {
        UUID terminal = UUID.randomUUID();
        PacketSyncPortableMonitor shown = new PacketSyncPortableMonitor(terminal, 1, 0);
        PacketSyncPortableMonitor next = new PacketSyncPortableMonitor(terminal, 2, 0);
        PacketSyncPortableMonitor.Panel selected = panel(), moved = panel();
        shown.panels.add(selected);
        moved.binding = new MonitorBindings.Binding(0, 1, 64, 0, selected.binding.panelId);
        next.panels.add(moved);
        assertFalse(PortableMonitorSelection.currentView(shown, selected.binding.panelId, next, moved));
        next.panels.clear();
        next.panels.add(selected);
        next.terminalId = UUID.randomUUID();
        assertFalse(PortableMonitorSelection.currentView(shown, selected.binding.panelId, next, selected));
    }

    private static PacketSyncPortableMonitor.Panel panel() {
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.binding = new MonitorBindings.Binding(0, 0, 64, 0, UUID.randomUUID());
        return panel;
    }
}
