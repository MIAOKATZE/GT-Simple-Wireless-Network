package com.miaokatze.gtswn.common.testworld;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.panel.NetworkInfoDataSet;
import com.miaokatze.gtswn.common.panel.NetworkInfoDataStore;

/** Preserves exactly the isolated synthetic owner's global EU sample dataset and GT team key. */
final class PanelOwnerScope implements AutoCloseable {

    private final String key;
    private final NetworkInfoDataStore store;
    private final NBTTagCompound before;
    private final TestEuScope eu;

    PanelOwnerScope(World testWorld, UUID owner) {
        key = owner.toString();
        eu = new TestEuScope(owner);
        World overworld = DimensionManager.getWorld(0);
        store = NetworkInfoDataStore.get(overworld == null ? testWorld : overworld);
        NetworkInfoDataSet data = store.getIfPresent(key);
        before = data == null ? null : data.toNBT();
    }

    @Override
    public void close() {
        store.remove(key);
        if (before != null) store.getOrCreate(key)
            .readFromNBT(before);
        store.markDirty();
        eu.close();
    }
}
