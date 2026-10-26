package com.miaokatze.gtswn.common.engineering;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;

import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Power;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Review;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;
import com.miaokatze.gtswn.common.engineering.power.EngineeringRegionPowerCollector;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.network.EngineeringTypedSnapshot;
import com.miaokatze.gtswn.network.PacketEngineeringCompact;
import com.miaokatze.gtswn.network.PacketEngineeringSnapshot;
import com.miaokatze.gtswn.network.PacketEngineeringSubscribe;
import com.miaokatze.gtswn.network.telemetry.ByteDelta;
import com.miaokatze.gtswn.network.telemetry.MonitorCapabilities;
import com.miaokatze.gtswn.network.telemetry.TelemetryTransport;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.common.GTWorldgenerator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** One bounded, short-lived subscription per connected player. All world reads execute on ServerTick END. */
public final class EngineeringDashboardSessions {

    public static final int TTL_TICKS = 200, UPDATE_TICKS = 20;
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> SESSIONS = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST_SENT = new HashMap<>();
    private static long tick;

    public static void enqueue(EntityPlayerMP player, PacketEngineeringSubscribe request) {
        if (player != null && request.valid) PENDING.put(player.getUniqueID(), new Pending(player, request));
    }

    public static void enqueueCompact(EntityPlayerMP player, PacketEngineeringSubscribe request, long ack, int fields) {
        if (player != null && request.valid)
            PENDING.put(player.getUniqueID(), new Pending(player, request, ack, fields));
    }

