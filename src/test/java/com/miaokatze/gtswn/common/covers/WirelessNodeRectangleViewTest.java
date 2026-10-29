package com.miaokatze.gtswn.common.covers;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.engineering.EngineeringGrid;

public class WirelessNodeRectangleViewTest {

    private static Map<Long, Byte> rectangle(WirelessNodeRegistry registry, int minX, int minZ, int maxX, int maxZ) {
        Map<Long, Byte> result = new HashMap<>();
        for (Map.Entry<Long, Byte> entry : registry.readViewInChunkRectangle(minX, minZ, maxX, maxZ))
            result.put(entry.getKey(), entry.getValue());
        return result;
    }

    @Test
    public void unrelatedSameXAndSameZPrefixesNeverEnterTheSelectedRectangle() {
        WirelessNodeRegistry registry = new WirelessNodeRegistry("rectangle");
        for (int i = 1; i <= 10_000; i++) {
            registry.register(null, 0, 64, i, (byte) 0);
            registry.register(null, i + 10_000, 64, 20_000, (byte) 0);
        }
        long visible = WirelessNodeIndexCodec.pack(0, 64, 20_000);
        registry.register(null, 0, 64, 20_000, (byte) 1);
        assertEquals(Collections.singletonMap(visible, (byte) 1), rectangle(registry, 0, 1250, 0, 1250));
    }

    @Test
    public void negativeChunkEdgesTypeChangesPruningAndReloadKeepBothIndexesConsistent() throws Exception {
        WirelessNodeRegistry registry = new WirelessNodeRegistry("signed");
        for (int x : new int[] { -17, -16, -1, 0, 15, 16 })
            for (int z : new int[] { -17, -16, -1, 0, 15, 16 }) registry.register(null, x, 255, z, (byte) 0);
        Map<Long, Byte> expected = new HashMap<>();
        for (int x : new int[] { -16, -1, 0, 15 })
            for (int z : new int[] { -16, -1, 0, 15 }) expected.put(WirelessNodeIndexCodec.pack(x, 255, z), (byte) 0);
        assertEquals(expected, rectangle(registry, -1, -1, 0, 0));
        long changed = WirelessNodeIndexCodec.pack(-16, 255, -1);
        registry.register(null, -16, 255, -1, (byte) 1);
        expected.put(changed, (byte) 1);
        registry.unregister(null, 0, 255, 0);
        expected.remove(WirelessNodeIndexCodec.pack(0, 255, 0));
        long pruned = WirelessNodeIndexCodec.pack(-1, 255, 15);
        registry.prune(null, Arrays.asList(pruned, pruned));
        expected.remove(pruned);
        assertEquals(expected, rectangle(registry, -1, -1, 0, 0));
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        registry.register(null, 7, 64, 7, (byte) 1);
        registry.readFromNBT(saved);
        assertEquals(expected, rectangle(registry, -1, -1, 0, 0));
        registry.prune(null, new ArrayList<>(registry.readView().keySet()));
        assertTrue(registry.readView().isEmpty());
        Field index = WirelessNodeRegistry.class.getDeclaredField("chunkNodes");
        index.setAccessible(true);
        assertTrue(((Map<?, ?>) index.get(registry)).isEmpty());
        registry.readFromNBT(saved);
        registry.readFromNBT(new NBTTagCompound());
        assertTrue(rectangle(registry, -1, -1, 0, 0).isEmpty());
        assertTrue(((Map<?, ?>) index.get(registry)).isEmpty());
    }

    @Test
    public void chunkAndPositionOrderIsStableAcrossRegistrationOrder() {
        List<Long> first = ordered(new int[] { 3, -16, 18, -1 });
        assertEquals(first, ordered(new int[] { 18, -1, -16, 3 }));
    }

    @Test
    public void fullSixtyFourCellViewsFitForMcGtAndBothNegativeAxisPatterns() {
        for (boolean gt : new boolean[] { false, true }) for (boolean axis : new boolean[] { false, true }) {
            for (int width : new int[] { 8, 64 }) {
                int height = 64 / width;
                int minX = EngineeringGrid.minBlock(-4, gt, axis);
                int minZ = EngineeringGrid.minBlock(-4, gt, axis);
                int maxX = EngineeringGrid.maxBlockExclusive(width - 5, gt, axis) - 1;
                int maxZ = EngineeringGrid.maxBlockExclusive(height - 5, gt, axis) - 1;
                WirelessNodeRegistry registry = new WirelessNodeRegistry("grid-rectangle");
                registry.register(null, minX, 0, minZ, (byte) 0);
                registry.register(null, maxX, 255, maxZ, (byte) 1);
                registry.register(null, minX - 1, 0, minZ, (byte) 0);
                registry.register(null, maxX, 0, maxZ + 1, (byte) 0);
                assertEquals(2, rectangle(registry, minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4).size());
            }
        }
    }

    private static List<Long> ordered(int[] xs) {
        WirelessNodeRegistry registry = new WirelessNodeRegistry("ordered");
        for (int x : xs) registry.register(null, x, 64, -x, (byte) 0);
        List<Long> result = new ArrayList<>();
        for (Map.Entry<Long, Byte> entry : registry.readViewInChunkRectangle(-2, -2, 2, 2)) result.add(entry.getKey());
        return result;
    }

    @Test
    public void packedWorldEdgesAndRectangleSizeAreBoundedBeforeIteration() {
        WirelessNodeRegistry registry = new WirelessNodeRegistry("edges");
        registry.register(null, -33_554_432, 0, -33_554_432, (byte) 0);
        registry.register(null, 33_554_431, 255, 33_554_431, (byte) 1);
        assertEquals(1, rectangle(registry, -2_097_152, -2_097_152, -2_097_152, -2_097_152).size());
        assertEquals(1, rectangle(registry, 2_097_151, 2_097_151, 2_097_151, 2_097_151).size());
        assertTrue(rectangle(registry, Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0).isEmpty());
        assertTrue(rectangle(registry, 0, 0, 576, 0).isEmpty());
        assertTrue(rectangle(registry, 0, 0, 24, 24).isEmpty());
        assertTrue(rectangle(registry, 1, 0, 0, 0).isEmpty());
        registry.register(null, 0, 64, 0, (byte) 0);
        assertEquals(1, rectangle(registry, 0, 0, 23, 23).size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void entriesCannotWriteThroughToEitherIndex() {
        WirelessNodeRegistry registry = new WirelessNodeRegistry("immutable-entry");
        registry.register(null, 0, 0, 0, (byte) 0);
        registry.readViewInChunkRectangle(0, 0, 0, 0).iterator().next().setValue((byte) 1);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void iteratorsCannotRemoveFromEitherIndex() {
        WirelessNodeRegistry registry = new WirelessNodeRegistry("immutable-iterator");
        registry.register(null, 0, 0, 0, (byte) 0);
        Iterator<Map.Entry<Long, Byte>> iterator = registry.readViewInChunkRectangle(0, 0, 0, 0).iterator();
        iterator.next();
        iterator.remove();
    }
}
