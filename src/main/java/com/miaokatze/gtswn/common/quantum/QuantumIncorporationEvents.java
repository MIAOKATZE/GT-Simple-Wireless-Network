package com.miaokatze.gtswn.common.quantum;

import java.util.Iterator;

import net.minecraft.world.ChunkPosition;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.event.world.WorldEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class QuantumIncorporationEvents {

    private long lastErrorTick = Long.MIN_VALUE;

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (WorldServer world : DimensionManager.getWorlds()) {
            try {
                QuantumIncorporationRegistry.get(world)
                    .tick(world);
            } catch (RuntimeException failure) {
                long now = world.getTotalWorldTime();
                if (lastErrorTick == Long.MIN_VALUE || now - lastErrorTick >= 200) {
                    lastErrorTick = now;
                    com.miaokatze.gtswn.main.GTSimpleWirelessNetwork.LOG.error("[量子并入] 维护异常", failure);
                }
            }
        }
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (!event.world.isRemote) {
            QuantumTileLifecycle.worldUnloading(event.world);
            QuantumIncorporationRegistry.get(event.world)
                .unload();
        }
    }

    @SubscribeEvent
    public void load(WorldEvent.Load event) {
        if (!event.world.isRemote) QuantumTileLifecycle.worldLoaded(event.world);
    }

    @SubscribeEvent
    public void explosion(ExplosionEvent.Detonate event) {
        if (event.world.isRemote) return;
        Iterator<ChunkPosition> iterator = event.getAffectedBlocks()
            .iterator();
        while (iterator.hasNext()) {
            ChunkPosition pos = iterator.next();
            if (QuantumIncorporationRegistry.isIncorporated(event.world, pos.chunkPosX, pos.chunkPosY, pos.chunkPosZ))
                iterator.remove();
        }
    }
}
