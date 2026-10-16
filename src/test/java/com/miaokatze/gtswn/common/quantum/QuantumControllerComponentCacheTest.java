package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class QuantumControllerComponentCacheTest {

    @Test
    public void repeatedQueriesAcrossEveryMemberDoNotTraverseTheGraphAgain() throws Exception {
        assertOneTraversal(7);
        assertOneTraversal(16);
    }

    private static void assertOneTraversal(int side) throws Exception {
        QuantumControllerRegistry registry = new QuantumControllerRegistry("test");
        CountingSet controllers = new CountingSet();
        Field backing = QuantumControllerRegistry.class.getDeclaredField("controllers");
        backing.setAccessible(true);
        backing.set(registry, controllers);
        Set<Long> positions = new HashSet<>();
        for (int x = 0; x < side; x++) for (int y = 64; y < 64 + side; y++)
            for (int z = 0; z < side; z++) positions.add(QuantumControllerRegistry.pack(x, y, z));
        registry.quantizeAll(positions);
        controllers.containsCalls = 0;
        Set<Long> component = registry.registeredStructure(QuantumControllerRegistry.pack(0, 64, 0));
        assertEquals(positions, component);
        int coldContains = controllers.containsCalls;
        assertEquals(1 + 6 * positions.size(), coldContains);
        for (int repeat = 0; repeat < 3; repeat++)
            for (long member : positions) assertSame(component, registry.registeredStructure(member));
        assertEquals("Warm queries must not repeat even one adjacency probe", coldContains, controllers.containsCalls);
    }

    @Test
    public void bridgeAdditionAndRemovalInvalidateAllMembersWithoutMutatingPublishedSnapshots() {
        QuantumControllerRegistry registry = new QuantumControllerRegistry("test");
        registry.quantize(0, 64, 0);
        registry.quantize(2, 64, 0);
        Set<Long> first = registry.registeredStructure(pos(0));
        Set<Long> second = registry.registeredStructure(pos(2));
        registry.quantizeAll(Collections.singleton(pos(1)));
        Set<Long> merged = registry.registeredStructure(pos(2));
        assertEquals(3, merged.size());
        assertSame(merged, registry.registeredStructure(pos(0)));
        assertSame(merged, registry.registeredStructure(pos(1)));
        assertEquals(Collections.singleton(pos(0)), first);
        assertEquals(Collections.singleton(pos(2)), second);
        registry.dequantize(pos(1));
        Set<Long> splitFirst = registry.registeredStructure(pos(0));
        Set<Long> splitSecond = registry.registeredStructure(pos(2));
        assertEquals(Collections.singleton(pos(0)), splitFirst);
        assertEquals(Collections.singleton(pos(2)), splitSecond);
        assertNotSame(splitFirst, splitSecond);
        assertEquals(3, merged.size());
    }

    @Test
    public void noOpMutationsAndTileUnloadKeepTheSameSavedGraphSnapshot() {
        QuantumControllerRegistry registry = new QuantumControllerRegistry("test");
        registry.quantize(0, 64, 0);
        Set<Long> component = registry.registeredStructure(pos(0));
        long revision = registry.getRevision();
        registry.quantize(0, 64, 0);
        registry.quantizeAll(Collections.singleton(pos(0)));
        registry.dequantize(pos(99));
        registry.unload();
        assertEquals(revision, registry.getRevision());
        assertSame(component, registry.registeredStructure(pos(0)));
    }

    @Test
    public void returnedSnapshotsAreImmutableAndRemainIterableWhileMembersAreRemoved() {
        QuantumControllerRegistry registry = new QuantumControllerRegistry("test");
        for (int x = 0; x < 3; x++) registry.quantize(x, 64, 0);
        Set<Long> component = registry.registeredStructure(pos(1));
        try {
            component.remove(pos(0));
            fail("Membership snapshots must not be mutable by callers");
        } catch (UnsupportedOperationException expected) {}
        for (long member : component) registry.dequantize(member);
        assertEquals(3, component.size());
        assertTrue(
            registry.getAll()
                .isEmpty());
        assertTrue(
            registry.registeredStructure(pos(1))
                .isEmpty());
    }

    @Test
    public void missingCoordinatesAreNotCachedAndNewlyQuantizedCoordinatesBecomeVisible() throws Exception {
        QuantumControllerRegistry registry = new QuantumControllerRegistry("test");
        for (int x = 100; x < 200; x++) assertTrue(
            registry.registeredStructure(pos(x))
                .isEmpty());
        Field cache = QuantumControllerRegistry.class.getDeclaredField("structureByMember");
        cache.setAccessible(true);
        assertTrue(((java.util.Map<?, ?>) cache.get(registry)).isEmpty());
        registry.quantize(150, 64, 0);
        assertEquals(Collections.singleton(pos(150)), registry.registeredStructure(pos(150)));
    }

    @Test
    public void nbtReplacementAndEmptyReloadDiscardCachedTopologyAndPreserveLegacyParsing() {
        QuantumControllerRegistry registry = new QuantumControllerRegistry("test");
        registry.quantize(0, 64, 0);
        Set<Long> original = registry.registeredStructure(pos(0));
        NBTTagCompound replacement = new NBTTagCompound();
        replacement.setIntArray("controllers", new int[] { -1, 16, 64, 0, -1, 17, 64, 0, -1, 16, 64, 0, 123 });
        registry.readFromNBT(replacement);
        assertTrue(
            registry.registeredStructure(pos(0))
                .isEmpty());
        Set<Long> restored = registry.registeredStructure(pos(16));
        assertEquals(2, restored.size());
        assertSame(restored, registry.registeredStructure(pos(17)));
        assertEquals(Collections.singleton(pos(0)), original);
        registry.readFromNBT(new NBTTagCompound());
        assertTrue(
            registry.registeredStructure(pos(16))
                .isEmpty());
        assertEquals(2, restored.size());
    }

    @Test
    public void savedComponentsLargerThanTheFiniteChannelFloodLimitAreNotTruncated() {
        int size = 513;
        int[] positions = new int[4 * size];
        for (int x = 0; x < size; x++) {
            positions[4 * x] = -1;
            positions[4 * x + 1] = x;
            positions[4 * x + 2] = 64;
        }
        NBTTagCompound saved = new NBTTagCompound();
        saved.setIntArray("controllers", positions);
        QuantumControllerRegistry registry = new QuantumControllerRegistry("test");
        registry.readFromNBT(saved);
        Set<Long> component = registry.registeredStructure(pos(0));
        assertEquals(size, component.size());
        assertSame(component, registry.registeredStructure(pos(size - 1)));
        registry.unload();
        assertSame(component, registry.registeredStructure(pos(size / 2)));
    }

    @Test
    public void separateWorldRegistriesNeverShareComponentSnapshots() {
        QuantumControllerRegistry first = new QuantumControllerRegistry("dimension_0");
        QuantumControllerRegistry second = new QuantumControllerRegistry("dimension_minus_1");
        first.quantize(0, 64, 0);
        second.quantize(0, 64, 0);
        Set<Long> a = first.registeredStructure(pos(0));
        Set<Long> b = second.registeredStructure(pos(0));
        assertEquals(a, b);
        assertNotSame(a, b);
        first.dequantize(pos(0));
        assertSame(b, second.registeredStructure(pos(0)));
    }

    private static long pos(int x) {
        return QuantumControllerRegistry.pack(x, 64, 0);
    }

    private static final class CountingSet extends HashSet<Long> {

        int containsCalls;

        @Override
        public boolean contains(Object value) {
            containsCalls++;
            return super.contains(value);
        }
    }
}
