package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.client.PortableMonitorClientCache;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.hud.PortableMonitorPins;

public class PortableMonitorSubscriptionsTest {

    @Test
    public void newerInventoryCredentialsCannotSelectOldEightHourDetailAfterSlotReplacement() {
        net.minecraft.item.ItemStack terminal = new net.minecraft.item.ItemStack(
            new com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor());
        terminal.stackTagCompound = new net.minecraft.nbt.NBTTagCompound();
        terminal.stackTagCompound.setBoolean("Initialized", true);
        terminal.stackTagCompound.setString(
            "OwnerUUID",
            UUID.randomUUID()
                .toString());
        terminal.stackTagCompound.setString(
            "MonitorId",
            UUID.randomUUID()
                .toString());
        UUID currentPanel = UUID.randomUUID();
        net.minecraft.nbt.NBTTagCompound binding = new net.minecraft.nbt.NBTTagCompound();
        binding.setString("id", currentPanel.toString());
        net.minecraft.nbt.NBTTagList bindings = new net.minecraft.nbt.NBTTagList();
        bindings.appendTag(binding);
        terminal.stackTagCompound.setTag("MonitorPanels", bindings);
        terminal.stackTagCompound.setLong("MonitorRevision", 3);
        PacketSyncPortableMonitor previous = new PacketSyncPortableMonitor(MonitorBindings.id(terminal), 1, 1);
        previous.bindingRevision = 1;
        PacketSyncPortableMonitor.Panel old = new PacketSyncPortableMonitor.Panel();
        old.binding = new MonitorBindings.Binding(0, 0, 0, 0, UUID.randomUUID());
        old.window = "8h";
        old.renderVersion = 42;
        old.render = new PortablePanelRenderData();
        previous.panels.add(old);
        PacketRequestPortableMonitor request = new PacketRequestPortableMonitor(MonitorBindings.id(terminal), 2, 0, 0);
        request.selected = 0;
        PortableMonitorClientCache.selectRequest(request, terminal, previous);
        assertEquals(currentPanel, request.selectedPanel);
        assertEquals(0, request.knownRenderVersion);
        previous.bindingRevision = 4; // The server reply may precede the corresponding inventory update.
        PortableMonitorClientCache.selectRequest(request, terminal, previous);
        assertEquals(old.binding.panelId, request.selectedPanel);
        assertEquals(42, request.knownRenderVersion);
    }

    @Test
    public void legacyPlotRemovalRetainsTitleAndAllOtherTerminalsWithoutCreatingValues() {
        String prefix = UUID.randomUUID() + "/" + UUID.randomUUID() + "/";
        String other = UUID.randomUUID() + "/" + UUID.randomUUID() + "/fluid:water";
        String[] old = { prefix + PortableMonitorPins.LEGACY_EU, other };
        assertTrue(PortableMonitorPins.selected(old, prefix, PortableMonitorPins.EU_TITLE));
        assertTrue(PortableMonitorPins.selected(old, prefix, PortableMonitorPins.EU_PLOT));
        String[] removed = PortableMonitorPins.toggle(old, prefix, PortableMonitorPins.EU_PLOT, 128);
        assertTrue(
            Arrays.asList(removed)
                .contains(other));
        assertTrue(PortableMonitorPins.selected(removed, prefix, PortableMonitorPins.EU_TITLE));
        assertFalse(PortableMonitorPins.selected(removed, prefix, PortableMonitorPins.EU_PLOT));
        assertFalse(PortableMonitorPins.selected(removed, prefix, "eu"));
        String[] rebound = PortableMonitorPins.toggle(removed, prefix, PortableMonitorPins.EU_PLOT, 128);
        assertTrue(PortableMonitorPins.selected(rebound, prefix, PortableMonitorPins.EU_PLOT));
        assertArrayEquals(removed, PortableMonitorPins.toggle(rebound, prefix, PortableMonitorPins.EU_PLOT, 128));
    }

