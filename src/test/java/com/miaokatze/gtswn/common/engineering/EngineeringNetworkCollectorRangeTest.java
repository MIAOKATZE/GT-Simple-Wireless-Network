package com.miaokatze.gtswn.common.engineering;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;

import org.junit.Test;

import com.miaokatze.gtswn.common.covers.WirelessNodeRegistry;
import com.miaokatze.gtswn.network.PacketEngineeringSubscribe;

public class EngineeringNetworkCollectorRangeTest {

    @Test
    public void everyRefreshReachesTheSelectedChunkBehindTenThousandSameXPositions() throws Exception {
        FixtureWorld world = allocate(FixtureWorld.class);
        Field storage = World.class.getDeclaredField("perWorldStorage");
        storage.setAccessible(true);
        storage.set(world, new MapStorage(null));
        WirelessNodeRegistry registry = WirelessNodeRegistry.get(world);
        for (int z = 1; z <= 10_000; z++) registry.register(null, 0, 64, z, (byte) 0);
        registry.register(null, 0, 64, 20_000, (byte) 0);
        EntityPlayerMP player = allocate(EntityPlayerMP.class);
        player.worldObj = world;
        PacketEngineeringSubscribe view = new PacketEngineeringSubscribe();
        view.minCellZ = view.maxCellZ = 1250;
        assertTrue(EngineeringGrid.valid(0, 1250, 0, 1250, false));
        for (int refresh = 0; refresh < 3; refresh++) {
            EngineeringNetworkCollector collector = new EngineeringNetworkCollector();
            Field deadline = EngineeringNetworkCollector.class.getDeclaredField("deadline");
            deadline.setAccessible(true);
            deadline.setLong(collector, Long.MAX_VALUE); // Isolate the hard inspection bound from machine timing.
            collector.link(player, view, false);
            assertEquals(refresh + 1, world.loadedChecks);
            assertEquals(20_000, world.lastZ);
            assertFalse(collector.truncated);
            Field inspected = EngineeringNetworkCollector.class.getDeclaredField("inspected");
            inspected.setAccessible(true);
            assertEquals(1, inspected.getInt(collector));
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(
            unsafe.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), type));
    }

    private static final class FixtureWorld extends World {

        int loadedChecks, lastZ;

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            loadedChecks++;
            lastZ = z;
            return false;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            throw new AssertionError("The collector must not probe an unloaded chunk");
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public Entity getEntityByID(int id) {
            return null;
        }
    }
}
