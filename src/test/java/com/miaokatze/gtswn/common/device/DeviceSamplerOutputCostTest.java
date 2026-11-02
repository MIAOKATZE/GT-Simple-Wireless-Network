package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.DimensionManager;

import org.junit.Test;

import gregtech.api.metatileentity.BaseMetaTileEntity;

/** Executes the production sampler against native GT output arrays, without a Minecraft process. */
public class DeviceSamplerOutputCostTest {

    @Test
    @SuppressWarnings("unchecked")
    public void outputScaleNeverCallsItemNamesAndRetainsObservedPower() throws Exception {
        int dimension = 37181;
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        field(World.class, "perWorldStorage").set(world, new MapStorage(null));
        field(World.class, "chunkProvider").set(
            world,
            Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { IChunkProvider.class }, (p, m, a) -> {
                if (m.getName()
                    .equals("chunkExists")) return true;
                throw new AssertionError("Sampler must not load chunks: " + m.getName());
            }));
        FixtureMachine machine = new FixtureMachine();
        FixtureTile tile = new FixtureTile();
        tile.setWorldObj(world);
        tile.setMetaTileEntity(machine);
        machine.setBaseMetaTileEntity(tile);
        world.tile = tile;
        Map<Integer, WorldServer> worlds = (Map<Integer, WorldServer>) field(DimensionManager.class, "worlds")
            .get(null);
        WorldServer previous = worlds.put(dimension, world);
        UUID holder = UUID.randomUUID(), terminal = UUID.randomUUID();
        DeviceTerminalDataStore.ensureActiveTerminal(holder, terminal);
        try {
            DeviceTerminalDataStore store = DeviceTerminalDataStore.get(world);
            DeviceTerminalDataStore.TerminalData data = store.getOrCreateTerminal(terminal);
            String key = dimension + ":0:0:0";
            data.boundKeys.add(key);
            DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
            record.metaTileId = tile.getMetaTileID();
            data.records.put(key, record);
            Method drain = DeviceSampleScheduler.class
                .getDeclaredMethod("drainSamples", MinecraftServer.class, World.class);
            drain.setAccessible(true);
            ItemStack output = new ItemStack(new Item() {

                @Override
                public String getItemStackDisplayName(ItemStack stack) {
                    throw new AssertionError("Terminal sampling must not call third-party output names");
                }
            });
            for (int length : new int[] { 8, 1024, 65536 }) {
                machine.mOutputItems = new ItemStack[length];
                Arrays.fill(machine.mOutputItems, output);
                record.recipeIn = record.recipeOut = "old recipe";
                DeviceSampleScheduler scheduler = new DeviceSampleScheduler();
                ((Collection<String>) field(DeviceSampleScheduler.class, "workQueue").get(scheduler)).add(key);
                ((Collection<Map.Entry<UUID, DeviceTerminalDataStore.TerminalData>>) field(
                    DeviceSampleScheduler.class,
                    "samplingTerminals").get(scheduler)).add(new AbstractMap.SimpleImmutableEntry<>(terminal, data));
                Object clock = new Object();
                DeviceFlowMonitor.tick(clock, 100);
                DeviceFlowMonitor.watch(machine, 1000);
                DeviceFlowMonitor.tick(clock, 101);
                DeviceFlowMonitor.Reading outer = DeviceFlowMonitor.enter(machine);
                DeviceFlowMonitor.energyChange(machine, 10000, 8000);
                DeviceFlowMonitor.energyChange(new Object(), 0, 4000);
                DeviceFlowMonitor.leave(outer);
                DeviceFlowMonitor.tick(clock, 102);
                drain.invoke(scheduler, null, world);
                assertEquals(DeviceTerminalDataStore.STATE_RUNNING, record.state);
                assertEquals(100, record.instIn);
                assertEquals(200, record.instOut);
                assertEquals(1000, record.inAvg, 1e-9);
                assertEquals(2000, record.outAvg, 1e-9);
                assertEquals("", record.recipeIn);
                assertEquals("", record.recipeOut);
                assertTrue(record.sampledFrom(tile));
            }
        } finally {
            DeviceTerminalDataStore.onPlayerLoggedOut(holder);
            DeviceFlowMonitor.clear();
            if (previous == null) worlds.remove(dimension);
            else worlds.put(dimension, previous);
        }
    }

    private static final class FixtureMachine extends DeviceMachineIdentityTest.IdentityMulti {

        @Override
        public String getLocalName() {
            return "sampler fixture";
        }
    }

    private static final class FixtureTile extends BaseMetaTileEntity {

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public boolean isAllowedToWork() {
            return true;
        }
    }

    private static final class FixtureWorld extends WorldServer {

        TileEntity tile;

        private FixtureWorld() {
            super(null, (ISaveHandler) null, "sampler-fixture", 0, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public long getTotalWorldTime() {
            return 102;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return tile;
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field(unsafe, "theUnsafe").get(null), type);
    }
}
