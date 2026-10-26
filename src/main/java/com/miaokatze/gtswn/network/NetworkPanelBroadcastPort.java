package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;
import com.miaokatze.gtswn.common.panel.AEMonitorDataStore;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.PanelBroadcastPort;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.network.telemetry.MonitorCapabilities;
import com.miaokatze.gtswn.network.telemetry.TelemetryTransport;

/** Per-recipient negotiated screen stream. Legacy recipients alone receive the unchanged legacy packet. */
public final class NetworkPanelBroadcastPort implements PanelBroadcastPort {

    private static final Map<TileEntityNetworkInfoPanel, State> STATES = new WeakHashMap<>();
    private static final java.util.concurrent.ConcurrentLinkedQueue<Ack> ACKS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger ACK_COUNT = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.ConcurrentHashMap<EntityPlayerMP, java.util.concurrent.atomic.AtomicInteger> PLAYER_ACKS = new java.util.concurrent.ConcurrentHashMap<>();
    private static long sequence;

    public static void clear() {
        STATES.clear();
        ACKS.clear();
        ACK_COUNT.set(0);
        PLAYER_ACKS.clear();
        sequence = 0;
    }

    @cpw.mods.fml.common.eventhandler.SubscribeEvent
    public void onServerTick(cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent event) {
        if (event.phase == cpw.mods.fml.common.gameevent.TickEvent.Phase.END) drainAcks();
    }

    @cpw.mods.fml.common.eventhandler.SubscribeEvent
    public void logout(cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent event) {
        for (Ack ack : ACKS) if (ack.player == event.player && ACKS.remove(ack)) ACK_COUNT.decrementAndGet();
        PLAYER_ACKS.remove(event.player);
        for (State state : STATES.values()) state.recipients.remove(event.player);
    }

    public static void acknowledge(EntityPlayerMP player, PacketAckPanelHistory ack) {
        if (player == null) return;
        if (ACK_COUNT.incrementAndGet() > 1024) {
            ACK_COUNT.decrementAndGet();
            return;
        }
        java.util.concurrent.atomic.AtomicInteger pending = PLAYER_ACKS
            .computeIfAbsent(player, ignored -> new java.util.concurrent.atomic.AtomicInteger());
        if (pending.incrementAndGet() > 128) {
            pending.decrementAndGet();
            ACK_COUNT.decrementAndGet();
            return;
        }
        ACKS.offer(new Ack(player, ack));
    }

    private static void drainAcks() {
        Ack entry;
        int remaining = 128;
        while (remaining-- > 0 && (entry = ACKS.poll()) != null) {
            ACK_COUNT.decrementAndGet();
            EntityPlayerMP player = entry.player;
            java.util.concurrent.atomic.AtomicInteger pending = PLAYER_ACKS.get(player);
            if (pending != null) pending.decrementAndGet();
            PacketAckPanelHistory ack = entry.packet;
            if (!MonitorCapabilities.serverSupports(player, MonitorCapabilities.PANELS) || player.worldObj == null
                || !player.worldObj.blockExists(ack.x, ack.y, ack.z)) continue;
            TileEntity tile = player.worldObj.getTileEntity(ack.x, ack.y, ack.z);
            if (!(tile instanceof TileEntityNetworkInfoPanel) || !watching(player, tile)) continue;
            State state = STATES.get(tile);
            if (state == null || !state.session.equals(ack.sessionId)
                || !ack.panelId.equals(((TileEntityNetworkInfoPanel) tile).getPortableMonitorId())) continue;
            Recipient recipient = state.recipients.get(player);
            if (recipient == null || recipient.connection != player.playerNetServerHandler.netManager) continue;
            TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
            acknowledge(
                recipient,
                ack.sequence,
                () -> TelemetryTransport.cancel(player, stream(panel)),
                () -> sendCompact(panel, state, player));
        }
    }