    private static boolean connected(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null && player != null
            && player.isEntityAlive()
            && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager.isChannelOpen()
            && server.getConfigurationManager()
                .func_152612_a(player.getCommandSenderName()) == player;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tick++;
        for (Map.Entry<UUID, Pending> entry : PENDING.entrySet()) {
            if (!PENDING.remove(entry.getKey(), entry.getValue())) continue;
            accept(entry.getValue());
        }
        long deadline = System.nanoTime() + 6_000_000L;
        Iterator<Session> iterator = SESSIONS.values()
            .iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            if (!connected(session.player) || session.player.dimension != session.playerDimension
                || tick - session.touched > TTL_TICKS) {
                session.close();
                iterator.remove();
                if (connected(session.player)) send(session.empty("expired"), session.player);
            }
        }
        // Expiration still checks every subscription. Refresh work advances a stable rotation across ticks,
        // so a saturated prefix cannot repeatedly consume all four slots before later players are considered.
        for (int updates = 0; updates < 4 && System.nanoTime() <= deadline; updates++) {
            Session session = nextDueSession();
            if (session == null) break;
            session.sent = tick;
            LAST_SENT.put(session.player.getUniqueID(), tick);
            try {
                Source actual = EngineeringTerminalSources.resolve(session.player, session.view.slot);
                if (actual.stack != session.terminal || !session.source.sameIdentity(actual)) {
                    send(session.empty(actual.present() ? "expired" : "empty"), session.player);
                    session.close();
                    SESSIONS.remove(session.player.getUniqueID());
                    continue;
                }
                session.sendSnapshot(session.snapshot(TerminalBaubles.terminals(session.player)));
            } catch (RuntimeException failure) {
                GTSimpleWirelessNetwork.LOG.error("[Engineering dashboard] Subscription failed", failure);
                send(session.empty("error"), session.player);
                session.close();
                SESSIONS.remove(session.player.getUniqueID());
            }
        }
    }

    private static Session nextDueSession() {
        for (int remaining = SESSIONS.size(); remaining > 0; remaining--) {
            Iterator<Map.Entry<UUID, Session>> iterator = SESSIONS.entrySet()
                .iterator();
            Map.Entry<UUID, Session> entry = iterator.next();
            UUID id = entry.getKey();
            Session session = entry.getValue();
            iterator.remove();
            SESSIONS.put(id, session);
            if (tick - session.sent >= UPDATE_TICKS) return session;
        }
        return null;
    }

    public static void clear() {
        PENDING.clear();
        for (Session session : SESSIONS.values()) session.close();
        SESSIONS.clear();
        LAST_SENT.clear();
        tick = 0L;
    }

    private static void accept(Pending pending) {
        EntityPlayerMP player = pending.player;
        PacketEngineeringSubscribe request = pending.request;
        Session old = SESSIONS.get(player.getUniqueID());
        if (request.close) {
            if (old != null && old.player == player && old.view.sessionId == request.sessionId) {
                SESSIONS.remove(player.getUniqueID());
                old.close();
            }
            return;
        }
        if (!connected(player)) return;
        if (pending.compact && !MonitorCapabilities.serverSupports(player, MonitorCapabilities.ENGINEERING)) return;
        Source source = EngineeringTerminalSources.resolve(player, request.slot);
        ItemStack terminal = source.stack;
        if (old != null && old.same(player, terminal, request)
            && old.source.sameIdentity(source)
            && request.matches(source)) {
            old.touched = tick;
            old.compact = pending.compact;
            old.fields = pending.fields;
            old.acknowledge(pending.ack);
            // Repeated keepalives cannot force an unbounded refresh. A new baseline resets only the comparison.
            if (request.baseline) old.review.reset();
            return;
        }
        if (old != null) old.close();
        Session session = new Session(player, source, request);
        session.compact = pending.compact;
        session.fields = pending.fields;
        if (!request.matches(source)) {
            SESSIONS.remove(player.getUniqueID());
            send(session.empty(source.present() ? "expired" : "empty"), player);
            return;
        }
        SESSIONS.put(player.getUniqueID(), session);
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.player.getUniqueID());
        Session session = SESSIONS.remove(event.player.getUniqueID());
        if (session != null) session.close();
        LAST_SENT.remove(event.player.getUniqueID());
    }

    @SubscribeEvent
    public void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        logout(new PlayerEvent.PlayerLoggedOutEvent(event.player));
    }

    private static void send(EngineeringSnapshot snapshot, EntityPlayerMP player) {
        if (connected(player)) {
            TelemetryTransport.cancel(player, "engineering");
            TelemetryTransport.control(player, new PacketEngineeringSnapshot(snapshot));
        }
    }

    private static boolean axisPattern() {
        return GTWorldgenerator.getOregenPattern() == GTWorldgenerator.OregenPattern.AXISSYMMETRICAL;
    }

    private static final class Pending {

        final EntityPlayerMP player;
        final PacketEngineeringSubscribe request;
        final boolean compact;
        final long ack;
        final int fields;

        Pending(EntityPlayerMP player, PacketEngineeringSubscribe request) {
            this(player, request, -1, 3);
        }

        Pending(EntityPlayerMP player, PacketEngineeringSubscribe request, long ack, int fields) {
            this.player = player;
            this.request = request;
            this.ack = ack;
            this.fields = fields;
            compact = ack >= 0;
        }
    }

    private static final class Session {

        final EntityPlayerMP player;
        final ItemStack terminal;
        final Source source;
        final PacketEngineeringSubscribe view;
        final int playerDimension;
        final boolean axis;
        final EngineeringPowerReview review = new EngineeringPowerReview();
        final EngineeringBoundPowerCollector bound = new EngineeringBoundPowerCollector();
        EngineeringRegionPowerCollector region;
        long touched = tick, sent = tick - UPDATE_TICKS;
        boolean compact;
        int fields = 3;
        long acknowledged, sentRevision, serial;
        byte[] baseline, sentBytes;

        void acknowledge(long revision) {
            if (revision == 0) {
                acknowledged = 0;
                baseline = null;
            } else if (revision == sentRevision && sentBytes != null) {
                if (revision != acknowledged) TelemetryTransport.cancel(player, "engineering");
                acknowledged = revision;
                baseline = sentBytes;
            }
        }

        void sendSnapshot(EngineeringSnapshot snapshot) {
            if (!"ok".equals(snapshot.status)) {
                baseline = sentBytes = null;
                acknowledged = 0;
                send(snapshot, player);
                return;
            }
            if (!compact || !MonitorCapabilities.serverSupports(player, MonitorCapabilities.ENGINEERING)) {
                TelemetryTransport
                    .offer(player, "engineering", new PacketEngineeringSnapshot(snapshot), this::authorized, null);
                return;
            }
            EngineeringSnapshot projected = new EngineeringSnapshot(
                snapshot.sessionId,
                snapshot.slot,
                0,
                snapshot.occupiedMask,
                snapshot.status,
                (fields & 1) != 0 ? snapshot.nodes : Collections.emptyList(),
                (fields & 2) != 0 ? snapshot.cells : Collections.emptyList(),
                snapshot.before,
                snapshot.current,
                snapshot.monitored,
                snapshot.unknown,
                snapshot.totalBound,
                snapshot.truncated,
                snapshot.coverageChanged,
                snapshot.coverageBoundOnly,
                snapshot.quantum,
                snapshot.axisSymmetrical,
                snapshot.patternResolved,
                snapshot.review);
            ByteBuf out = Unpooled.buffer();
            final byte[] bytes;
            try {
                new EngineeringTypedSnapshot(projected).toBytes(out);
                bytes = new byte[out.readableBytes()];
                out.readBytes(bytes);
            } finally {
                out.release();
            }
            final long target = baseline != null && java.util.Arrays.equals(bytes, baseline) ? acknowledged : ++serial;
            PacketEngineeringCompact packet = new PacketEngineeringCompact(
                view.sessionId,
                view.slot,
                acknowledged,
                target,
                snapshot.serverTick,
                ByteDelta.between(baseline, bytes));
            TelemetryTransport.offer(player, "engineering", packet, this::authorized, () -> {
                sentRevision = target;
                sentBytes = bytes;
            });
        }

        Session(EntityPlayerMP player, ItemStack terminal, PacketEngineeringSubscribe view) {
            this(player, new Source(terminal, SourceOrigin.BAUBLE), view);
        }

        Session(EntityPlayerMP player, Source source, PacketEngineeringSubscribe view) {
            this.player = player;
            this.source = source;
            this.terminal = source.stack;
            this.view = view;
            playerDimension = player.dimension;
            axis = axisPattern();
            sent = LAST_SENT.getOrDefault(player.getUniqueID(), tick - UPDATE_TICKS);
        }

        boolean same(EntityPlayerMP current, ItemStack stack, PacketEngineeringSubscribe request) {
            return current == player && terminal == stack
                && source.binding.equals(EngineeringTerminalSources.binding(stack))
                && current.dimension == playerDimension
                && axis == axisPattern()
                && view.sessionId == request.sessionId
                && view.slot == request.slot
                && view.dim == request.dim
                && view.gtGrid == request.gtGrid
                && view.boundOnly == request.boundOnly
                && view.minCellX == request.minCellX
                && view.minCellZ == request.minCellZ
                && view.maxCellX == request.maxCellX
                && view.maxCellZ == request.maxCellZ;
        }

        boolean authorized() {
            if (!connected(player) || player.dimension != playerDimension) return false;
            Source actual = EngineeringTerminalSources.resolve(player, view.slot);
            if (actual.stack != terminal || !source.sameIdentity(actual)) return false;
            com.miaokatze.gtswn.common.monitoring.MonitoringProfiles profiles = com.miaokatze.gtswn.common.monitoring.MonitoringProfiles
                .get(player.worldObj);
            if (view.slot == 2 && !profiles.canRead(
                player.getUniqueID(),
                com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.readTerminalId(terminal))) return false;
            if (com.miaokatze.gtswn.common.monitoring.MonitoringProfiles.hasProfileReference(terminal)) {
                UUID profileId = com.miaokatze.gtswn.common.monitoring.MonitoringProfiles
                    .readProfileReference(terminal);
                if (!profiles.canRead(player.getUniqueID(), profileId)) return false;
                if (view.slot >= 3 && !com.miaokatze.gtswn.common.monitoring.QuantumProfileAdapter
                    .matches(terminal, profiles.profileForTerminal(profileId))) return false;
            }
            if (view.slot >= 3 && !com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess.validate(player, terminal)
                && !com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess.canManage(player, terminal)) return false;
            return true;
        }

        EngineeringSnapshot snapshot(ItemStack[] terminals) {
            if (!authorized()) return empty("denied");
            int mask = mask(terminals);
            if (view.slot == 2) {
                if (view.dim != player.dimension) return empty("denied");
                if (region == null && !view.boundOnly) region = new EngineeringRegionPowerCollector();
                EngineeringPowerResult result = view.boundOnly ? bound.collect(player, terminal, view, axis)
                    : region.collect(player, terminal, view, axis, tick);
                Review comparison = review.observe(result, tick);
                return new EngineeringSnapshot(
                    view.sessionId,
                    view.slot,
                    tick,
                    mask,
                    "ok",
                    Collections.emptyList(),
                    result.cells,
                    review.before(),
                    result.total,
                    result.samples.size(),
                    result.unknown,
                    result.totalBound,
                    result.truncated,
                    comparison.addedMonitored > 0 || comparison.removedMonitored > 0,
                    result.boundOnly,
                    null,
                    axis,
                    GTWorldgenerator.isOregenPatternResolved(),
                    comparison);
            }
            EngineeringNetworkCollector collector = new EngineeringNetworkCollector();
            if (view.slot == 1) collector.link(player, view, axis);
            else if (view.slot >= 3) collector.quantum(player, terminal);
            return new EngineeringSnapshot(
                view.sessionId,
                view.slot,
                tick,
                mask,
                collector.status,
                collector.nodes,
                Collections.emptyList(),
                Power.ZERO,
                Power.ZERO,
                0,
                0,
                0,
                collector.truncated,
                false,
                false,
                collector.quantum,
                axis,
                GTWorldgenerator.isOregenPatternResolved());
        }

        EngineeringSnapshot empty(String status) {
            return new EngineeringSnapshot(
                view.sessionId,
                view.slot,
                tick,
                connected(player) ? mask(TerminalBaubles.terminals(player)) : 0,
                status,
                Collections.emptyList(),
                Collections.emptyList(),
                Power.ZERO,
                Power.ZERO,
                0,
                0,
                0,
                false,
                false,
                view.boundOnly,
                null,
                axis,
                GTWorldgenerator.isOregenPatternResolved());
        }

        void close() {
            TelemetryTransport.cancel(player, "engineering");
            baseline = sentBytes = null;
            if (region != null) region.close();
        }

        private static int mask(ItemStack[] terminals) {
            int mask = 0;
            for (int i = 0; i < terminals.length; i++) if (terminals[i] != null) mask |= 1 << i;
            return mask;
        }
    }
}
