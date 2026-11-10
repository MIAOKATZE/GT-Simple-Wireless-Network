package com.miaokatze.gtswn.common.testworld;

import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Blocks;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

/** Flat inert canvas. Mark populated before publication, avoiding third party IWorldGenerator decoration. */
public final class TestChunkProvider implements IChunkProvider {

    private final World world;

    public TestChunkProvider(World world) {
        this.world = world;
    }

    @Override
    public Chunk provideChunk(int x, int z) {
        Block[] blocks = new Block[16 * 16 * 128];
        for (int bx = 0; bx < 16; bx++) for (int bz = 0; bz < 16; bz++) {
            int offset = (bx * 16 + bz) * 128;
            blocks[offset] = Blocks.bedrock;
            for (int y = 1; y < 63; y++) blocks[offset + y] = Blocks.stone;
            blocks[offset + 63] = Blocks.stone;
        }
        Chunk chunk = new Chunk(world, blocks, x, z);
        for (int bx = 0; bx < 16; bx++)
            for (int bz = 0; bz < 16; bz++) chunk.getBiomeGenForWorldCoords(bx, bz, world.getWorldChunkManager());
        chunk.isTerrainPopulated = true;
        chunk.generateSkylightMap();
        return chunk;
    }

    @Override
    public Chunk loadChunk(int x, int z) {
        return provideChunk(x, z);
    }

    @Override
    public boolean chunkExists(int x, int z) {
        return true;
    }

    @Override
    public void populate(IChunkProvider provider, int x, int z) {}

    @Override
    public boolean saveChunks(boolean all, IProgressUpdate progress) {
        return true;
    }

    @Override
    public boolean unloadQueuedChunks() {
        return false;
    }

    @Override
    public boolean canSave() {
        return true;
    }

    @Override
    public String makeString() {
        return "GTSWN-TEST flat canvas";
    }

    @Override
    public List getPossibleCreatures(EnumCreatureType type, int x, int y, int z) {
        return Collections.emptyList();
    }

    @Override
    public ChunkPosition func_147416_a(World world, String name, int x, int y, int z) {
        return null;
    }

    @Override
    public int getLoadedChunkCount() {
        return 0;
    }

    @Override
    public void recreateStructures(int x, int z) {}

    @Override
    public void saveExtraData() {}
}
