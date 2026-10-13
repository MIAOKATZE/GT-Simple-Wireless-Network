package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceEnergyVisualMathTest {

    @Test
    public void weightsInstantAndAverageAndPreservesDirection() {
        assertEquals(-75D, DeviceEnergyVisualMath.weighted(-100D, -50D, 0.5D), 0D);
        assertEquals(50D, DeviceEnergyVisualMath.weighted(100D, 50D, 1D), 0D);
        assertEquals(100D, DeviceEnergyVisualMath.weighted(100D, 50D, -1D), 0D);
    }

    @Test
    public void logarithmKeepsSmallDevicesVisibleBesideExtremePower() {
        double small = DeviceEnergyVisualMath.intensity(32D, 1000000000D);
        assertTrue(small > 0.1D);
        assertTrue(small < DeviceEnergyVisualMath.intensity(32768D, 1000000000D));
        assertEquals(1D, DeviceEnergyVisualMath.intensity(1000000000D, 1000000000D), 0D);
        assertEquals(0D, DeviceEnergyVisualMath.intensity(0D, 0D), 0D);
    }

    @Test
    public void smoothingConvergesWithoutOvershooting() {
        double value = 0D;
        for (int i = 0; i < 10; i++) {
            double next = DeviceEnergyVisualMath.smooth(value, 1D, 0.5D);
            assertTrue(next >= value && next <= 1D);
            value = next;
        }
        assertTrue(value > 0.99D);
        assertEquals(0D, DeviceEnergyVisualMath.smooth(1D, 0D, 0D), 0D);
    }

    @Test
    public void invalidTelemetryDoesNotPoisonRendering() {
        assertEquals(0D, DeviceEnergyVisualMath.weighted(Double.NaN, Double.POSITIVE_INFINITY, 0.5D), 0D);
        assertEquals(0D, DeviceEnergyVisualMath.intensity(Double.POSITIVE_INFINITY, 100D), 0D);
    }
}
