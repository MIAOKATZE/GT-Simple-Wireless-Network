package com.miaokatze.gtswn.common.quantum;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import appeng.tile.networking.TileController;

/** Real removal and chunk unloading have deliberately separate registration lifecycles. */
public final class QuantumTileLifecycle {

    private static final Set<World> UNLOADING = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<TileEntity> UNLOADED_TILES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<TileEntity> REINSERTED_TILES = Collections.newSetFromMap(new WeakHashMap<>());

    private QuantumTileLifecycle() {}

    public static void worldUnloading(World world) {
        UNLOADING.add(world);
        QuantumControllerRegistry.get(world)
            .unload();
    }

    public static void worldLoaded(World world) {
        UNLOADING.remove(world);
    }

    public static void removed(TileEntity tile) {
        World world = tile.getWorldObj();
        if (world == null || world.isRemote
            || UNLOADING.contains(world)
            || UNLOADED_TILES.contains(tile)
            || REINSERTED_TILES.contains(tile)) return;
        if (tile instanceof QuantumIncorporationIdentity
            && !((QuantumIncorporationIdentity) tile).gtswn$getIncorporationIdentity()
                .isEmpty()) {
            QuantumIncorporationRegistry.get(world)
                .removed(world, tile);
        }
        if (tile instanceof TileController && QuantumControllerRegistry.get(world)
            .ownsController(tile)) {
            QuantumControllerEventHandler
                .cancelStructure(world, QuantumControllerRegistry.pack(tile.xCoord, tile.yCoord, tile.zCoord));
        }
    }

    public static void unloaded(TileEntity tile) {
        World world = tile.getWorldObj();
        if (world == null || world.isRemote) return;
        if (!(tile instanceof TileController) && (!(tile instanceof QuantumIncorporationIdentity)
            || ((QuantumIncorporationIdentity) tile).gtswn$getIncorporationIdentity()
                .isEmpty()))
            return;
        UNLOADED_TILES.add(tile);
        if (tile instanceof QuantumIncorporationIdentity
            && !((QuantumIncorporationIdentity) tile).gtswn$getIncorporationIdentity()
                .isEmpty()) {
            QuantumIncorporationRegistry.get(world)
                .unloadTile(tile);
        }
        if (tile instanceof TileController) QuantumControllerRegistry.get(world)
            .unloadTile(tile);
    }

    public static void validated(TileEntity tile) {
        UNLOADED_TILES.remove(tile);
        REINSERTED_TILES.remove(tile);
    }

    public static void reinserted(TileEntity tile) {
        REINSERTED_TILES.add(tile);
    }
}
