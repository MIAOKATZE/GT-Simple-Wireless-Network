package com.miaokatze.gtswn.common.quantum;

import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import appeng.tile.networking.TileController;

/** Invalidation only for GTSWN's controller identity and local safety policy, never native AE permissions. */
public final class QuantumPolicyEpoch {

    private static final AtomicLong EPOCH = new AtomicLong();

    private QuantumPolicyEpoch() {}

    /** Opt in only when policy setters, NBT reads and controller lifecycle changes emit this epoch. */
    public interface TrackedController {
    }

    public static long current() {
        return EPOCH.get();
    }

    public static void changed() {
        EPOCH.incrementAndGet();
    }

    /** NBT can be read before a controller has a world; no world lookup or retained world reference is needed. */
    public static void controllerChanged(TileEntity tile) {
        if (!(tile instanceof TileController)) return;
        World world = tile.getWorldObj();
        if (world == null || !world.isRemote) changed();
    }
}
