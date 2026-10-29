package com.miaokatze.gtswn.common.covers;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.engineering.EngineeringGrid;

public class WirelessNodeRangeSnapshotTest {

    private static final WirelessNodeIndexCodec.Probe VALID = (p, x, y, z,
        t) -> WirelessNodeIndexCodec.ProbeResult.VALID;

    @Test
    public void closedXBoundsIncludeAllYZBitsAndClampPackedWorldEdges() {
        WirelessNodeRegistry r = new WirelessNodeRegistry("range");
        for (int x : new int[] { -33554432, -65, -64, -1, 0, 64, 65, 33554431 }) {
            r.register(null, x, 0, -33554432, (byte) 0);
            r.register(null, x, 255, 33554431, (byte) 1);
        }
        assertEquals(
            8,
            r.snapshotInXRange(-64, 64)
                .size());
        assertEquals(
            2,
            r.snapshotInXRange(0, 0)
                .size());
        assertEquals(r.snapshot(null), r.snapshotInXRange(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertTrue(
            r.snapshotInXRange(65, -65)
                .isEmpty());
        assertTrue(
            r.snapshotInXRange(Integer.MIN_VALUE, -33554433)
                .isEmpty());
        assertTrue(
            r.snapshotInXRange(33554432, Integer.MAX_VALUE)
                .isEmpty());
    }

    @Test
    public void oldCandidateSnapshotsSurviveProbeRepairAndReload() {
        WirelessNodeRegistry r = new WirelessNodeRegistry("range");
        r.register(null, 0, 64, 0, (byte) 0);
        r.register(null, 1, 64, 0, (byte) 0);
        Map<Long, Byte> before = r.snapshotInXRange(-64, 64);
        WirelessNodeIndexCodec.RadiusSelection selected = WirelessNodeIndexCodec
            .selectWithinRadius(before, WirelessNodeIndexCodec.pack(0, 64, 0), 4096, 256, (p, x, y, z, t) -> {
                r.unregister(null, x, y, z);
                r.register(null, x, y, z, (byte) 1);
                return WirelessNodeIndexCodec.ProbeResult.VALID;
            });
        assertEquals(2, selected.selected.size());
        assertEquals(Byte.valueOf((byte) 0), before.get(WirelessNodeIndexCodec.pack(0, 64, 0)));
        r.prune(null, Collections.singleton(WirelessNodeIndexCodec.pack(0, 64, 0)));
        NBTTagCompound saved = new NBTTagCompound();
        r.writeToNBT(saved);
        r.register(null, -20, 64, -20, (byte) 0);
        r.readFromNBT(saved);
        assertEquals(r.snapshot(null), r.snapshotInXRange(-64, 64));
        assertEquals(
            1,
            r.snapshotInXRange(-64, 64)
                .size());
        r.readFromNBT(new NBTTagCompound());
        assertTrue(
            r.snapshotInXRange(-64, 64)
                .isEmpty());
        assertEquals(2, before.size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void candidatesCannotWriteThrough() {
        new WirelessNodeRegistry("range").snapshotInXRange(-64, 64)
            .put(0L, (byte) 0);
    }

    @Test
    public void dimensionConcentratedOnOneXSlabKeepsSharedSnapshot() {
        WirelessNodeRegistry r = new WirelessNodeRegistry("range");
        for (int i = 0; i < 32768; i++) r.register(null, 0, 64, 10000 + i, (byte) 0);
        assertSame(r.snapshot(null), r.snapshotInXRange(-64, 64));
        assertSame(r.snapshotInXRange(-64, 64), r.snapshotInXRange(-63, 65));
    }

    @Test
    public void boundedLookupMatchesWholeIndexAcrossNegativeCoordinatesTypesPruningAndLimits() {
        Random random = new Random(2034);
        WirelessNodeRegistry r = new WirelessNodeRegistry("range");
        for (int i = 0; i < 12000; i++) r.register(
            null,
            random.nextInt(1024) - 512,
            random.nextInt(256),
            random.nextInt(1024) - 512,
            (byte) (i % 2));
        for (int centerX : new int[] { -33554432, -65, -1, 0, 64, 33554431 }) {
            long center = WirelessNodeIndexCodec.pack(centerX, 64, 0);
            r.register(null, centerX, 64, 0, (byte) 0);
            for (int limit : new int[] { 0, 1, 256 }) {
                WirelessNodeIndexCodec.Probe probe = (p, x, y, z, t) -> x % 7 == 0
                    ? WirelessNodeIndexCodec.ProbeResult.INVALID
                    : x % 5 == 0 ? WirelessNodeIndexCodec.ProbeResult.UNLOADED
                        : WirelessNodeIndexCodec.ProbeResult.VALID;
                WirelessNodeIndexCodec.RadiusSelection full = WirelessNodeIndexCodec
                    .selectWithinRadius(r.snapshot(null), center, 4096, limit, probe);
                WirelessNodeIndexCodec.RadiusSelection bounded = WirelessNodeIndexCodec
                    .selectWithinRadius(r.snapshotInXRange(centerX - 64, centerX + 64), center, 4096, limit, probe);
                assertEquals(full.pruned, bounded.pruned);
                assertEquals(full.selected.size(), bounded.selected.size());
                for (int i = 0; i < full.selected.size(); i++) {
                    assertEquals(full.selected.get(i).packed, bounded.selected.get(i).packed);
                    assertEquals(full.selected.get(i).type, bounded.selected.get(i).type);
                }
            }
        }
    }

    @Test
    public void sparseDimensionOnlySuppliesLocalCandidatesWithoutDroppingRadiusBoundary() {
        WirelessNodeRegistry r = new WirelessNodeRegistry("range");
        for (int i = 0; i < 32768; i++) r.register(null, 10000 + i, 64, 10000, (byte) 0);
        r.register(null, -64, 64, 0, (byte) 0);
        r.register(null, 64, 64, 0, (byte) 1);
        r.register(null, 0, 64, 65, (byte) 0);
        Map<Long, Byte> candidates = r.snapshotInXRange(-64, 64);
        assertEquals(3, candidates.size());
        WirelessNodeIndexCodec.RadiusSelection result = WirelessNodeIndexCodec
            .selectWithinRadius(candidates, WirelessNodeIndexCodec.pack(0, 64, 0), 4096, 256, VALID);
        assertEquals(2, result.selected.size());
        assertTrue(result.pruned.isEmpty());
    }
    @Test
    public void dashboardSlabSkipsAnUnrelatedPrefixLargerThanTheWholeRefreshBudget() {
        WirelessNodeRegistry registry = new WirelessNodeRegistry("dashboard-range");
        for (int x = -20_000; x < -10_000; x++) registry.register(null, x, 64, 0, (byte) 0);
        long visible = WirelessNodeIndexCodec.pack(17, 64, 1);
        registry.register(null, 17, 64, 1, (byte) 0);
        assertEquals(Collections.singleton(visible), registry.readViewInXRange(16, 31).keySet());
    }

    @Test
    public void dashboardSlabIncludesSignedXAndEveryEncodedYAndZAtBothClosedEdges() {
        WirelessNodeRegistry registry = new WirelessNodeRegistry("dashboard-boundaries");
        Set<Long> expected = new HashSet<>();
        for (int x : new int[] { -33_554_432, -17, -16, 0, 15, 16, 33_554_431 })
            for (int y : new int[] { -2048, 0, 255, 2047 })
                for (int z : new int[] { -33_554_432, -1, 0, 33_554_431 }) {
                    registry.register(null, x, y, z, (byte) 0);
                    if (x >= -16 && x <= 15) expected.add(WirelessNodeIndexCodec.pack(x, y, z));
                }
        assertEquals(expected, registry.readViewInXRange(-16, 15).keySet());
        assertEquals(registry.readView(), registry.readViewInXRange(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertTrue(registry.readViewInXRange(16, 15).isEmpty());
        assertTrue(registry.readViewInXRange(Integer.MIN_VALUE, -33_554_433).isEmpty());
        assertTrue(registry.readViewInXRange(33_554_432, Integer.MAX_VALUE).isEmpty());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void dashboardSlabCannotMutateTheRegistry() {
        WirelessNodeRegistry registry = new WirelessNodeRegistry("dashboard-read-only");
        registry.register(null, 0, 64, 0, (byte) 0);
        registry.readViewInXRange(0, 15).clear();
    }

    @Test
    public void dashboardSlabUsesSafeClosedBoundsAtBothWorldBordersAndAcrossLegacyAxis() {
        for (boolean gt : new boolean[] { false, true }) for (boolean axis : new boolean[] { false, true }) {
            for (int block : new int[] { -29_999_984, -49, -32, -1, 0, 31, 48, 29_999_952 }) {
                int cell = EngineeringGrid.cellOfBlock(block, gt, axis);
                int min = EngineeringGrid.minBlock(cell, gt, axis);
                int max = EngineeringGrid.maxBlockExclusive(cell, gt, axis) - 1;
                WirelessNodeRegistry registry = new WirelessNodeRegistry("dashboard-edge");
                registry.register(null, min - 1, 0, 0, (byte) 0);
                registry.register(null, min, 0, 0, (byte) 0);
                registry.register(null, max, 0, 0, (byte) 0);
                registry.register(null, max + 1, 0, 0, (byte) 0);
                assertEquals(2, registry.readViewInXRange(min, max).size());
                assertTrue(min <= block && block <= max);
            }
        }
    }

}
