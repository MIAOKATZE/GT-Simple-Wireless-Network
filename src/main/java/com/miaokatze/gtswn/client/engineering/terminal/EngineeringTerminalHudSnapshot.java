package com.miaokatze.gtswn.client.engineering.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.client.DeviceTerminalVisualizationState;
import com.miaokatze.gtswn.client.PortableMonitorClientCache;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.hud.HudController;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Read-only adapter: the existing HUD owns requests, subscriptions and session validation. */
public final class EngineeringTerminalHudSnapshot {

    private static HudController hud;
    private static EntityPlayer lastPlayer;
    private static Object lastWorld, lastConnection;
    private static int lastTick = Integer.MIN_VALUE;
    private static EngineeringTerminalHudSnapshot cached;

    public final Source device;
    public final Source portable;

    private EngineeringTerminalHudSnapshot(Source device, Source portable) {
        this.device = device;
        this.portable = portable;
    }

    /** Receives the single existing HUD controller; never creates a second polling owner. */
    public static void bind(HudController controller) {
        hud = controller;
        cached = null;
    }

    /** Client-main-thread capture; reuse a stable view throughout a rendered client tick. */
    public static EngineeringTerminalHudSnapshot capture(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        Object connection = mc.getNetHandler();
        if (player == null || mc.theWorld == null || player.worldObj != mc.theWorld) {
            cached = null;
            lastPlayer = null;
            return new EngineeringTerminalHudSnapshot(Source.EMPTY, Source.EMPTY);
        }
        if (cached != null && lastPlayer == player
            && lastWorld == mc.theWorld
            && lastConnection == connection
            && lastTick == player.ticksExisted) return cached;
        lastPlayer = player;
        lastWorld = mc.theWorld;
        lastConnection = connection;
        lastTick = player.ticksExisted;
        Selected device = select(player, false), portable = select(player, true);
        cached = new EngineeringTerminalHudSnapshot(device(device), portable(portable));
        return cached;
    }

    private static Selected select(EntityPlayer player, boolean portable) {
        IInventory baubles = TerminalBaubles.inventory(player);
        if (baubles != null) for (int i = 0; i < baubles.getSizeInventory(); i++) {
            ItemStack stack = baubles.getStackInSlot(i);
            if (matches(stack, portable)) return new Selected(stack, true);
        }
        for (ItemStack stack : player.inventory.mainInventory)
            if (matches(stack, portable)) return new Selected(stack, false);
        return new Selected(null, false);
    }

    private static boolean matches(ItemStack stack, boolean portable) {
        return portable ? PortableWirelessNetworkMonitor.isMonitorBound(stack)
            : stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal;
    }

    private static Source device(Selected selected) {
        UUID id = ItemDeviceInfoTerminal.readTerminalId(selected.stack);
        boolean enabled = ItemDeviceInfoTerminal.isVisualizationEnabled(selected.stack)
            && DeviceTerminalVisualizationState.active(id);
        DeviceTerminalClientCache.Snapshot snapshot = enabled ? DeviceTerminalClientCache.getSnapshot(id) : null;
        return new Source(
            id,
            selected.bauble,
            enabled,
            snapshot != null && DeviceTerminalClientCache.isFresh(id, System.currentTimeMillis()),
            Collections.emptyList(),
            Collections.emptyList(),
            snapshot == null ? Collections.emptyList() : snapshot.entries);
    }

    private static Source portable(Selected selected) {
        ItemStack stack = selected.stack;
        UUID id = MonitorBindings.id(stack);
        boolean enabled = stack != null && PortableWirelessNetworkMonitor.getHudMode(stack) > 0;
        List<String> basic = new ArrayList<>();
        // Old wireless-EU text belongs to the controller's selected owner, never to another terminal.
        boolean selectedByHud = hud != null && (id == null ? stack != null && stack == hud.monitorStack()
            : id.equals(MonitorBindings.id(hud.monitorStack())));
        if (enabled && selectedByHud) {
            if (Config.hudChargeEnabled && hud.hasBattery()) basic.add(hud.chargeText());
            if (Config.hudInstantEnabled) basic.add(hud.realtimeEutText());
            if (Config.hudAverageEnabled) basic.add(hud.eutText());
            if (Config.hudEUEnabled) basic.add(hud.euText());
        }
        List<PortableMonitorClientCache.PinnedLine> lines = new ArrayList<>();
        if (enabled) for (PortableMonitorClientCache.PinnedLine line : PortableMonitorClientCache.pinnedLines(stack)) {
            PortableMonitorClientCache.PinnedLine copy = line.graph
                ? new PortableMonitorClientCache.PinnedLine(
                    line.text,
                    line.chart.clone(),
                    line.rateChart.clone(),
                    line.change,
                    line.currentText,
                    line.palette,
                    line.euGraph)
                : new PortableMonitorClientCache.PinnedLine(line.text, line.icon);
            lines.add(copy.grouped(line.group, line.source));
        }
        return new Source(
            id,
            selected.bauble,
            enabled,
            enabled && !PortableMonitorClientCache.stale(stack),
            basic,
            lines,
            Collections.emptyList());
    }

    public static final class Source {

        private static final Source EMPTY = new Source(
            null,
            false,
            false,
            false,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList());
        public final UUID terminalId;
        public final boolean bauble, enabled, fresh;
        public final List<String> basicLines;
        public final List<PortableMonitorClientCache.PinnedLine> pinnedLines;
        public final List<Entry> devices;

        private Source(UUID terminalId, boolean bauble, boolean enabled, boolean fresh, List<String> basic,
            List<PortableMonitorClientCache.PinnedLine> pinned, List<Entry> devices) {
            this.terminalId = terminalId;
            this.bauble = bauble;
            this.enabled = enabled;
            this.fresh = fresh;
            this.basicLines = Collections.unmodifiableList(new ArrayList<>(basic));
            this.pinnedLines = Collections.unmodifiableList(new ArrayList<>(pinned));
            this.devices = Collections.unmodifiableList(new ArrayList<>(devices));
        }
    }

    private static final class Selected {

        final ItemStack stack;
        final boolean bauble;

        Selected(ItemStack stack, boolean bauble) {
            this.stack = stack;
            this.bauble = bauble;
        }
    }
}
