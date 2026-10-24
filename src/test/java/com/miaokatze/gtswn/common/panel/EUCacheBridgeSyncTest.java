package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.math.BigInteger;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

public class EUCacheBridgeSyncTest {

    private static NBTTagCompound packet(long seq, boolean delta, long... ticks) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setLong("euSyncSeq", seq);
        tag.setInteger("euSyncKind", delta ? 1 : 0);
        tag.setString("cachedEu", Long.toString(seq));
        tag.setDouble("cachedEut", seq);
        tag.setString("cachedStatus", "seq " + seq);
        NBTTagList samples = new NBTTagList();
        for (long tick : ticks) samples.appendTag(new NetworkInfoSample(tick, tick, BigInteger.ONE, 1).toNBT());
        tag.setTag("samples", samples);
        return tag;
    }

    @Test
    public void duplicateDeltaIsNoOpAndDoesNotBlockTheNextSequence() {
        EUCacheBridge cache = new EUCacheBridge(null, null);
        cache.readSync(packet(10, false, 100));
        cache.readSync(packet(11, true, 110));
        NBTTagCompound repeated = packet(11, true, 115);
        repeated.setString("cachedEu", "999");
        cache.readSync(repeated);
        assertEquals(BigInteger.valueOf(11), cache.getCachedEu());
        assertEquals(
            2,
            cache.getCachedSamples()
                .size());
        cache.readSync(packet(12, true, 120));
        assertEquals(
            3,
            cache.getCachedSamples()
                .size());
        assertEquals(
            120,
            cache.getCachedSamples()
                .get(2).tick);
    }

    @Test
    public void realGapRemainsLockedUntilFullSnapshotEvenAfterDuplicateOrNextDelta() {
        EUCacheBridge cache = new EUCacheBridge(null, null);
        cache.readSync(packet(10, false, 100));
        cache.readSync(packet(12, true, 120));
        cache.readSync(packet(12, true, 120));
        cache.readSync(packet(13, true, 130));
        assertEquals(
            1,
            cache.getCachedSamples()
                .size());
        assertEquals(BigInteger.valueOf(13), cache.getCachedEu());
        cache.readSync(packet(13, false, 110, 120, 130));
        cache.readSync(packet(14, true, 140));
        assertEquals(
            4,
            cache.getCachedSamples()
                .size());
        assertEquals(
            140,
            cache.getCachedSamples()
                .get(3).tick);
    }

    @Test
    public void ownerResetRequiresAFullSnapshotAndWindowRemainsBounded() {
        EUCacheBridge cache = new EUCacheBridge(null, null);
        cache.readSync(packet(1, false, 1));
        for (int seq = 2; seq <= 100; seq++) cache.readSync(packet(seq, true, seq));
        assertEquals(
            WindowChain.CAPACITY,
            cache.getCachedSamples()
                .size());
        cache.onOwnerBound();
        cache.readSync(packet(101, true, 101));
        assertEquals(
            100,
            cache.getCachedSamples()
                .get(WindowChain.CAPACITY - 1).tick);
        cache.readSync(packet(101, false, 101));
        cache.readSync(packet(102, true, 102));
        assertEquals(
            2,
            cache.getCachedSamples()
                .size());
    }
}
