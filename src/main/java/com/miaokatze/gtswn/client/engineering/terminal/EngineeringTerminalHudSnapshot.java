package com.miaokatze.gtswn.client.engineering.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.client.DeviceTerminalVisualizationState;
import com.miaokatze.gtswn.client.PortableMonitorClientCache;
import com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;
import com.miaokatze.gtswn.common.hud.HudController;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Read-only factory demand backed by the existing terminal queues and HUD cache owner. */
public final class EngineeringTerminalHudSnapshot {

    private static HudController hud;
    private static EntityPlayer lastPlayer;
    private static Object lastWorld, lastConnection;
    private static int lastTick = Integer.MIN_VALUE;
    private static EngineeringTerminalHudSnapshot cached;
    private static boolean factoryReading, deviceReading;
    private static EngineeringTerminalSources.Source deviceDemand = EngineeringTerminalSources.Source.EMPTY;
    private static Object demandWorld, demandConnection;
    private static EntityPlayer demandPlayer;
    private static int lastDeviceRequest = Integer.MIN_VALUE;
    private static long initialDeviceReceipt;

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

    /** Explicit lifecycle demand, never a side effect of capture/render and never a visualization toggle. */
    public static void tick(boolean reading) {
        Minecraft mc = Minecraft.getMinecraft();
        reading &= mc.theWorld != null && mc.thePlayer != null && !mc.thePlayer.isDead;
        boolean portableReading = (reading || PortableSuperDashboard.isOpen()) && mc.theWorld != null
            && mc.thePlayer != null
            && !mc.thePlayer.isDead;
        if (!portableReading) {
            close();
            return;
        }
        if (!factoryReading || demandWorld != mc.theWorld
            || demandConnection != mc.getNetHandler()
            || demandPlayer != mc.thePlayer) {
            close();
            factoryReading = true;
            demandWorld = mc.theWorld;
            demandConnection = mc.getNetHandler();
            demandPlayer = mc.thePlayer;
        }
        if (deviceReading != reading) cached = null;
        deviceReading = reading;
        if (hud != null) {
            hud.setFactoryReading(true);
            // This is the original HUD's sampler and token/session gate, shared with updateForFrame.
            hud.updateForFrame(mc.thePlayer);
        }
        if (!deviceReading) {
            deviceDemand = EngineeringTerminalSources.Source.EMPTY;
            lastDeviceRequest = Integer.MIN_VALUE;
            return;
        }
        EngineeringTerminalSources.Source current = EngineeringTerminalSources.resolve(mc.thePlayer, 2);
        if (!deviceDemand.sameIdentity(current)) {
            deviceDemand = current;
            initialDeviceReceipt = DeviceTerminalClientCache
                .receiptRevision(ItemDeviceInfoTerminal.readTerminalId(current.stack));
            lastDeviceRequest = Integer.MIN_VALUE;
            cached = null;
        }
        if (!current.present()) return;
        UUID id = ItemDeviceInfoTerminal.readTerminalId(current.stack);
        // Factory owns this polling demand even when its labels are off; the ordinary renderer is paused here.
        int now = mc.thePlayer.ticksExisted;
        int interval = DeviceTerminalClientCache.hasMorePending(id) ? 5 : 20;
        if (lastDeviceRequest == Integer.MIN_VALUE || now < lastDeviceRequest || now - lastDeviceRequest >= interval) {
            GTSWNPacketHandler.NETWORK.sendToServer(
                lastDeviceRequest == Integer.MIN_VALUE
                    ? new com.miaokatze.gtswn.network.PacketRequestDeviceTerminalData(id, -1, false)
                    : DeviceTerminalClientCache.requestMetadata(id));
            lastDeviceRequest = now;
        }
    }

    /** Rendering cannot borrow a prior source/connection cache or a normal ACK for another UUID. */
    public static DeviceTerminalClientCache.Snapshot visualizationSnapshot(UUID id) {
        Minecraft mc = Minecraft.getMinecraft();
        if (id == null || !deviceReading
            || demandPlayer != mc.thePlayer
            || demandWorld != mc.theWorld
            || demandConnection != mc.getNetHandler()
            || mc.thePlayer == null
            || mc.thePlayer.isDead
            || mc.thePlayer.getHealth() <= 0) return null;
        EngineeringTerminalSources.Source current = EngineeringTerminalSources.resolve(mc.thePlayer, 2);
        if (!acceptsVisualization(
            id,
            ItemDeviceInfoTerminal.readTerminalId(current.stack),
            current.present() && deviceDemand.sameIdentity(current),
            initialDeviceReceipt,
            DeviceTerminalClientCache.receiptRevision(id),
            DeviceTerminalClientCache.isFresh(id, System.currentTimeMillis()))) return null;
        return DeviceTerminalClientCache.getSnapshot(id);
    }

