package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class NetworkInfoNativeAccountTest {

    @Test
    public void teamMembersShareActualAccountButLegacyHistoriesStaySeparate() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), leader = UUID.randomUUID();
        Map<UUID, UUID> teams = new HashMap<>();
        teams.put(first, leader);
        teams.put(second, leader);
        NetworkInfoDataStore store = new NetworkInfoDataStore("native");
        NetworkInfoDataSet old = store.getOrCreate(first.toString());
        old.addSample(BigInteger.valueOf(99), 100, 1000);
        NetworkInfoDataSet a = store.getOrCreateNative(NetworkInfoDataStore.resolveAccount(first, teams::get));
        NetworkInfoDataSet b = store.getOrCreateNative(NetworkInfoDataStore.resolveAccount(second, teams::get));
        assertSame(a, b);
        assertNotSame(old, a);
        assertTrue(
            a.query(WindowChain.WINDOW_5_MIN)
                .isEmpty());
        assertEquals(BigInteger.valueOf(99), old.newest().eu);
        UUID other = UUID.randomUUID();
        teams.put(first, other);
        assertNotSame(a, store.getOrCreateNative(NetworkInfoDataStore.resolveAccount(first, teams::get)));
        assertSame(a, store.getNativeIfPresent(leader));
    }

    @Test
    public void nativeKeyRequiresExplicitCanonicalAccountIdentity() {
        UUID account = UUID.randomUUID();
        assertEquals(account, NetworkInfoDataStore.nativeAccount(NetworkInfoDataStore.nativeKey(account)));
        assertNull(NetworkInfoDataStore.nativeAccount(account.toString()));
        assertNull(NetworkInfoDataStore.nativeAccount("eu:account:1-1-1-1-1"));
        assertNull(NetworkInfoDataStore.nativeAccount("eu:account:not-a-uuid"));
        assertNull(NetworkInfoDataStore.resolveAccount(account, ignored -> { throw new IllegalStateException(); }));
        assertNull(NetworkInfoDataStore.resolveAccount(null, ignored -> account));
    }

    @Test
    public void schedulerAndImmediateApiShareOneNativeReadAndSampleLock() {
        UUID account = UUID.randomUUID();
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        Map.Entry<String, NetworkInfoDataSet> entry = new AbstractMap.SimpleImmutableEntry<>(
            NetworkInfoDataStore.nativeKey(account),
            data);
        AtomicInteger reads = new AtomicInteger();
        assertTrue(NetworkInfoMonitorScheduler.sampleNativeEntry(entry, 100, 1000, id -> id, id -> {
            reads.incrementAndGet();
            return new BigInteger("123456789012345678901234567890");
        }));
        assertFalse(NetworkInfoMonitorScheduler.sampleNativeEntry(entry, 100, 1000, id -> id, id -> {
            reads.incrementAndGet();
            return BigInteger.ZERO;
        }));
        assertEquals(1, reads.get());
        assertEquals(new BigInteger("123456789012345678901234567890"), data.newest().eu);
    }

    @Test
    public void formerAccountLeaderCannotRedirectHistoryIntoNewTeamsBalance() {
        UUID old = UUID.randomUUID(), next = UUID.randomUUID();
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        Map.Entry<String, NetworkInfoDataSet> entry = new AbstractMap.SimpleImmutableEntry<>(
            NetworkInfoDataStore.nativeKey(old),
            data);
        assertFalse(NetworkInfoMonitorScheduler.sampleNativeEntry(entry, 100, 1000, id -> next, id -> {
            fail("A former native account must not be read through membership redirection");
            return BigInteger.ZERO;
        }));
        assertNull(data.newest());
    }

    @Test
    public void unavailableOrMidReadChangedAccountNeverFabricatesSamples() {
        UUID account = UUID.randomUUID();
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        Map.Entry<String, NetworkInfoDataSet> entry = new AbstractMap.SimpleImmutableEntry<>(
            NetworkInfoDataStore.nativeKey(account),
            data);
        assertFalse(NetworkInfoMonitorScheduler.sampleNativeEntry(entry, 100, 1000, id -> id, id -> null));
        assertNull(data.newest());
        AtomicInteger checks = new AtomicInteger();
        assertFalse(
            NetworkInfoMonitorScheduler.sampleNativeEntry(
                entry,
                200,
                2000,
                id -> checks.incrementAndGet() == 1 ? account : UUID.randomUUID(),
                id -> BigInteger.TEN));
        assertNull(data.newest());
    }

    @Test
    public void legacyAndNativeSeriesBothRoundTripWithoutMigrationGuessing() {
        UUID owner = UUID.randomUUID();
        NetworkInfoDataStore data = new NetworkInfoDataStore("before");
        data.getOrCreate(owner.toString())
            .addSample(BigInteger.ONE, 10, 100);
        data.getOrCreateNative(owner)
            .addSample(BigInteger.TEN, 20, 200);
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        NetworkInfoDataStore restored = new NetworkInfoDataStore("after");
        restored.readFromNBT(tag);
        assertEquals(
            BigInteger.ONE,
            restored.getIfPresent(owner.toString())
                .newest().eu);
        assertEquals(
            BigInteger.TEN,
            restored.getNativeIfPresent(owner)
                .newest().eu);
    }
}
