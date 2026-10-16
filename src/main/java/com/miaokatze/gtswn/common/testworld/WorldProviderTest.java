package com.miaokatze.gtswn.common.testworld;

import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManagerHell;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

/** Environment settings are local to this provider; shared WorldInfo gamerules are never changed. */
public final class WorldProviderTest extends WorldProvider {

    @Override
    protected void registerWorldChunkManager() {
        worldChunkMgr = new WorldChunkManagerHell(BiomeGenBase.plains, 0);
        hasNoSky = false;
    }

    @Override
    public IChunkProvider createChunkGenerator() {
        return new TestChunkProvider(worldObj);
    }

    @Override
    public String getDimensionName() {
        return "GTSWN-TEST";
    }

    @Override
    public String getSaveFolder() {
        return "GTSWN-TEST";
    }

    @Override
    public boolean canRespawnHere() {
        return false;
    }

    @Override
    public boolean canDoLightning(Chunk chunk) {
        return false;
    }

    @Override
    public boolean canDoRainSnowIce(Chunk chunk) {
        return false;
    }

    @Override
    public ChunkCoordinates getSpawnPoint() {
        return new ChunkCoordinates(0, 65, 0);
    }

    @Override
    public float calculateCelestialAngle(long time, float partial) {
        return 0;
    }

    @Override
    public void updateWeather() {
        worldObj.prevRainingStrength = worldObj.rainingStrength = 0;
        worldObj.prevThunderingStrength = worldObj.thunderingStrength = 0;
    }
}