    static boolean acceptsVisualization(UUID requested, UUID actual, boolean sameSource, long before, long receipt,
        boolean fresh) {
        return requested != null && requested.equals(actual) && sameSource && receipt > before && fresh;
    }

    public static void close() {
        factoryReading = false;
        deviceReading = false;
        deviceDemand = EngineeringTerminalSources.Source.EMPTY;
        demandWorld = demandConnection = null;
        demandPlayer = null;
        lastDeviceRequest = Integer.MIN_VALUE;
        cached = null;
        if (hud != null) hud.setFactoryReading(false);
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
        EngineeringTerminalSources.Source device = EngineeringTerminalSources.resolve(player, 2);
        EngineeringTerminalSources.Source portable = EngineeringTerminalSources.resolve(player, 0);
        cached = new EngineeringTerminalHudSnapshot(device(device), portable(portable));
        return cached;
    }

    private static Source device(EngineeringTerminalSources.Source selected) {
        UUID id = ItemDeviceInfoTerminal.readTerminalId(selected.stack);
        boolean itemEnabled = ItemDeviceInfoTerminal.isVisualizationEnabled(selected.stack);
        boolean enabled = deviceReading && selected.present()
            || itemEnabled && DeviceTerminalVisualizationState.active(id);
        DeviceTerminalClientCache.Snapshot snapshot = enabled ? DeviceTerminalClientCache.getSnapshot(id) : null;
        return new Source(
            id,
            selected.origin,
            enabled,
            itemEnabled,
            snapshot != null && DeviceTerminalClientCache.isFresh(id, System.currentTimeMillis()),
            Collections.emptyList(),
            Collections.emptyList(),
            snapshot == null ? Collections.emptyList() : snapshot.entries);
    }

    private static Source portable(EngineeringTerminalSources.Source selected) {
        ItemStack stack = selected.stack;
        UUID id = MonitorBindings.id(stack);
        boolean itemEnabled = stack != null && PortableWirelessNetworkMonitor.getHudMode(stack) > 0;
        boolean enabled = selected.present() && (factoryReading || itemEnabled);
        List<String> basic = new ArrayList<>();
        // Old wireless-EU text belongs to the controller's selected owner, never to another terminal.
        boolean selectedByHud = selected.present() && hud != null
            && selected.binding.equals(EngineeringTerminalSources.binding(hud.monitorStack()));
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
            selected.origin,
            enabled,
            itemEnabled,
            enabled && (selectedByHud && hud.fresh() || !PortableMonitorClientCache.stale(stack)),
            basic,
            lines,
            Collections.emptyList());
    }

    public static final class Source {

        private static final Source EMPTY = new Source(
            null,
            SourceOrigin.NONE,
            false,
            false,
            false,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList());
        public final UUID terminalId;
        public final boolean bauble, enabled, fresh;
        public final boolean itemDisplayEnabled;
        public final SourceOrigin origin;
        public final List<String> basicLines;
        public final List<PortableMonitorClientCache.PinnedLine> pinnedLines;
        public final List<Entry> devices;

        private Source(UUID terminalId, SourceOrigin origin, boolean enabled, boolean itemDisplayEnabled, boolean fresh,
            List<String> basic, List<PortableMonitorClientCache.PinnedLine> pinned, List<Entry> devices) {
            this.terminalId = terminalId;
            this.origin = origin;
            this.bauble = origin == SourceOrigin.BAUBLE;
            this.enabled = enabled;
            this.itemDisplayEnabled = itemDisplayEnabled;
            this.fresh = fresh;
            this.basicLines = Collections.unmodifiableList(new ArrayList<>(basic));
            this.pinnedLines = Collections.unmodifiableList(new ArrayList<>(pinned));
            this.devices = Collections.unmodifiableList(new ArrayList<>(devices));
        }
    }

}
