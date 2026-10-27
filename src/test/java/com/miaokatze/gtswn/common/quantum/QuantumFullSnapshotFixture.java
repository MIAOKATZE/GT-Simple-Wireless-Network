package com.miaokatze.gtswn.common.quantum;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;

import appeng.api.networking.IGrid;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;

final class QuantumFullSnapshotFixture implements AutoCloseable {

    final QuantumConnectionSecurityContextTest.MaintenanceFixture fixture;
    final AutoCloseable clock;
    final QuantumNetworkData expected = new QuantumNetworkData();

    static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {}
        throw new NoSuchFieldException(name);
    }

    static class GridProxy extends AENetworkProxy {

        final IGrid grid;

        GridProxy(IGridProxyable host, IGrid grid) {
            super(host, "proxy", null, true);
            this.grid = grid;
        }

        @Override
        public IGrid getGrid() {
            return grid;
        }
    }

    @SuppressWarnings("unchecked")
    QuantumFullSnapshotFixture() throws Exception {
        Class<?> type = Class.forName("com.miaokatze.gtswn.common.quantum.QuantumMaintenanceRetryTest$ServerClock");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        clock = (AutoCloseable) ctor.newInstance();
        fixture = new QuantumConnectionSecurityContextTest.MaintenanceFixture(true, 1, 0, true);
        field(World.class, "worldInfo").set(fixture.world, new WorldInfo(new NBTTagCompound()));
        Object host = fixture.own;
        field(host.getClass(), "gridProxy").set(host, new GridProxy((IGridProxyable) host, fixture.grid.grid));
        QuantumNetworkData.clearCache();
        AnchorKey key = QuantumControllerRegistry.get(fixture.world)
            .canonicalKey(new AnchorKey(37175, 0, 64, 0));
        Class<?> entry = Class.forName("com.miaokatze.gtswn.common.quantum.QuantumNetworkData$FullCacheEntry");
        Constructor<?> cache = entry
            .getDeclaredConstructor(IGrid.class, long.class, long.class, QuantumNetworkData.class);
        cache.setAccessible(true);
        expected.online = true;
        expected.anchorDim = 37175;
        expected.anchorY = 64;
        expected.totalChannels = 123;
        ((Map<AnchorKey, Object>) field(QuantumNetworkData.class, "FULL_CACHE").get(null)).put(
            key,
            cache.newInstance(
                fixture.grid.grid,
                0L,
                QuantumControllerRegistry.get(fixture.world)
                    .getRevision(),
                expected));
    }

    QuantumNetworkData get() {
        return QuantumNetworkData.assemble(37175, 0, 64, 0);
    }

    public void close() throws Exception {
        QuantumNetworkData.clearCache();
        try {
            fixture.close();
        } finally {
            clock.close();
        }
    }
}
