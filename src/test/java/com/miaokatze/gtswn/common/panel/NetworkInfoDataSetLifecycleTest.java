package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.math.BigInteger;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class NetworkInfoDataSetLifecycleTest {

    @Test
    public void onlyObservedDataSetsAreActiveAndTtlRetainsItsExactBoundary() {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        assertFalse(data.isRequestActive(0));
        assertFalse(data.isRequestActive(5999));
        data.updateRequestTick(0);
        assertTrue(data.isRequestActive(0));
        assertTrue(data.isRequestActive(5999));
        assertFalse(data.isRequestActive(6000));
        assertFalse(data.isRequestActive(5999));
    }

    @Test
    public void futureRequestExpiresPermanentlyUntilAnotherRealRequest() {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        data.updateRequestTick(10000);
        assertFalse(data.isRequestActive(10));
        assertFalse(data.isRequestActive(10000));
        data.updateRequestTick(10001);
        assertTrue(data.isRequestActive(10001));
    }

    @Test
    public void persistedHistoryDoesNotRestoreObservationOrLoseTheSamplingLock() {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        data.updateRequestTick(1000);
        sample(data, 1000, 100);
        data.readFromNBT(data.toNBT());
        assertFalse(data.isRequestActive(1000));
        assertEquals(1, data.size(WindowChain.WINDOW_5_MIN));
        assertFalse(data.tryAcquireSampleLock(1099));
        assertTrue(data.tryAcquireSampleLock(1100));
    }

    @Test
    public void persistedFutureLockRecoversAndRetainsHistoryWithoutCrossEpochSlope() {
        NetworkInfoDataSet original = new NetworkInfoDataSet();
        sample(original, 10000, 100);
        sample(original, 10100, 200);
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        data.readFromNBT(original.toNBT());
        sample(data, 10, 1000);
        assertEquals(3, data.size(WindowChain.WINDOW_5_MIN));
        assertEquals(0.0, data.newest().eut, 0.0);
        assertFalse(data.tryAcquireSampleLock(10));
        assertFalse(data.tryAcquireSampleLock(109));
        sample(data, 110, 1200);
        assertEquals(4, data.size(WindowChain.WINDOW_5_MIN));
        assertEquals(2.0, data.newest().eut, 0.0);
    }

    @Test
    public void schedulerRollbackBetweenSamplesBreaksRateContinuityButNotHistory() {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        sample(data, 1000, 100);
        // Scheduler observed 1050, then 1020: newer than the sample but a different clock epoch.
        data.resetSampleClock(1020);
        sample(data, 1020, 1100);
        assertEquals(2, data.size(WindowChain.WINDOW_5_MIN));
        assertEquals(0.0, data.newest().eut, 0.0);
        assertFalse(data.tryAcquireSampleLock(1020));
        sample(data, 1120, 1300);
        assertEquals(2.0, data.newest().eut, 0.0);
    }

    @Test
    public void schedulerRollbackCannotReopenThePublicApiSampleFromTheSameTick() {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        sample(data, 1000, 100);
        sample(data, 10, 1000);
        data.resetSampleClock(10);
        assertFalse(data.tryAcquireSampleLock(10));
        assertEquals(2, data.size(WindowChain.WINDOW_5_MIN));
        sample(data, 110, 1200);
        assertEquals(0.0, data.newest().eut, 0.0);
        sample(data, 210, 1400);
        assertEquals(2.0, data.newest().eut, 0.0);
    }

    @Test
    public void rollbackToTheOldSampleTickRetainsDedupButCannotBridgeEpochs() {
        NetworkInfoDataStore store = new NetworkInfoDataStore("equal-rollback");
        NetworkInfoDataSet data = store.getOrCreate("live");
        sample(data, 1000, 100);
        data.updateRequestTick(1000);
        assertEquals(
            1,
            NetworkInfoMonitorScheduler.activeEntriesForSampling(store, 1000, true)
                .size());
        assertFalse(data.tryAcquireSampleLock(1000));
        sample(data, 1100, 1100);
        assertEquals(0.0, data.newest().eut, 0.0);
        sample(data, 1200, 1300);
        assertEquals(2.0, data.newest().eut, 0.0);
    }

    @Test
    public void activeStoreSnapshotNeverIncludesUnobservedOrFutureObservedHistory() {
        NetworkInfoDataStore store = new NetworkInfoDataStore("lifecycle");
        store.getOrCreate("never");
        store.getOrCreate("future")
            .updateRequestTick(10000);
        store.getOrCreate("live")
            .updateRequestTick(10);
        assertEquals(
            1,
            store.getActiveEntries(10)
                .size());
        assertEquals(
            "live",
            store.getActiveEntries(10)
                .get(0)
                .getKey());
        assertTrue(
            store.getActiveEntries(10000)
                .isEmpty());
    }

    @Test
    public void schedulerResetReachesInactiveHistoryWithoutActivatingIt() {
        NetworkInfoDataStore store = new NetworkInfoDataStore("rollback");
        NetworkInfoDataSet data = store.getOrCreate("future-request");
        sample(data, 1000, 100);
        data.updateRequestTick(1050);
        assertTrue(
            NetworkInfoMonitorScheduler.activeEntriesForSampling(store, 1020, true)
                .isEmpty());
        assertEquals(1, data.size(WindowChain.WINDOW_5_MIN));
        data.updateRequestTick(1021);
        assertEquals(
            1,
            NetworkInfoMonitorScheduler.activeEntriesForSampling(store, 1120, false)
                .size());
        sample(data, 1120, 1100);
        assertEquals(0.0, data.newest().eut, 0.0);
        sample(data, 1220, 1300);
        assertEquals(2.0, data.newest().eut, 0.0);
        assertEquals(3, data.size(WindowChain.WINDOW_5_MIN));
    }

    @Test
    public void pendingDiscontinuitySurvivesSaveReloadIncludingAnEqualTickLock() {
        for (long rollbackTick : new long[] { 1000, 1020 }) {
            NetworkInfoDataStore store = new NetworkInfoDataStore("save-rollback");
            NetworkInfoDataSet data = store.getOrCreate("history");
            sample(data, 1000, 100);
            NetworkInfoMonitorScheduler.activeEntriesForSampling(store, rollbackTick, true);
            NBTTagCompound saved = data.toNBT();
            assertTrue(saved.getBoolean("sampleClockReset"));
            NetworkInfoDataSet restored = new NetworkInfoDataSet();
            restored.readFromNBT(saved);
            assertFalse(restored.isRequestActive(rollbackTick));
            assertEquals(1, restored.size(WindowChain.WINDOW_5_MIN));
            if (rollbackTick == 1000) assertFalse(restored.tryAcquireSampleLock(1000));
            sample(restored, 1120, 1100);
            assertEquals(0.0, restored.newest().eut, 0.0);
            assertFalse(
                restored.toNBT()
                    .getBoolean("sampleClockReset"));
            sample(restored, 1220, 1300);
            assertEquals(2.0, restored.newest().eut, 0.0);
        }
    }

    @Test
    public void oldNbtWithoutTheOptionalBoundaryFlagRetainsNormalRateContinuity() {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        sample(data, 1000, 100);
        NBTTagCompound old = data.toNBT();
        old.removeTag("sampleClockReset");
        data.readFromNBT(old);
        sample(data, 1100, 300);
        assertEquals(2.0, data.newest().eut, 0.0);
    }

    private static void sample(NetworkInfoDataSet data, long tick, long eu) {
        assertTrue(data.tryAcquireSampleLock(tick));
        data.addSample(BigInteger.valueOf(eu), tick, tick * 50);
    }
}
