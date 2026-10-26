package com.miaokatze.gtswn.common.monitoring;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class NativeWirelessEUSnapshotTest {

    @Test
    public void sameAccountAndTickShareExactReadWithoutSharingOtherAccounts() {
        NativeWirelessEUSnapshot.Cache cache = new NativeWirelessEUSnapshot.Cache();
        Object world = new Object();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), account = UUID.randomUUID();
        AtomicInteger reads = new AtomicInteger();
        BigInteger amount = new BigInteger("999999999999999999999999999999999999999");
        NativeWirelessEUSnapshot a = cache.read(world, 100, first, id -> account, id -> {
            reads.incrementAndGet();
            return amount;
        });
        NativeWirelessEUSnapshot b = cache.read(world, 100, second, id -> account, id -> {
            reads.incrementAndGet();
            return BigInteger.ZERO;
        });
        assertSame(a, b);
        assertEquals(amount, b.amount);
        assertEquals(1, reads.get());
        assertNotSame(a, cache.read(world, 101, first, id -> account, id -> amount));
    }

    @Test
    public void membershipWorldAndRollbackBoundariesDoNotStitchSources() {
        NativeWirelessEUSnapshot.Cache cache = new NativeWirelessEUSnapshot.Cache();
        Object world = new Object();
        UUID owner = UUID.randomUUID(), first = UUID.randomUUID(), next = UUID.randomUUID();
        NativeWirelessEUSnapshot a = cache.read(world, 100, owner, id -> first, id -> BigInteger.ONE);
        NativeWirelessEUSnapshot b = cache.read(world, 100, owner, id -> next, id -> BigInteger.TEN);
        assertNotEquals(a.sourceToken(), b.sourceToken());
        NativeWirelessEUSnapshot rewind = cache.read(world, 10, owner, id -> next, id -> BigInteger.TEN);
        assertNotEquals(b.epoch, rewind.epoch);
        assertNotEquals(rewind.epoch, cache.read(new Object(), 10, owner, id -> next, id -> BigInteger.TEN).epoch);
    }

    @Test
    public void missingAndChangingSourcesNeverPublishZeroOrCrossAccountData() {
        NativeWirelessEUSnapshot.Cache cache = new NativeWirelessEUSnapshot.Cache();
        UUID account = UUID.randomUUID(), next = UUID.randomUUID();
        Object world = new Object();
        assertNull(cache.read(world, 1, account, id -> id, id -> null));
        AtomicInteger checks = new AtomicInteger();
        assertNull(
            cache.read(world, 2, account, id -> checks.incrementAndGet() <= 2 ? account : next, id -> BigInteger.TEN));
    }

    @Test
    public void sameTickBoundFailsClosedWithoutEvictionAndNextTickRecovers() {
        NativeWirelessEUSnapshot.Cache cache = new NativeWirelessEUSnapshot.Cache();
        Object world = new Object();
        UUID first = UUID.randomUUID();
        NativeWirelessEUSnapshot saved = cache.read(world, 1, first, id -> id, id -> BigInteger.ONE);
        for (int i = 1; i < NativeWirelessEUSnapshot.Cache.MAX_ACCOUNTS; i++)
            assertNotNull(cache.read(world, 1, UUID.randomUUID(), id -> id, id -> BigInteger.ONE));
        UUID extra = UUID.randomUUID();
        assertNull(cache.read(world, 1, extra, id -> id, id -> {
            fail("Bound must precede native read");
            return BigInteger.ZERO;
        }));
        assertSame(saved, cache.read(world, 1, first, id -> id, id -> BigInteger.TEN));
        assertNotNull(cache.read(world, 2, extra, id -> id, id -> BigInteger.TEN));
    }

    @Test
    public void controlReadsLaterSameTickTransactionsWithoutChangingTelemetrySnapshot() {
        NativeWirelessEUSnapshot.Cache cache = new NativeWirelessEUSnapshot.Cache();
        Object world = new Object();
        UUID account = UUID.randomUUID();
        AtomicInteger nativeBalance = new AtomicInteger(10);
        NativeWirelessEUSnapshot monitoring = cache
            .read(world, 100, account, id -> id, id -> BigInteger.valueOf(nativeBalance.get()));
        nativeBalance.set(20);
        NativeWirelessEUSnapshot control = cache
            .readForControl(world, 100, account, id -> id, id -> BigInteger.valueOf(nativeBalance.get()));
        assertEquals(BigInteger.valueOf(20), control.amount);
        assertEquals(monitoring.sourceToken(), control.sourceToken());
        assertSame(
            monitoring,
            cache.read(world, 100, account, id -> id, id -> BigInteger.valueOf(nativeBalance.get())));
        assertEquals(BigInteger.TEN, monitoring.amount);
        nativeBalance.set(30);
        assertEquals(
            BigInteger.valueOf(30),
            cache.readForControl(world, 100, account, id -> id, id -> BigInteger.valueOf(nativeBalance.get())).amount);
    }

    @Test
    public void failedControlReadDoesNotPoisonTelemetryOrFabricateZero() {
        NativeWirelessEUSnapshot.Cache cache = new NativeWirelessEUSnapshot.Cache();
        Object world = new Object();
        UUID account = UUID.randomUUID();
        NativeWirelessEUSnapshot monitoring = cache.read(world, 100, account, id -> id, id -> BigInteger.TEN);
        assertNull(cache.readForControl(world, 100, account, id -> id, id -> null));
        assertSame(monitoring, cache.read(world, 100, account, id -> id, id -> BigInteger.ONE));
        assertEquals(BigInteger.ONE, cache.readForControl(world, 100, account, id -> id, id -> BigInteger.ONE).amount);
    }

}
