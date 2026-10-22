package com.miaokatze.gtswn.common.engineering.power;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.junit.Test;

import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Power;
import com.miaokatze.gtswn.network.PacketEngineeringSubscribe;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** Exercise actual resumable discovery and identity invalidation without loading a world or GT machines. */
public class EngineeringRegionPowerCollectorTest {

    @Test
    public void manyChunksAreDiscoveredAcrossCollectionsWithinChunkBudget() throws Exception {
        EngineeringRegionPowerCollector collector = collector(7, 7);
        Chunk[] loaded = new Chunk[64];
        for (int i = 0; i < loaded.length; i++) loaded[i] = chunk(new LinkedHashMap<>());
        discover(collector, loaded);
        assertEquals(EngineeringRegionPowerCollector.CHUNK_BUDGET, scanned(collector));
        discover(collector, loaded);
        assertEquals(64, scanned(collector));
    }

    @Test
    public void largeRawTileMapsResumeWithoutCopyingOrUnboundedIteration() throws Exception {
        EngineeringRegionPowerCollector collector = collector(0, 0);
        Map<Object, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < EngineeringRegionPowerCollector.TILE_BUDGET * 2 + 1; i++) raw.put(i, new Object());
        Chunk[] loaded = { chunk(raw) };
        discover(collector, loaded);
        assertEquals(0, scanned(collector));
        discover(collector, loaded);
        assertEquals(0, scanned(collector));
        discover(collector, loaded);
        assertEquals(1, scanned(collector));
    }

    @Test
    public void rawMapMutationWhilePausedRestartsAndRetainsIncompleteCoverage() throws Exception {
        EngineeringRegionPowerCollector collector = collector(0, 0);
        Map<Object, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < EngineeringRegionPowerCollector.TILE_BUDGET + 1; i++) raw.put(i, new Object());
        Chunk[] loaded = { chunk(raw) };
        discover(collector, loaded);
        raw.put(-1, new Object());
        discover(collector, loaded);
        assertEquals(0, scanned(collector));
        discover(collector, loaded);
        assertEquals(0, scanned(collector));
        discover(collector, loaded);
        assertEquals(1, scanned(collector));
    }

    @Test
    public void unloadingOrReplacingTileDropsTheOldObservationIdentity() throws Exception {
        EngineeringRegionPowerCollector collector = collector(0, 0);
        Map<String, Object> machines = (Map<String, Object>) field(EngineeringRegionPowerCollector.class, "machines")
            .get(collector);
        Class<?> machineType = Class.forName(EngineeringRegionPowerCollector.class.getName() + "$Machine");
        Constructor<?> constructor = machineType
            .getDeclaredConstructor(TileEntity.class, IMetaTileEntity.class, int.class, String.class);
        constructor.setAccessible(true);
        TileEntity tile = (TileEntity) allocate(TileEntity.class);
        Object previous = constructor.newInstance(tile, null, 0, "0:0:0:0@1");
        Method discard = EngineeringRegionPowerCollector.class
            .getDeclaredMethod("discardReplacedMachines", Chunk[].class);
        discard.setAccessible(true);
        machines.put("0:0:0:0", previous);
        discard.invoke(collector, (Object) new Chunk[] { null });
        assertTrue(machines.isEmpty());
        machines.put("0:0:0:0", previous);
        discard.invoke(collector, (Object) new Chunk[] { chunk(new LinkedHashMap<>()) });
        assertTrue(machines.isEmpty());
    }

    @Test
    public void closingRegionDoesNotCancelASharedDitWatch() throws Exception {
        Object server = new Object(), machine = new Object();
        DeviceFlowMonitor.clear();
        DeviceFlowMonitor.tick(server, 100);
        DeviceFlowMonitor.watch(machine, 1000);
        EngineeringRegionPowerCollector collector = collector(0, 0);
        collector.close();
        assertTrue(DeviceFlowMonitor.isWatched(machine));
        assertEquals(0, ((List<?>) field(EngineeringRegionPowerCollector.class, "chunks").get(collector)).size());
        DeviceFlowMonitor.clear();
    }

    @Test
    public void oversizedRegionalSumRemainsFiniteAndNeverBecomesZero() {
        Power total = new Power(Double.MAX_VALUE, 1, Double.MAX_VALUE, 1)
            .plus(new Power(Double.MAX_VALUE, 3, Double.MAX_VALUE, 3));
        assertEquals(Double.MAX_VALUE, total.instIn, 0);
        assertEquals(Double.MAX_VALUE, total.avgIn, 0);
        assertEquals(4, total.instOut, 0);
    }

    private static EngineeringRegionPowerCollector collector(int maxX, int maxZ) throws Exception {
        EngineeringRegionPowerCollector collector = new EngineeringRegionPowerCollector();
        PacketEngineeringSubscribe request = new PacketEngineeringSubscribe();
        request.maxCellX = maxX;
        request.maxCellZ = maxZ;
        Method configure = EngineeringRegionPowerCollector.class
            .getDeclaredMethod("configure", World.class, PacketEngineeringSubscribe.class, boolean.class);
        configure.setAccessible(true);
        configure.invoke(collector, null, request, false);
        return collector;
    }

    private static Chunk chunk(Map<?, ?> rawTiles) throws Exception {
        Chunk chunk = (Chunk) allocate(Chunk.class);
        field(Chunk.class, "chunkTileEntityMap").set(chunk, rawTiles);
        return chunk;
    }

    private static void discover(EngineeringRegionPowerCollector collector, Chunk[] loaded) throws Exception {
        Method discover = EngineeringRegionPowerCollector.class.getDeclaredMethod("discover", Chunk[].class);
        discover.setAccessible(true);
        discover.invoke(collector, (Object) loaded);
    }

    private static int scanned(EngineeringRegionPowerCollector collector) throws Exception {
        int scanned = 0;
        for (Object state : (List<?>) field(EngineeringRegionPowerCollector.class, "chunks").get(collector))
            if (field(state.getClass(), "scanned").getBoolean(state)) scanned++;
        return scanned;
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
