package com.miaokatze.gtswn.common.covers;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class WirelessNodeSnapshotReuseTest {

    @Test
    public void stableSnapshotsAreSharedAndOnlyEffectiveMutationsInvalidate() {
        WirelessNodeRegistry registry = new WirelessNodeRegistry("snapshot-test");
        long position = WirelessNodeIndexCodec.pack(1, 64, 2);
        Map<Long, Byte> empty = registry.snapshot(null);
        assertSame(empty, registry.snapshot(null));
        registry.register(null, 1, 64, 2, WirelessNodeRegistry.TYPE_ENERGY);
        Map<Long, Byte> energy = registry.snapshot(null);
        assertTrue(empty.isEmpty());
        registry.register(null, 1, 64, 2, WirelessNodeRegistry.TYPE_ENERGY);
        registry.unregister(null, 9, 64, 9);
        registry.prune(null, Collections.singleton(123L));
        assertSame(energy, registry.snapshot(null));
        registry.register(null, 1, 64, 2, WirelessNodeRegistry.TYPE_DYNAMO);
        Map<Long, Byte> dynamo = registry.snapshot(null);
        assertNotSame(energy, dynamo);
        assertEquals(Byte.valueOf(WirelessNodeRegistry.TYPE_ENERGY), energy.get(position));
        assertEquals(Byte.valueOf(WirelessNodeRegistry.TYPE_DYNAMO), dynamo.get(position));
        registry.prune(null, Collections.singleton(position));
        assertTrue(
            registry.snapshot(null)
                .isEmpty());
        assertEquals(1, dynamo.size());
    }

    @Test
    public void removalAndReloadKeepPreviouslyReturnedSnapshotsDetached() {
        WirelessNodeRegistry registry = new WirelessNodeRegistry("snapshot-test");
        registry.register(null, 1, 64, 2, WirelessNodeRegistry.TYPE_ENERGY);
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        Map<Long, Byte> original = registry.snapshot(null);
        registry.unregister(null, 1, 64, 2);
        Map<Long, Byte> removed = registry.snapshot(null);
        assertTrue(removed.isEmpty());
        registry.readFromNBT(saved);
        Map<Long, Byte> restored = registry.snapshot(null);
        assertEquals(original, restored);
        assertNotSame(original, restored);
        registry.readFromNBT(new NBTTagCompound());
        assertTrue(
            registry.snapshot(null)
                .isEmpty());
        assertEquals(1, restored.size());
        assertTrue(removed.isEmpty());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void snapshotCannotWriteThroughToRegistry() {
        new WirelessNodeRegistry("snapshot-test").snapshot(null)
            .put(0L, WirelessNodeRegistry.TYPE_ENERGY);
    }
}
