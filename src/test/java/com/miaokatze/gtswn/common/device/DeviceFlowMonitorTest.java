package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

public class DeviceFlowMonitorTest {

    private Object server;

    @Before
    public void reset() {
        DeviceFlowMonitor.clear();
        server = new Object();
        DeviceFlowMonitor.tick(server, 100);
    }

    @Test
    public void sharingStorageDoesNotShareControllerReadings() {
        Object a = new Object(), b = new Object(), hatch = new Object();
        DeviceFlowMonitor.Reading first = DeviceFlowMonitor.watch(a, 100);
        DeviceFlowMonitor.Reading second = DeviceFlowMonitor.watch(b, 100);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(a);
        DeviceFlowMonitor.energyChange(hatch, 1000, 800);
        DeviceFlowMonitor.leave(previous);
        previous = DeviceFlowMonitor.enter(b);
        DeviceFlowMonitor.energyChange(hatch, 800, 740);
        DeviceFlowMonitor.leave(previous);
        assertEquals(10, first.average(0));
        assertEquals(3, second.average(0));
        assertEquals(0, first.average(1));
    }

    @Test
    public void reopeningAnExpiredWatchBeforePeriodicPruneRequiresFreshWarmup() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading old = DeviceFlowMonitor.watch(machine, 21);
        DeviceFlowMonitor.tick(server, 123);
        DeviceFlowMonitor.Reading reopened = DeviceFlowMonitor.watch(machine, 200);
        assertNotSame(old, reopened);
        assertFalse(reopened.windowReady());
        assertFalse(reopened.warmedUp());
        DeviceFlowMonitor.tick(server, 143);
        assertTrue(reopened.windowReady());
    }

    @Test
    public void bothActualDirectionsRemainAvailable() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 100);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
        DeviceFlowMonitor.energyChange(new Object(), 1000, 800);
        DeviceFlowMonitor.energyChange(new Object(), 0, 400);
        DeviceFlowMonitor.leave(previous);
        assertEquals(10, reading.average(0));
        assertEquals(20, reading.average(1));
    }

    private static class MTEFusionComputer {
    }

    private static class MTELargeFusionComputer {
    }

    @Test
    public void fusionBufferRefillIsNotGenerationOrDoubleConsumption() {
        for (Object fusion : new Object[] { new MTEFusionComputer(), new MTELargeFusionComputer() }) {
            DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(fusion, 100);
            DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(fusion);
            DeviceFlowMonitor.energyChange(new Object(), 10000, 9000);
            DeviceFlowMonitor.energyChange(fusion, 2000, 3000);
            DeviceFlowMonitor.energyChange(fusion, 3000, 2600);
            DeviceFlowMonitor.leave(previous);
            assertEquals(20, reading.average(0));
            assertEquals(0, reading.average(1));
        }
    }

    @Test
    public void intervalAverageRetainsBurstsOutsideInstantWindow() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 1000);
        assertFalse(reading.warmedUp());
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
        DeviceFlowMonitor.energyChange(0, 2000);
        DeviceFlowMonitor.leave(previous);
        DeviceFlowMonitor.tick(server, 200);
        assertEquals(0, reading.average(1));
        assertTrue(reading.warmedUp());
        assertEquals(20.0, reading.consumeInterval()[1], 0.0);
        assertFalse(reading.warmedUp());
        DeviceFlowMonitor.tick(server, 300);
        assertEquals(0.0, reading.consumeInterval()[1], 0.0);
    }

    @Test
    public void lowDutyCycleFractionalRatesRemainVisible() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 1000);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
        DeviceFlowMonitor.energyChange(16, 0);
        DeviceFlowMonitor.leave(previous);
        DeviceFlowMonitor.tick(server, 300);
        assertEquals(0.08, reading.consumeInterval()[0], 0.000001);
    }

    @Test
    public void dashboardWindowNeverConsumesOrResetsTheDitInterval() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 1000);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
        DeviceFlowMonitor.energyChange(16, 0);
        DeviceFlowMonitor.leave(previous);
        assertFalse(reading.windowReady());
        DeviceFlowMonitor.tick(server, 119);
        assertEquals(0.8, reading.snapshotWindow()[0], 0.000001);
        assertEquals(0.8, reading.snapshotWindow()[0], 0.000001);
        assertEquals(16.0 / 19, reading.consumeInterval()[0], 0.000001);
        assertFalse(reading.warmedUp());
        DeviceFlowMonitor.tick(server, 120);
        assertTrue(reading.windowReady());
        assertEquals(0, reading.snapshotWindow()[0], 0);
        reading.consumeInterval();
        assertTrue(reading.windowReady());
    }

    @Test
    public void shorterDashboardLeaseCannotShortenAnExistingDitWatch() {
        Object machine = new Object();
        DeviceFlowMonitor.watch(machine, 1000);
        DeviceFlowMonitor.watch(machine, 200);
        DeviceFlowMonitor.tick(server, 400);
        assertTrue(DeviceFlowMonitor.isWatched(machine));
        DeviceFlowMonitor.tick(server, 1120);
        assertFalse(DeviceFlowMonitor.isWatched(machine));
    }

    @Test
    public void readOnlyWindowPreservesOversizedValuesAndExpiresCleanly() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 1000);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
        DeviceFlowMonitor.wirelessTransaction(java.math.BigInteger.TEN.pow(400), true);
        DeviceFlowMonitor.leave(previous);
        double rate = reading.snapshotWindow()[1];
        assertTrue(Double.isFinite(rate));
        assertTrue(rate > Long.MAX_VALUE);
        DeviceFlowMonitor.tick(server, 120);
        assertEquals(0, reading.snapshotWindow()[1], 0);
        assertTrue(reading.consumeInterval()[1] > Long.MAX_VALUE);
    }

    @Test
    public void largeRatesDoNotOverflowTheWindowOrIntervalSum() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 1000);
        long amount = Long.MAX_VALUE / 2;
        for (int tick = 100; tick < 120; tick++) {
            DeviceFlowMonitor.tick(server, tick);
            DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
            DeviceFlowMonitor.energyChange(amount, 0);
            DeviceFlowMonitor.leave(previous);
        }
        assertTrue(reading.average(0) >= amount - 1024);
        DeviceFlowMonitor.tick(server, 120);
        assertEquals((double) amount, reading.consumeInterval()[0], 1024.0);
    }

    @Test
    public void nestedUnknownCallsAndExceptionsRestoreAttribution() {
        Object a = new Object(), unknown = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(a, 100);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(a);
        try {
            DeviceFlowMonitor.energyChange(1000, 800);
            DeviceFlowMonitor.Reading nested = DeviceFlowMonitor.enter(unknown);
            try {
                DeviceFlowMonitor.energyChange(1000, 0);
            } finally {
                DeviceFlowMonitor.leave(nested);
            }
            DeviceFlowMonitor.energyChange(800, 600);
        } finally {
            DeviceFlowMonitor.leave(previous);
        }
        DeviceFlowMonitor.energyChange(600, 0);
        assertEquals(20, reading.average(0));
    }

    @Test
    public void integratedClientThreadCannotMutateServerObservation() throws Exception {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 100);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
        Thread client = new Thread(() -> {
            assertFalse(DeviceFlowMonitor.active());
            assertFalse(DeviceFlowMonitor.isWatched(machine));
            DeviceFlowMonitor.energyChange(1000, 0);
        });
        client.start();
        client.join();
        DeviceFlowMonitor.leave(previous);
        assertEquals(0, reading.average(0));
    }

    @Test
    public void ttlCapacityAndServerLifecycleBoundReferences() {
        Object machine = new Object();
        DeviceFlowMonitor.watch(machine, 1);
        DeviceFlowMonitor.tick(server, 120);
        assertFalse(DeviceFlowMonitor.isWatched(machine));
        for (int i = 0; i < DeviceFlowMonitor.MAX_TRACKED; i++)
            assertNotNull(DeviceFlowMonitor.watch(new Object(), 100));
        assertNull(DeviceFlowMonitor.watch(new Object(), 100));
        DeviceFlowMonitor.tick(new Object(), 0);
        assertNotNull(DeviceFlowMonitor.watch(machine, 100));
    }

    @Test
    public void committedWirelessTransactionsHaveIndependentActualDirections() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 100);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
        DeviceFlowMonitor.wirelessTransaction(java.math.BigInteger.valueOf(-200), true);
        DeviceFlowMonitor.wirelessTransaction(java.math.BigInteger.valueOf(400), true);
        DeviceFlowMonitor.wirelessTransaction(java.math.BigInteger.valueOf(-900), false);
        DeviceFlowMonitor.wirelessTransaction(java.math.BigInteger.ZERO, true);
        DeviceFlowMonitor.leave(previous);
        assertEquals(10, reading.average(0));
        assertEquals(20, reading.average(1));
    }

    @Test
    public void outsideControllerWirelessBufferTransfersAreNotGenerationOrDoubleConsumption() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 100);
        DeviceFlowMonitor.wirelessTransaction(java.math.BigInteger.valueOf(-1000), true);
        DeviceFlowMonitor.energyChange(machine, 0, 1000);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
        DeviceFlowMonitor.energyChange(machine, 1000, 800);
        DeviceFlowMonitor.leave(previous);
        assertEquals(10, reading.average(0));
        assertEquals(0, reading.average(1));
    }

    @Test
    public void equalWirelessProductionAndLocalConsumptionAreNeverAssumedToBeTransfers() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 100);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
        DeviceFlowMonitor.energyChange(machine, 1000, 800);
        DeviceFlowMonitor.wirelessTransaction(java.math.BigInteger.valueOf(200), true);
        DeviceFlowMonitor.leave(previous);
        assertEquals(10, reading.average(0));
        assertEquals(10, reading.average(1));
    }

    @Test
    public void nestedWirelessTransactionsRestoreControllerAttribution() {
        Object a = new Object(), b = new Object();
        DeviceFlowMonitor.Reading first = DeviceFlowMonitor.watch(a, 100);
        DeviceFlowMonitor.Reading second = DeviceFlowMonitor.watch(b, 100);
        DeviceFlowMonitor.Reading outer = DeviceFlowMonitor.enter(a);
        try {
            DeviceFlowMonitor.wirelessTransaction(java.math.BigInteger.valueOf(-200), true);
            DeviceFlowMonitor.Reading inner = DeviceFlowMonitor.enter(b);
            try {
                DeviceFlowMonitor.wirelessTransaction(java.math.BigInteger.valueOf(400), true);
            } finally {
                DeviceFlowMonitor.leave(inner);
            }
            DeviceFlowMonitor.wirelessTransaction(java.math.BigInteger.valueOf(-200), true);
        } finally {
            DeviceFlowMonitor.leave(outer);
        }
        assertEquals(20, first.average(0));
        assertEquals(0, first.average(1));
        assertEquals(0, second.average(0));
        assertEquals(20, second.average(1));
    }

    @Test
    public void bigIntegerThresholdIsExactAndOrdinaryLongBucketsKeepTheirPrecision() throws Exception {
        Object normal = new Object(), large = new Object();
        DeviceFlowMonitor.Reading ordinary = DeviceFlowMonitor.watch(normal, 100);
        DeviceFlowMonitor.Reading oversized = DeviceFlowMonitor.watch(large, 100);
        java.math.BigInteger boundary = java.math.BigInteger.valueOf(Long.MAX_VALUE);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(normal);
        DeviceFlowMonitor.wirelessTransaction(boundary, true);
        DeviceFlowMonitor.leave(previous);
        previous = DeviceFlowMonitor.enter(large);
        DeviceFlowMonitor.wirelessTransaction(boundary.add(java.math.BigInteger.ONE), true);
        DeviceFlowMonitor.leave(previous);
        java.lang.reflect.Field extra = DeviceFlowMonitor.Reading.class.getDeclaredField("oversizedValues");
        extra.setAccessible(true);
        assertNull(extra.get(ordinary));
        assertNotNull(extra.get(oversized));
        assertEquals(Long.MAX_VALUE / 20, ordinary.average(1));
        assertEquals((double) (Long.MAX_VALUE / 20), oversized.average(1), 1024);
    }

    @Test
    public void enormousWirelessVolumeSaturatesInstantAndKeepsIntervalFinite() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 1000);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
        java.math.BigInteger enormous = java.math.BigInteger.TEN.pow(400);
        DeviceFlowMonitor.wirelessTransaction(enormous, true);
        DeviceFlowMonitor.wirelessTransaction(enormous, true);
        DeviceFlowMonitor.leave(previous);
        assertEquals(Long.MAX_VALUE, reading.average(1));
        DeviceFlowMonitor.tick(server, 120);
        double rate = reading.consumeInterval()[1];
        assertTrue(Double.isFinite(rate));
        assertTrue(rate > 0);
        assertEquals(0, reading.average(1));
    }
}
