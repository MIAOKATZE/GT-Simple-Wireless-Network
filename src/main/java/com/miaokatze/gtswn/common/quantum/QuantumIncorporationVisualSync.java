package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketSyncQuantumIncorporationState;
import com.miaokatze.gtswn.network.PacketSyncQuantumIncorporationState.Position;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/** Server main-thread, event-driven visual state. Neither world scans nor chunk loads are needed. */
public final class QuantumIncorporationVisualSync {

    private static final Map<World, Map<Long, Watchers>> WORLDS = new IdentityHashMap<>();
    private static final Map<EntityPlayerMP, Set<Watchers>> PLAYERS = new IdentityHashMap<>();

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
        GTSWNPacketHandler.NETWORK.sendTo(snapshot(watchers), event.player);
    }

    @SubscribeEvent
    public void unwatch(ChunkWatchEvent.UnWatch event) {
        Map<Long, Watchers> chunks = WORLDS.get(event.player.worldObj);
        if (chunks == null) return;
        Watchers watchers = chunks.get(chunkKey(event.chunk.chunkXPos, event.chunk.chunkZPos));
        if (watchers == null) return;
        remove(event.player, watchers);
        GTSWNPacketHandler.NETWORK.sendTo(
            new PacketSyncQuantumIncorporationState(
                watchers.world.provider.dimensionId,
                watchers.x,
                watchers.z,
                java.util.Collections.emptyList()),
            event.player);
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        Set<Watchers> watched = PLAYERS.remove(event.player);
        if (watched == null) return;
        for (Watchers watchers : watched) {
            watchers.players.remove(event.player);
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
        PacketSyncQuantumIncorporationState packet = snapshot(watchers);
        for (EntityPlayerMP player : watchers.players) GTSWNPacketHandler.NETWORK.sendTo(packet, player);
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

    private static final class Watchers {

        private final World world;
        private final int x, z;
        private final Set<EntityPlayerMP> players = new HashSet<>();

        private Watchers(World world, int x, int z) {
            this.world = world;
            this.x = x;
            this.z = z;
        }
    }
}
