package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class NetworkInfoDataStoreReloadTest {

    @Test
    public void everyNbtReplacementInvalidatesResolvedReferencesWithoutDroppingSavedHistory() {
        NetworkInfoDataStore store = new NetworkInfoDataStore("live");
        NetworkInfoDataSet first = store.getOrCreate("account");
        first.addSample(BigInteger.ONE, 100, 1000);
        first.updateRequestTick(100);
        NetworkInfoDataStore replacement = new NetworkInfoDataStore("snapshot");
        replacement.getOrCreate("account").addSample(BigInteger.TEN, 100, 1000);
        NBTTagCompound saved = new NBTTagCompound();
        replacement.writeToNBT(saved);
        int revision = store.getRevision();
        store.readFromNBT(saved);
        assertNotEquals(revision, store.getRevision());
        NetworkInfoDataSet restored = store.getIfPresent("account");
        assertNotSame(first, restored);
        assertEquals(BigInteger.TEN, restored.newest().eu);
        assertFalse(restored.isRequestActive(100));
        revision = store.getRevision();
        store.readFromNBT(saved);
        assertNotEquals(revision, store.getRevision());
        assertNotSame(restored, store.getIfPresent("account"));
        assertEquals(BigInteger.TEN, store.getIfPresent("account").newest().eu);
        revision = store.getRevision();
        store.readFromNBT(new NBTTagCompound());
        assertNotEquals(revision, store.getRevision());
        assertNull(store.getIfPresent("account"));
    }

    @Test
    public void activeSnapshotCannotReplaceTheStoreEntryBehindItsRevision() {
        NetworkInfoDataStore store = new NetworkInfoDataStore("snapshot");
        NetworkInfoDataSet original = store.getOrCreate("account");
        original.updateRequestTick(100);
        List<Map.Entry<String, NetworkInfoDataSet>> entries = store.getActiveEntries(100);
        assertEquals(1, entries.size());
        try {
            entries.get(0).setValue(new NetworkInfoDataSet());
            fail("The snapshot's map entries must not be live mutable aliases");
        } catch (UnsupportedOperationException expected) {}
        assertSame(original, store.getIfPresent("account"));
        entries.clear();
        assertSame(original, store.getIfPresent("account"));
    }
}