    @Test
    public void titleOnlyDoesNotRequestHistoryOrSubscribeAnyValue() {
        java.util.Set<String> title = Collections.singleton(PortableMonitorPins.EU_TITLE);
        assertFalse(PortableMonitorRequestQueue.needsEUChart(false, title, true));
        assertFalse(PortableMonitorRequestQueue.needsEUChart(false, Collections.singleton("eu"), true));
        assertTrue(
            PortableMonitorRequestQueue.needsEUChart(false, Collections.singleton(PortableMonitorPins.EU_PLOT), false));
        assertTrue(PortableMonitorRequestQueue.needsAEChart(false, Collections.singleton(PortableMonitorPins.AE_PLOT)));
    }

    @Test
    public void managementRowsAreScopedAndOfflineComponentsStayRemovable() {
        UUID terminal = UUID.randomUUID();
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.binding = new MonitorBindings.Binding(0, 0, 64, 0, UUID.randomUUID());
        panel.detail = true;
        PacketSyncPortableMonitor.Row eu = new PacketSyncPortableMonitor.Row();
        eu.key = "eu";
        PacketSyncPortableMonitor.Row ae = new PacketSyncPortableMonitor.Row();
        ae.key = "fluid:water";
        ae.kind = 2;
        panel.rows.add(eu);
        panel.rows.add(ae);
        String prefix = terminal + "/" + panel.binding.panelId + "/";
        String[] pins = { prefix + PortableMonitorPins.EU_TITLE, prefix + PortableMonitorPins.AE_PLOT, prefix + ae.key,
            prefix + eu.key };
        List<PacketSyncPortableMonitor.Row> euRows = PortableMonitorClientCache
            .managementRows(terminal, panel, pins, true, "offline", 0);
        assertEquals(3, euRows.size());
        assertEquals("eu", euRows.get(2).key);
        assertEquals(
            2,
            PortableMonitorClientCache.managementRows(terminal, panel, pins, true, "offline", 1)
                .size());
        assertEquals(
            1,
            PortableMonitorClientCache.managementRows(terminal, panel, pins, true, "offline", 2)
                .size());
        panel.rows.clear();
        panel.state = 1;
        assertEquals(
            3,
            PortableMonitorClientCache.managementRows(terminal, panel, pins, false, "offline", 0)
                .size());
        assertEquals(
            2,
            PortableMonitorClientCache.managementRows(terminal, panel, pins, false, "offline", 1)
                .size());
        assertEquals(
            1,
            PortableMonitorClientCache.managementRows(terminal, panel, pins, false, "offline", 2)
                .size());
    }

    @Test
    public void selectedIdentityCannotFollowAnIndexIntoAnotherScreen() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        List<MonitorBindings.Binding> before = Arrays
            .asList(new MonitorBindings.Binding(0, 0, 64, 0, first), new MonitorBindings.Binding(0, 1, 64, 0, second));
        PacketRequestPortableMonitor request = new PacketRequestPortableMonitor(UUID.randomUUID(), 1, 0, 0);
        request.selected = 0;
        request.selectedPanel = first;
        assertEquals(0, PortableMonitorRequestQueue.selectedIndex(request, before));
        assertEquals(-1, PortableMonitorRequestQueue.selectedIndex(request, before.subList(1, 2)));
        request.selectedPanel = second;
        assertEquals(1, PortableMonitorRequestQueue.selectedIndex(request, before));
        assertEquals(0, PortableMonitorRequestQueue.selectedIndex(request, before.subList(1, 2)));
    }

    @Test
    public void subscriptionLimitAllowsRemovalAtCapacityAndPreservesOtherEntries() {
        String[] pins = new String[128];
        for (int i = 0; i < pins.length; i++) pins[i] = "other/" + i;
        assertNull(PortableMonitorPins.toggle(pins, "terminal/panel/", PortableMonitorPins.AE_PLOT, 128));
        pins[12] = "terminal/panel/" + PortableMonitorPins.LEGACY_AE;
        String[] result = PortableMonitorPins.toggle(pins, "terminal/panel/", PortableMonitorPins.AE_TITLE, 128);
        assertEquals(128, result.length);
        assertTrue(new HashSet<>(Arrays.asList(result)).contains("terminal/panel/" + PortableMonitorPins.AE_PLOT));
    }
}
