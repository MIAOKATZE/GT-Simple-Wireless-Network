package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.miaokatze.gtswn.network.PacketQuantumIncorporationDelta;
import com.miaokatze.gtswn.network.PacketSyncQuantumIncorporationState;
import com.miaokatze.gtswn.network.PacketSyncQuantumIncorporationState.Position;
import com.miaokatze.gtswn.network.telemetry.MonitorCapabilities;
import com.miaokatze.gtswn.network.telemetry.TelemetryTransport;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Server main-thread, event-driven visual state. Neither world scans nor chunk loads are needed. */
public final class QuantumIncorporationVisualSync {

    private static final Map<World, Map<Long, Watchers>> WORLDS = new IdentityHashMap<>();
    private static final Map<EntityPlayerMP, Set<Watchers>> PLAYERS = new IdentityHashMap<>();

    private static final Map<EntityPlayerMP, Map<String, Ack>> ACKS = new ConcurrentHashMap<>();
    private static long tick;

    public static void enqueueAck(EntityPlayerMP player, int dimension, int x, int z, long revision) {
        if (player == null || revision < 0 || ACKS.size() >= 1024) return;
        Map<String, Ack> pending = ACKS.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>());
        String key = dimension + ":" + x + ":" + z;
        if (pending.size() < 512 || pending.containsKey(key)) pending.put(key, new Ack(dimension, x, z, revision));
    }

    private static String stream(Watchers watchers) {
        return "quantum:" + watchers.world.provider.dimensionId + ":" + watchers.x + ":" + watchers.z;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tick++;
        for (Map.Entry<EntityPlayerMP, Map<String, Ack>> entry : ACKS.entrySet()) {
            EntityPlayerMP player = entry.getKey();
            Map<String, Ack> acks = entry.getValue();
            if (!MonitorCapabilities.connected(player)) {
                ACKS.remove(player, acks);
                continue;
            }
            if (!MonitorCapabilities.serverSupports(player, MonitorCapabilities.QUANTUM)) continue;
            Map<Long, Watchers> chunks = WORLDS.get(player.worldObj);
            if (chunks == null) continue;
            for (Map.Entry<String, Ack> pending : acks.entrySet()) {
                Ack ack = pending.getValue();
                if (!acks.remove(pending.getKey(), ack)) continue;
                if (ack.dimension != player.dimension) continue;
                Watchers watchers = chunks.get(chunkKey(ack.x, ack.z));
                Delivery delivery = watchers == null ? null : watchers.deliveries.get(player);
                if (delivery == null) continue;
                if (ack.revision == delivery.sentRevision && delivery.sent != null) {
                    if (ack.revision != delivery.acknowledged && TelemetryTransport.cancel(player, stream(watchers)))
                        watchers.dirty = true;
                    delivery.baseline = delivery.sent;
                    delivery.acknowledged = ack.revision;
                } else if (ack.revision == 0 && tick - delivery.lastReset >= 20) {
                    delivery.baseline = null;
                    delivery.acknowledged = 0;
                    delivery.lastReset = tick;
                    watchers.dirty = true;
                }
            }
        }
        for (Map<Long, Watchers> chunks : WORLDS.values()) for (Watchers watchers : chunks.values()) {
            if (!watchers.dirty && watchers.retry.isEmpty()) continue;
            boolean changed = watchers.dirty;
            watchers.dirty = false;
            PacketSyncQuantumIncorporationState current = snapshot(watchers);
            // Retained central-queue entries need no polling. Only rejected recipients retry the current snapshot.
            for (EntityPlayerMP player : new ArrayList<>(changed ? watchers.players : watchers.retry))
                transmit(watchers, player, current);
        }
    }

    private static void transmit(Watchers watchers, EntityPlayerMP player,
        PacketSyncQuantumIncorporationState current) {
        java.util.function.BooleanSupplier authorized = () -> watchers.players.contains(player)
            && player.worldObj == watchers.world
            && MonitorCapabilities.connected(player);
        TelemetryTransport.Admission admission;
        if (!MonitorCapabilities.serverSupports(player, MonitorCapabilities.QUANTUM)) {
            admission = TelemetryTransport.offerStatus(player, stream(watchers), current, authorized, null);
        } else {
            Delivery delivery = watchers.deliveries.computeIfAbsent(player, ignored -> new Delivery());
            final long revision = ++watchers.serial;
            PacketQuantumIncorporationDelta packet = new PacketQuantumIncorporationDelta(
                current,
                delivery.baseline,
                delivery.acknowledged,
                revision);
            admission = TelemetryTransport.offerStatus(player, stream(watchers), packet, authorized, () -> {
                delivery.sent = current;
                delivery.sentRevision = revision;
            });
        }
        retainRejected(watchers.retry, player, admission);
    }

    static void retainRejected(Set<EntityPlayerMP> retry, EntityPlayerMP player,
        TelemetryTransport.Admission admission) {
        if (admission == TelemetryTransport.Admission.REJECTED) retry.add(player);
        else retry.remove(player);
    }

    public static void clear() {
        WORLDS.clear();
        PLAYERS.clear();
        ACKS.clear();
        tick = 0;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @SubscribeEvent
    public void watch(ChunkWatchEvent.Watch event) {
        World world = event.player.worldObj;
        if (world.isRemote) return;
        int x = event.chunk.chunkXPos, z = event.chunk.chunkZPos;
        Watchers watchers = WORLDS.computeIfAbsent(world, ignored -> new HashMap<>())
            .computeIfAbsent(chunkKey(x, z), ignored -> new Watchers(world, x, z));
        watchers.players.add(event.player);
        PLAYERS.computeIfAbsent(event.player, ignored -> new HashSet<>())
            .add(watchers);
        transmit(watchers, event.player, snapshot(watchers));
    }

    @SubscribeEvent
    public void unwatch(ChunkWatchEvent.UnWatch event) {
        Map<Long, Watchers> chunks = WORLDS.get(event.player.worldObj);
        if (chunks == null) return;
        Watchers watchers = chunks.get(chunkKey(event.chunk.chunkXPos, event.chunk.chunkZPos));
        if (watchers == null) return;
        remove(event.player, watchers);
        TelemetryTransport.control(
            event.player,
            new PacketSyncQuantumIncorporationState(
                watchers.world.provider.dimensionId,
                watchers.x,
                watchers.z,
                java.util.Collections.emptyList()));
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        ACKS.remove(event.player);
        Set<Watchers> watched = PLAYERS.remove(event.player);
        if (watched == null) return;
        for (Watchers watchers : watched) {
            watchers.players.remove(event.player);
            watchers.deliveries.remove(event.player);
            watchers.retry.remove(event.player);
            if (event.player instanceof EntityPlayerMP)
                TelemetryTransport.cancel((EntityPlayerMP) event.player, stream(watchers));
            prune(watchers);
        }
    }

    @SubscribeEvent
    public void dimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        Set<Watchers> watched = PLAYERS.get(player);
        if (watched == null) return;
        for (Watchers watchers : new ArrayList<>(watched)) {
            if (watchers.world != player.worldObj) remove(player, watchers);
        }
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        Map<Long, Watchers> chunks = WORLDS.remove(event.world);
        if (chunks == null) return;
        for (Watchers watchers : chunks.values()) {
            for (EntityPlayerMP player : watchers.players) {
                TelemetryTransport.cancel(player, stream(watchers));
                Set<Watchers> watched = PLAYERS.get(player);
                if (watched != null) {
                    watched.remove(watchers);
                    if (watched.isEmpty()) PLAYERS.remove(player);
                }
            }
        }
    }

    /** Call after an add/remove/forget has updated the registry. Only this chunk's observers receive it. */
    public static void changed(World world, int x, int y, int z) {
        if (world == null || world.isRemote) return;
        Map<Long, Watchers> chunks = WORLDS.get(world);
        if (chunks == null) return;
        Watchers watchers = chunks.get(chunkKey(x >> 4, z >> 4));
        if (watchers == null || watchers.players.isEmpty()) return;
        watchers.dirty = true; // Multiple registry changes in one tick share one immutable chunk snapshot.
    }

    private static PacketSyncQuantumIncorporationState snapshot(Watchers watchers) {
        ArrayList<Position> positions = new ArrayList<>();
        for (QuantumIncorporationRegistry.Entry entry : QuantumIncorporationRegistry.get(watchers.world)
            .snapshotChunk(watchers.x, watchers.z)) {
            int x = QuantumControllerRegistry.unpackX(entry.position);
            int y = QuantumControllerRegistry.unpackY(entry.position);
            int z = QuantumControllerRegistry.unpackZ(entry.position);
            if (y >= 0 && y < 256 && QuantumIncorporationRegistry.isIncorporated(watchers.world, x, y, z)) {
                positions.add(
                    new Position(
                        (y << 8) | ((z & 15) << 4) | (x & 15),
                        UUID.fromString(entry.identity()),
                        entry.getColorIndex()));
            }
        }
        return new PacketSyncQuantumIncorporationState(
            watchers.world.provider.dimensionId,
            watchers.x,
            watchers.z,
            positions);
    }

    private static void remove(EntityPlayerMP player, Watchers watchers) {
        watchers.players.remove(player);
        watchers.deliveries.remove(player);
        watchers.retry.remove(player);
        TelemetryTransport.cancel(player, stream(watchers));
        Set<Watchers> watched = PLAYERS.get(player);
        if (watched != null) {
            watched.remove(watchers);
            if (watched.isEmpty()) PLAYERS.remove(player);
        }
        prune(watchers);
    }

    private static void prune(Watchers watchers) {
        if (!watchers.players.isEmpty()) return;
        Map<Long, Watchers> chunks = WORLDS.get(watchers.world);
        if (chunks == null) return;
        chunks.remove(chunkKey(watchers.x, watchers.z));
        if (chunks.isEmpty()) WORLDS.remove(watchers.world);
    }

    private static final class Delivery {

        PacketSyncQuantumIncorporationState baseline, sent;
        long acknowledged, sentRevision, lastReset = -20;
    }

    private static final class Ack {

        final int dimension, x, z;
        final long revision;

        Ack(int dimension, int x, int z, long revision) {
            this.dimension = dimension;
            this.x = x;
            this.z = z;
            this.revision = revision;
        }
    }

    private static final class Watchers {

        private final World world;
        private final int x, z;
        private final Set<EntityPlayerMP> players = new HashSet<>();
        private final Map<EntityPlayerMP, Delivery> deliveries = new IdentityHashMap<>();
        private final Set<EntityPlayerMP> retry = new HashSet<>();
        private boolean dirty;
        private long serial;

        private Watchers(World world, int x, int z) {
            this.world = world;
            this.x = x;
            this.z = z;
        }
    }
}
