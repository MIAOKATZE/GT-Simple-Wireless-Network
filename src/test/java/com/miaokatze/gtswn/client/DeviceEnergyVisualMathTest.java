package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void loadRadiusIsMonotonicAndDistinguishesSmallFromLargeMachines() {
        double[] loads = { 0D, 1D, 32D, 128D, 512D, 2048D, 8192D, 32768D };
        double previous = -1D;
        for (double load : loads) {
            double radius = DeviceEnergyVisualMath.radius(DeviceEnergyVisualMath.intensity(load, 32768D));
            assertTrue(radius > previous);
            assertTrue(radius <= DeviceEnergyVisualMath.MAX_ACTIVE_RADIUS);
            previous = radius;
        }
        double low = DeviceEnergyVisualMath.radius(DeviceEnergyVisualMath.intensity(32D, 32768D));
        double high = DeviceEnergyVisualMath.radius(DeviceEnergyVisualMath.intensity(32768D, 32768D));
        assertTrue(low >= DeviceEnergyVisualMath.MIN_ACTIVE_RADIUS);
        assertTrue("Power differences must change the visible volume, not only its color", high - low > 1D);
        assertEquals(0D, DeviceEnergyVisualMath.radius(0D), 0D);
        assertEquals(DeviceEnergyVisualMath.MAX_ACTIVE_RADIUS, DeviceEnergyVisualMath.radius(100D), 0D);
        assertEquals(0D, DeviceEnergyVisualMath.radius(Double.NaN), 0D);
        assertEquals(0D, DeviceEnergyVisualMath.radius(Double.POSITIVE_INFINITY), 0D);
        assertEquals(0D, DeviceEnergyVisualMath.radius(-1D), 0D);
    }

    @Test
    public void sizeOneTenAndThousandPreserveLoadCurveAndActualMaximumRadius() {
        double low = DeviceEnergyVisualMath.intensity(32D, 32768D);
        for (int size : new int[] { 1, 10, 1000 }) {
            double small = DeviceEnergyVisualMath.radius(low, size);
            double large = DeviceEnergyVisualMath.radius(1D, size);
            assertEquals(DeviceEnergyVisualMath.radius(low) * size, small, 0D);
            assertEquals(2.8D * size, large, 0D);
            assertTrue(small < large);
            assertTrue(Double.isFinite(large));
            assertEquals(0D, DeviceEnergyVisualMath.radius(0D, size), 0D);
            assertEquals(0D, DeviceEnergyVisualMath.radius(Double.NaN, size), 0D);
        }
        assertEquals(28D, DeviceEnergyVisualMath.radius(1D, DeviceEnergyVisualMath.HEAT_SIZE_DEFAULT), 0D);
        assertEquals(2800D, DeviceEnergyVisualMath.radius(1D, 1000), 0D);
        assertEquals(2.8D, DeviceEnergyVisualMath.radius(1D, Integer.MIN_VALUE), 0D);
        assertEquals(2800D, DeviceEnergyVisualMath.radius(1D, Integer.MAX_VALUE), 0D);
    }

    @Test
    public void largeSizeInterpolationAndCandidateRangeDoNotCollapseToOriginalRadius() {
        assertEquals(1414D, DeviceEnergyVisualMath.interpolateRadius(28D, 2800D, .5D), 0D);
        assertEquals(2800D, DeviceEnergyVisualMath.interpolateRadius(28D, 2800D, 1D), 0D);
        assertEquals(28D, DeviceEnergyVisualMath.interpolateRadius(2800D, 28D, 1D), 0D);
        for (int size : new int[] { 1, 10, 1000 }) {
            double maximum = DeviceEnergyVisualMath.radius(1D, size);
            double sourceRange = DeviceEnergyVisualMath.sourceRange(64D, size);
            assertEquals(64D + maximum + 1.1D, sourceRange, 0D);
            assertTrue(DeviceEnergyVisualMath.withinRange(64D + maximum, 0D, 0D, 64D, maximum));
            assertFalse(DeviceEnergyVisualMath.withinRange(65D + maximum, 0D, 0D, 64D, maximum));
        }
    }

    @Test
    public void independentChannelsAndAverageWeightPreserveRadiusOrdering() {
        double electric = DeviceEnergyVisualMath.intensity(32D, 128D);
        double steam = DeviceEnergyVisualMath.intensity(3200D, 12800D);
        assertTrue(DeviceEnergyVisualMath.radius(steam) > DeviceEnergyVisualMath.radius(electric));
        double briefSpike = DeviceEnergyVisualMath.weighted(32768D, 32D, .65D);
        double sustained = DeviceEnergyVisualMath.weighted(32768D, 32768D, .65D);
        double quiet = DeviceEnergyVisualMath.weighted(32D, 32D, .65D);
        double spikeRadius = DeviceEnergyVisualMath.radius(DeviceEnergyVisualMath.intensity(briefSpike, sustained));
        assertTrue(spikeRadius > DeviceEnergyVisualMath.radius(DeviceEnergyVisualMath.intensity(quiet, sustained)));
        assertTrue(spikeRadius < DeviceEnergyVisualMath.radius(DeviceEnergyVisualMath.intensity(sustained, sustained)));
    }

    @Test
    public void frameInterpolationRemainsInsideBothEndpointsDuringGrowthAndDecay() {
        for (int step = 0; step <= 20; step++) {
            double progress = step / 20D;
            double growing = DeviceEnergyVisualMath.interpolateRadius(1.1D, 2.8D, progress);
            double shrinking = DeviceEnergyVisualMath.interpolateRadius(2.8D, 1.1D, progress);
            assertTrue(growing >= 1.1D && growing <= 2.8D);
            assertTrue(shrinking >= 1.1D && shrinking <= 2.8D);
            assertEquals(3.9D, growing + shrinking, 1E-12D);
        }
        assertEquals(1.1D, DeviceEnergyVisualMath.interpolateRadius(1.1D, 2.8D, -1D), 0D);
        assertEquals(2.8D, DeviceEnergyVisualMath.interpolateRadius(1.1D, 2.8D, 2D), 0D);
        assertEquals(0D, DeviceEnergyVisualMath.interpolateRadius(Double.NaN, Double.POSITIVE_INFINITY, .5D), 0D);
    }

    @Test
    public void renderRangeIncludesHeatEnvelopeAtTheBoundary() {
        assertTrue(DeviceEnergyVisualMath.withinRange(66D, 0D, 0D, 64D, 2.8D));
        assertFalse(DeviceEnergyVisualMath.withinRange(67D, 0D, 0D, 64D, 2.8D));
        assertTrue(DeviceEnergyVisualMath.withinRange(0D, 0D, -64D, 64D, 0D));
        assertFalse(DeviceEnergyVisualMath.withinRange(64.1D, 0D, 0D, 64D, 0D));
        assertFalse(DeviceEnergyVisualMath.withinRange(Double.NaN, 0D, 0D, 64D, 2.8D));
    }

    @Test
    public void maximumMixedSceneNeverGrowsTheDefaultTessellatorBuffer() {
        int vertices = 0;
        int batches = 1;
        // 512 bounded devices, each with electric and steam channels; every channel is active.
        for (int layer = 0; layer < 1024; layer++) {
            int required = DeviceEnergyVisualMath.vertices(true);
            if (DeviceEnergyVisualMath.startsNewBatch(vertices, required)) {
                assertTrue(vertices * 8 <= 61440);
                vertices = 0;
                batches++;
            }
            vertices += required;
            assertTrue(vertices <= DeviceEnergyVisualMath.FILL_VERTEX_BUDGET);
        }
        assertTrue(vertices * 8 <= 61440);
        assertTrue("Radial geometry remains a bounded set of shared batches", batches <= 12);
        assertEquals(88, DeviceEnergyVisualMath.vertices(true));
        assertEquals(24, DeviceEnergyVisualMath.vertices(false));
        assertFalse(DeviceEnergyVisualMath.startsNewBatch(7656, 24));
        assertTrue(DeviceEnergyVisualMath.startsNewBatch(7656, 88));
    }
}
