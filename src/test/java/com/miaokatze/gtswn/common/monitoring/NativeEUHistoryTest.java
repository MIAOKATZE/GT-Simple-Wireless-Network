package com.miaokatze.gtswn.common.monitoring;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.util.EUDataSet;

public class NativeEUHistoryTest {

    @Test
    public void legacyAndChangedAccountsAreArchivedAndNeverJoined() {
        NativeEUHistory sources = new NativeEUHistory();
        EUDataSet active = new EUDataSet();
        active.add(BigInteger.ONE, 10);
        assertTrue(sources.bind("epoch:first", active));
        assertTrue(active.isEmpty());
        assertEquals(1, sources.archiveCount());
        active.add(BigInteger.TEN, 20);
        assertTrue(sources.bind("epoch:first", active));
        assertEquals(1, active.size());
        assertTrue(sources.bind("epoch:second", active));
        assertTrue(active.isEmpty());
        assertEquals(2, sources.archiveCount());
        NBTTagCompound tag = new NBTTagCompound();
        sources.write(tag);
        NativeEUHistory restored = new NativeEUHistory();
        restored.read(tag);
        assertEquals("epoch:second", restored.source());
        assertEquals(2, restored.archiveCount());
        EUDataSet previous = new EUDataSet();
        previous.loadFromNBT(
            tag.getTagList("nativeEUHistoryArchives", 10)
                .getCompoundTagAt(1),
            "history");
        assertEquals(
            BigInteger.TEN,
            previous.snapshot()
                .get(0).eu);
        assertEquals(
            20,
            previous.snapshot()
                .get(0).tick);
    }

    @Test
    public void fullArchiveQuotaPreservesActiveOldHistoryAndRefusesCrossSourceAppend() {
        NativeEUHistory sources = new NativeEUHistory();
        EUDataSet active = new EUDataSet();
        for (int i = 0; i < NativeEUHistory.MAX_ARCHIVES; i++) {
            active.add(BigInteger.valueOf(i), i);
            assertTrue(sources.bind("source" + i, active));
        }
        active.add(BigInteger.valueOf(999), 999);
        assertFalse(sources.bind("overflow", active));
        assertEquals(
            BigInteger.valueOf(999),
            active.snapshot()
                .get(0).eu);
        assertEquals(NativeEUHistory.MAX_ARCHIVES, sources.archiveCount());
    }

    @Test
    public void normalRestartWithNewRuntimeEpochRetainsDurableAccountHistory() {
        UUID realm = UUID.randomUUID(), account = UUID.randomUUID();
        NativeWirelessEUSnapshot.Cache cache = new NativeWirelessEUSnapshot.Cache();
        NativeWirelessEUSnapshot before = cache.read(new Object(), 100, account, id -> id, id -> BigInteger.ONE);
        NativeEUHistory identity = new NativeEUHistory();
        EUDataSet active = new EUDataSet();
        assertTrue(identity.bindDurable(realm, before.account, active));
        active.add(BigInteger.ONE, 100);
        active.add(BigInteger.TEN, 200);
        NBTTagCompound saved = new NBTTagCompound();
        identity.write(saved);
        active.saveToNBT(saved, "measurementHistory");
        NativeEUHistory restoredIdentity = new NativeEUHistory();
        restoredIdentity.read(saved);
        EUDataSet restored = new EUDataSet();
        restored.loadFromNBT(saved, "measurementHistory");
        NativeWirelessEUSnapshot after = cache.read(new Object(), 201, account, id -> id, id -> BigInteger.TEN);
        assertNotEquals(before.epoch, after.epoch);
        assertTrue(restoredIdentity.bindDurable(realm, after.account, restored));
        assertEquals(0, restoredIdentity.archiveCount());
        assertEquals(2, restored.size());
        assertEquals(0.09, restored.calculateRecentEUT(), 0.0);
        assertTrue(restoredIdentity.bindDurable(realm, UUID.randomUUID(), restored));
        assertEquals(1, restoredIdentity.archiveCount());
        assertTrue(restored.isEmpty());
    }

    @Test
    public void durableRealmChangeAndActualClockRollbackRemainExplicitBoundaries() {
        UUID realm = UUID.randomUUID(), account = UUID.randomUUID();
        NativeEUHistory identity = new NativeEUHistory();
        EUDataSet active = new EUDataSet();
        assertTrue(identity.bindDurable(realm, account, active));
        active.add(BigInteger.ONE, 100);
        active.add(BigInteger.TEN, 200);
        assertTrue(identity.breakClockContinuity(active));
        assertEquals(1, identity.archiveCount());
        assertTrue(active.isEmpty());
        assertTrue(identity.bindDurable(realm, account, active));
        active.add(BigInteger.valueOf(1000), 10);
        assertEquals(0.0, active.calculateRecentEUT(), 0.0);
        active.add(BigInteger.valueOf(1200), 110);
        assertEquals(2.0, active.calculateRecentEUT(), 0.0);
        assertTrue(identity.bindDurable(UUID.randomUUID(), account, active));
        assertEquals(2, identity.archiveCount());
        assertTrue(active.isEmpty());
    }

}
