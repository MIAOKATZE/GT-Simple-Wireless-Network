package com.miaokatze.gtswn.mixins.minecraft;

import java.util.ArrayList;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.common.quantum.QuantumTileLifecycle;

/** Mark unloading before AEBaseTile's override calls invalidate without calling super.onChunkUnload. */
@Mixin(Chunk.class)
public abstract class QuantumChunkLifecycleMixin {

    @Shadow
    public Map<ChunkPosition, TileEntity> chunkTileEntityMap;
    @Shadow
    public boolean isChunkLoaded;

    @Inject(method = "onChunkUnload", at = @At("HEAD"))
    private void gtswn$unloadTiles(CallbackInfo callback) {
        for (TileEntity tile : new ArrayList<>(chunkTileEntityMap.values())) QuantumTileLifecycle.unloaded(tile);
    }

    @Inject(method = "onChunkLoad", at = @At("HEAD"))
    private void gtswn$loadTiles(CallbackInfo callback) {
        for (TileEntity tile : chunkTileEntityMap.values()) QuantumTileLifecycle.validated(tile);
    }

    @Inject(
        method = "func_150812_a",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntity;invalidate()V"))
    private void gtswn$replaceTile(int x, int y, int z, TileEntity replacement, CallbackInfo callback) {
        TileEntity previous = chunkTileEntityMap.get(new ChunkPosition(x, y, z));
        if (previous == replacement) QuantumTileLifecycle.reinserted(previous);
        else if (isChunkLoaded && previous != null) QuantumTileLifecycle.removed(previous);
    }

    @Inject(method = "removeTileEntity", at = @At("HEAD"))
    private void gtswn$removeTile(int x, int y, int z, CallbackInfo callback) {
        if (!isChunkLoaded) return;
        TileEntity previous = chunkTileEntityMap.get(new ChunkPosition(x, y, z));
        if (previous != null) QuantumTileLifecycle.removed(previous);
    }
}
