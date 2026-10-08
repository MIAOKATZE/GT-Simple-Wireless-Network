package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.miaokatze.gtswn.network.PacketSyncQuantumIncorporationState;
import com.miaokatze.gtswn.network.PacketSyncQuantumIncorporationState.Position;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Client main-thread cache, indexed by observed chunk; each position binds to one TE object only. */
public final class QuantumIncorporationClientState {

    private static final Map<Long, Map<Integer, State>> CHUNKS = new HashMap<>();
    private static World currentWorld;

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static void useWorld(World world) {
        if (currentWorld != world) {
            CHUNKS.clear();
            currentWorld = world;
        }
    }

    public static void accept(World world, PacketSyncQuantumIncorporationState packet) {
        if (world == null || !world.isRemote
            || !packet.isValid()
            || world.provider.dimensionId != packet.getDimension()) return;
        useWorld(world);
        long key = chunkKey(packet.getChunkX(), packet.getChunkZ());
        Map<Integer, State> previous = CHUNKS.get(key);
        if (packet.getPositions()
            .isEmpty()) {
            CHUNKS.remove(key);
            return;
        }
        Map<Integer, State> replacement = new HashMap<>();
        for (Position position : packet.getPositions()) {
            int local = position.local;
            State state = previous == null ? null : previous.get(local);
            // Preserve object identity (including invalidated entries) across unrelated changes in this chunk.
            if (state == null || !state.sameIdentity(position)) state = new State(
                (packet.getChunkX() << 4) + (local & 15),
                local >>> 8,
                (packet.getChunkZ() << 4) + ((local >>> 4) & 15),
                world.getTotalWorldTime(),
                position);
            state.tile(world);
            replacement.put(local, state);
        }
        CHUNKS.put(key, replacement);
    }

    /** Only synchronized entries are inspected; the world/loaded tile list is never scanned. */
    public static Iterable<TileEntity> tiles(World world) {
        useWorld(world);
        if (world == null || !world.isRemote || CHUNKS.isEmpty()) return Collections.emptyList();
        ArrayList<TileEntity> tiles = new ArrayList<>();
        for (Map<Integer, State> chunk : CHUNKS.values()) {
            for (State state : chunk.values()) {
                TileEntity tile = state.tile(world);
                if (tile != null) tiles.add(tile);
            }
        }
        return tiles;
    }

    public static boolean registered(World world, int x, int y, int z) {
        useWorld(world);
        if (world == null || !world.isRemote || y < 0 || y >= 256) return false;
        Map<Integer, State> chunk = CHUNKS.get(chunkKey(x >> 4, z >> 4));
        if (chunk == null) return false;
        State state = chunk.get((y << 8) | ((z & 15) << 4) | (x & 15));
        return state != null && state.tile(world) != null;
    }

    @SubscribeEvent
    public void unloadChunk(ChunkEvent.Unload event) {
        if (event.world == currentWorld)
            CHUNKS.remove(chunkKey(event.getChunk().xPosition, event.getChunk().zPosition));
    }

    @SubscribeEvent
    public void unloadWorld(WorldEvent.Unload event) {
        if (event.world == currentWorld) useWorld(null);
    }

    private static final class State {

        private final int x, y, z;
        private final long receivedTick;
        private final long identityMost, identityLeast;
        private TileEntity bound;
        private boolean invalid;

        private State(int x, int y, int z, long receivedTick, Position position) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.receivedTick = receivedTick;
            identityMost = position.identityMost;
            identityLeast = position.identityLeast;
        }

        private boolean sameIdentity(Position position) {
            return identityMost == position.identityMost && identityLeast == position.identityLeast;
        }

        private TileEntity tile(World world) {
            if (invalid) return null;
            // ChunkWatch can precede chunk/TE data. Allow a short binding window without loading anything.
            if (!world.getChunkProvider()
                .chunkExists(x >> 4, z >> 4)) {
                if (world.getTotalWorldTime() - receivedTick > 100) {
                    invalid = true;
                    bound = null;
                }
                return null;
            }
            TileEntity actual = world.getTileEntity(x, y, z);
            if (bound == null) {
                if (actual != null && !actual.isInvalid()) bound = actual;
                else if (world.getTotalWorldTime() - receivedTick > 100) invalid = true;
            } else if (actual != bound || bound.isInvalid() || bound.getWorldObj() != world) {
                invalid = true;
                bound = null;
            }
            return bound;
        }
    }
}
