package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import com.miaokatze.gtswn.common.monitoring.MonitoringRuntime;

public class NetworkInfoMonitorSchedulerTest {

    @Test
    public void serverStopReleasesIdentityTokensWithoutOpeningAnotherWorld() throws Exception {
        NetworkInfoMonitorScheduler scheduler = new NetworkInfoMonitorScheduler();
        Field cadenceField = NetworkInfoMonitorScheduler.class.getDeclaredField("cadence");
        cadenceField.setAccessible(true);
        NetworkInfoMonitorScheduler.SampleCadence cadence = (NetworkInfoMonitorScheduler.SampleCadence) cadenceField
            .get(scheduler);
        Object server = new Object(), world = new Object();
        try {
            assertTrue(cadence.shouldSample(server, world, 1000));
            MonitoringRuntime.stop();
            for (String name : new String[] { "server", "world" }) {
                Field identity = NetworkInfoMonitorScheduler.SampleCadence.class.getDeclaredField(name);
                identity.setAccessible(true);
                assertNull("Stopped scheduler must release " + name, identity.get(cadence));
            }
            assertTrue(cadence.shouldSample(server, world, 1000));
            assertFalse(cadence.shouldSample(server, world, 1000));
            assertSame(cadence, cadenceField.get(new NetworkInfoMonitorScheduler()));
        } finally {
            MonitoringRuntime.stop();
        }
    }

    @Test
    public void ordinaryCadenceDeduplicatesAndUsesExactHundredTickBoundary() {
        NetworkInfoMonitorScheduler.SampleCadence cadence = new NetworkInfoMonitorScheduler.SampleCadence();
        Object server = new Object(), world = new Object();
        assertTrue(cadence.shouldSample(server, world, 1000));
        assertFalse(cadence.shouldSample(server, world, 1000));
        assertFalse(cadence.shouldSample(server, world, 1099));
        assertTrue(cadence.shouldSample(server, world, 1100));
        assertFalse(cadence.shouldSample(server, world, 1100));
    }

    @Test
    public void newServerOrOverworldStartsItsOwnCadenceEvenAtTheSameTick() {
        NetworkInfoMonitorScheduler.SampleCadence cadence = new NetworkInfoMonitorScheduler.SampleCadence();
        Object server = new Object(), world = new Object();
        assertTrue(cadence.shouldSample(server, world, 100000));
        server = new Object();
        assertTrue(cadence.shouldSample(server, world, 100000));
        assertFalse(cadence.shouldSample(server, world, 100000));
        world = new Object();
        assertTrue(cadence.shouldSample(server, world, 100000));
        assertFalse(cadence.shouldSample(server, world, 100001));
    }

    @Test
    public void rollbackBelowPriorSampleRestartsImmediatelyWithoutDuplicateSampling() {
        NetworkInfoMonitorScheduler.SampleCadence cadence = new NetworkInfoMonitorScheduler.SampleCadence();
        Object server = new Object(), world = new Object();
        assertTrue(cadence.shouldSample(server, world, 100000));
        assertTrue(cadence.shouldSample(server, world, 0));
        assertFalse(cadence.shouldSample(server, world, 0));
        assertFalse(cadence.shouldSample(server, world, 99));
        assertTrue(cadence.shouldSample(server, world, 100));
    }

    @Test
    public void rollbackBetweenSamplingBoundariesAlsoRestartsImmediately() {
        NetworkInfoMonitorScheduler.SampleCadence cadence = new NetworkInfoMonitorScheduler.SampleCadence();
        Object server = new Object(), world = new Object();
        assertTrue(cadence.shouldSample(server, world, 1000));
        assertFalse(cadence.shouldSample(server, world, 1050));
        assertTrue(cadence.shouldSample(server, world, 1020));
        assertFalse(cadence.shouldSample(server, world, 1119));
        assertTrue(cadence.shouldSample(server, world, 1120));
    }
}