    /** A baseline change invalidates every retained packet encoded against the previous baseline. */
    static void acknowledge(Recipient recipient, long sequence, java.util.function.BooleanSupplier cancel,
        Runnable rebuild) {
        if (sequence == 0) {
            recipient.base = null;
            recipient.ack = 0;
            recipient.sent.clear();
            cancel.getAsBoolean();
            rebuild.run();
            return;
        }
        PortableMonitorRequestQueue.RenderCache sent = recipient.sent.get(sequence);
        if (sent == null || sequence <= recipient.ack) return;
        recipient.base = sent;
        recipient.ack = sequence;
        recipient.sent.entrySet()
            .removeIf(e -> e.getKey() < sequence);
        if (cancel.getAsBoolean()) rebuild.run();
    }

    @Override
    public void broadcastAEMonitorData(World world, int x, int y, int z, String chartKey,
        List<AEMonitorSample> chartSamples, Map<String, AEMonitorSample> monitorLatest,
        Map<String, Double> monitorAvg300s) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityNetworkInfoPanel)) return;
        TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
        State state = STATES.computeIfAbsent(panel, ignored -> new State());
        state.aePresent = true;
        state.chartKey = chartKey;
        com.miaokatze.gtswn.common.monitoring.SourceKey source = panel.getAEMonitorSourceKey();
        state.aeSource = source == null ? "" : source.toString();
        state.latest = new HashMap<>(monitorLatest);
        state.averages = new HashMap<>(monitorAvg300s);
        PacketSyncAEMonitorData legacy = null;
        for (EntityPlayer entity : new ArrayList<EntityPlayer>(world.playerEntities)) {
            if (!(entity instanceof EntityPlayerMP)) continue;
            EntityPlayerMP player = (EntityPlayerMP) entity;
            if (!watching(player, panel) || player.getDistanceSq(x + .5, y + .5, z + .5) > 4096) continue;
            if (MonitorCapabilities.serverSupports(player, MonitorCapabilities.PANELS))
                sendCompact(panel, state, player);
            else {
                if (legacy == null) legacy = new PacketSyncAEMonitorData(
                    x,
                    y,
                    z,
                    chartKey,
                    chartSamples,
                    monitorLatest,
                    monitorAvg300s);
                TelemetryTransport.offer(
                    player,
                    stream(panel) + ":ae",
                    legacy,
                    () -> watching(player, panel) && !panel.isInvalid()
                        && player.getDistanceSq(x + .5, y + .5, z + .5) <= 4096,
                    () -> {});
            }
        }
    }

    @Override
    public void broadcastEULiveData(TileEntityNetworkInfoPanel panel) {
        if (panel.getWorldObj() == null || panel.getWorldObj().isRemote) return;
        State state = STATES.computeIfAbsent(panel, ignored -> new State());
        S35PacketUpdateTileEntity legacy = null, full = null;
        for (EntityPlayer entity : new ArrayList<EntityPlayer>(panel.getWorldObj().playerEntities)) {
            if (!(entity instanceof EntityPlayerMP)) continue;
            EntityPlayerMP player = (EntityPlayerMP) entity;
            if (!watching(player, panel)) continue;
            if (MonitorCapabilities.serverSupports(player, MonitorCapabilities.PANELS))
                sendCompact(panel, state, player);
            else {
                Recipient recipient = recipient(state, player);
                if (recipient == null) continue;
                final long generation = ++recipient.legacyGeneration;
                // Build once per broadcast, not per recipient: the shared eight-delta counter advances once.
                if (legacy == null) legacy = (S35PacketUpdateTileEntity) panel.getLiveDescriptionPacket();
                if (!recipient.legacyNeedsFull && TelemetryTransport.trySendNativePanel(player, legacy)) continue;
                recipient.legacyNeedsFull = true;
                // Never queue a dependent delta: replacing/dropping it would break the old client's sequence chain.
                if (full == null) full = (S35PacketUpdateTileEntity) panel.getDescriptionPacket();
                TelemetryTransport.offerNativePanel(
                    player,
                    stream(panel) + ":eu",
                    full,
                    () -> watching(player, panel) && !panel.isInvalid()
                        && !MonitorCapabilities.serverSupports(player, MonitorCapabilities.PANELS),
                    () -> { recipient.legacyFullSent(generation); });
            }
        }
    }

    private static Recipient recipient(State state, EntityPlayerMP player) {
        Recipient recipient = state.recipients.get(player);
        if (recipient == null || recipient.connection != player.playerNetServerHandler.netManager) {
            if (state.recipients.size() >= 1024) return null;
            recipient = new Recipient(player.playerNetServerHandler.netManager);
            state.recipients.put(player, recipient);
        }
        return recipient;
    }

    private static void sendCompact(TileEntityNetworkInfoPanel panel, State state, EntityPlayerMP player) {
        TelemetryTransport.cancel(player, stream(panel) + ":eu");
        TelemetryTransport.cancel(player, stream(panel) + ":ae");
        Recipient recipient = recipient(state, player);
        if (recipient == null) return;
        String boundKey = panel.getChartItem() != null ? TileEntityNetworkInfoPanel.getAEKey(panel.getChartItem())
            : panel.getChartFluid() != null ? TileEntityNetworkInfoPanel.getAEKey(panel.getChartFluid()) : null;
        boolean aeCurrent = state.aePresent && (state.chartKey == null || state.chartKey.equals(boundKey));
        String chartKey = aeCurrent ? state.chartKey : boundKey;
        AEMonitorDataSet data = AEMonitorDataStore.get(panel.getWorldObj())
            .getIfPresent(panel.getAEMonitorDataKey());
        PortableMonitorRequestQueue.RenderCache next = PortableMonitorRequestQueue.renderCache(panel, data, chartKey);
        PacketSyncPanelHistory packet = new PacketSyncPanelHistory();
        packet.x = panel.xCoord;
        packet.y = panel.yCoord;
        packet.z = panel.zCoord;
        packet.panelId = panel.getPortableMonitorId();
        packet.sessionId = state.session;
        packet.sequence = ++sequence;
        packet.history = PortableHistoryPatch.between(recipient.base, next, true, true);
        packet.energy = panel.getCachedEu()
            .toString();
        packet.power = panel.getCachedEut();
        packet.status = panel.getCachedStatus();
        packet.aePresent = aeCurrent;
        // A live grid replacement must not relabel the previous grid's latest rows as new-source values.
        if (aeCurrent && state.aeSource.equals(next.render.aeSource)) {
            packet.latest.putAll(state.latest);
            packet.averages.putAll(state.averages);
        }
        final Recipient target = recipient;
        TelemetryTransport.offer(
            player,
            stream(panel),
            packet,
            () -> watching(player, panel) && !panel.isInvalid()
                && STATES.get(panel) == state
                && MonitorCapabilities.serverSupports(player, MonitorCapabilities.PANELS),
            () -> {
                target.sent.put(packet.sequence, next);
                while (target.sent.size() > 4) target.sent.remove(
                    target.sent.keySet()
                        .iterator()
                        .next());
            });
    }

    private static String stream(TileEntityNetworkInfoPanel panel) {
        return "panel:" + panel.getWorldObj().provider.dimensionId + ":" + panel.getPortableMonitorId();
    }

    private static boolean watching(EntityPlayerMP player, TileEntity tile) {
        return player.worldObj == tile.getWorldObj() && tile.getWorldObj() instanceof WorldServer
            && ((WorldServer) tile.getWorldObj()).getPlayerManager()
                .isPlayerWatchingChunk(player, tile.xCoord >> 4, tile.zCoord >> 4);
    }

    private static final class Ack {

        final EntityPlayerMP player;
        final PacketAckPanelHistory packet;

        Ack(EntityPlayerMP player, PacketAckPanelHistory packet) {
            this.player = player;
            this.packet = packet;
        }
    }

    private static final class State {

        final UUID session = UUID.randomUUID();
        final Map<EntityPlayerMP, Recipient> recipients = new WeakHashMap<>();
        boolean aePresent;
        String chartKey;
        String aeSource = "";
        Map<String, AEMonitorSample> latest = new HashMap<>();
        Map<String, Double> averages = new HashMap<>();
    }

    static final class Recipient {

        final Object connection;
        final LinkedHashMap<Long, PortableMonitorRequestQueue.RenderCache> sent = new LinkedHashMap<>();
        PortableMonitorRequestQueue.RenderCache base;
        long ack, legacyGeneration;
        boolean legacyNeedsFull;

        Recipient(Object connection) {
            this.connection = connection;
        }

        void legacyFullSent(long generation) {
            if (legacyGeneration == generation) legacyNeedsFull = false;
        }
    }
}
