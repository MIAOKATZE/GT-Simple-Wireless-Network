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
        DeviceFlowMonitor.energyChange(hatch, 1000, 800, false);
        DeviceFlowMonitor.leave(previous);
        previous = DeviceFlowMonitor.enter(b);
        DeviceFlowMonitor.energyChange(hatch, 800, 740, false);
        DeviceFlowMonitor.leave(previous);
        assertEquals(10, first.average(0));
        assertEquals(3, second.average(0));
        assertEquals(0, first.average(1));
    }

    @Test
    public void bothActualDirectionsRemainAvailable() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 100);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
        DeviceFlowMonitor.energyChange(new Object(), 1000, 800, false);
        DeviceFlowMonitor.energyChange(new Object(), 0, 400, false);
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
            DeviceFlowMonitor.energyChange(new Object(), 10000, 9000, false);
            DeviceFlowMonitor.energyChange(fusion, 2000, 3000, false);
            DeviceFlowMonitor.energyChange(fusion, 3000, 2600, false);
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
        DeviceFlowMonitor.steam(machine, 2000, true);
        DeviceFlowMonitor.tick(server, 200);
        assertEquals(0, reading.average(3));
        assertTrue(reading.warmedUp());
        assertEquals(20.0, reading.consumeInterval()[3], 0.0);
        assertFalse(reading.warmedUp());
        DeviceFlowMonitor.tick(server, 300);
        assertEquals(0.0, reading.consumeInterval()[3], 0.0);
    }

    @Test
    public void lowDutyCycleFractionalRatesRemainVisible() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 1000);
        DeviceFlowMonitor.steam(machine, 16, false);
        DeviceFlowMonitor.tick(server, 300);
        assertEquals(0.08, reading.consumeInterval()[2], 0.000001);
    }

    @Test
    public void largeRatesDoNotOverflowTheWindowOrIntervalSum() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 1000);
        long amount = Long.MAX_VALUE / 2;
        for (int tick = 100; tick < 120; tick++) {
            DeviceFlowMonitor.tick(server, tick);
            DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
            DeviceFlowMonitor.energyChange(amount, 0, false);
            DeviceFlowMonitor.leave(previous);
        }
        assertTrue(reading.average(0) >= amount - 1024);
        DeviceFlowMonitor.tick(server, 120);
        assertEquals((double) amount, reading.consumeInterval()[0], 1024.0);
    }

    @Test
    public void actualSteamStorageRatioDoesNotBecomeElectricity() {
        Object machine = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine, 100);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(machine);
        DeviceFlowMonitor.energyChange(machine, 1000, 800, true);
        DeviceFlowMonitor.leave(previous);
        assertEquals(20, reading.average(2));
        assertEquals(0, reading.average(0));
    }

    @Test
    public void nestedUnknownCallsAndExceptionsRestoreAttribution() {
        Object a = new Object(), unknown = new Object();
        DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(a, 100);
        DeviceFlowMonitor.Reading previous = DeviceFlowMonitor.enter(a);
        try {
            DeviceFlowMonitor.energyChange(1000, 800, false);
            DeviceFlowMonitor.Reading nested = DeviceFlowMonitor.enter(unknown);
            try {
                DeviceFlowMonitor.energyChange(1000, 0, false);
            } finally {
                DeviceFlowMonitor.leave(nested);
            }
            DeviceFlowMonitor.energyChange(800, 600, false);
        } finally {
            DeviceFlowMonitor.leave(previous);
        }
        DeviceFlowMonitor.energyChange(600, 0, false);
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
            DeviceFlowMonitor.energyChange(1000, 0, false);
            DeviceFlowMonitor.steam(machine, 1000, false);
        });
        client.start();
        client.join();
        DeviceFlowMonitor.leave(previous);
        assertEquals(0, reading.average(0));
        assertEquals(0, reading.average(2));
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
}
