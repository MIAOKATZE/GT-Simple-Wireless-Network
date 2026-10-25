package com.miaokatze.gtswn.client.render;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import org.junit.Test;

public class NetworkChartMathTest {

    @Test
    public void signedBarsHaveActualZeroAndKeepFiniteExtremeCoordinates() {
        double[] positive = NetworkChartMath.includeZero(new double[] { 90, 110 });
        assertArrayEquals(new double[] { 0, 110 }, positive, 0);
        double[] negative = NetworkChartMath.includeZero(new double[] { -110, -90 });
        assertArrayEquals(new double[] { -110, 0 }, negative, 0);
        double[] mixed = NetworkChartMath.includeZero(new double[] { -50, 100 });
        double zero = NetworkChartMath.normalize(0, mixed);
        assertTrue(NetworkChartMath.normalize(-25, mixed) < zero);
        assertTrue(NetworkChartMath.normalize(25, mixed) > zero);
        assertEquals(.5, NetworkChartMath.normalize(0, new double[] { -Double.MAX_VALUE, Double.MAX_VALUE }), 0);
        assertTrue(Double.isNaN(NetworkChartMath.normalize(Double.NaN, mixed)));
        assertTrue(Double.isNaN(NetworkChartMath.normalize(Double.POSITIVE_INFINITY, mixed)));
        assertNull(NetworkChartMath.includeZero(null));
    }

    @Test
    public void groupedBarsRemainSeparateAndVisibleAtBothEnds() {
        for (int count : new int[] { 1, 2, 61, 3600 }) {
            for (int sample : new int[] { 0, count - 1 }) {
                double[] energy = NetworkChartMath.barBounds(sample, count, 0, 2);
                double[] power = NetworkChartMath.barBounds(sample, count, 1, 2);
                assertTrue(energy[0] >= 0);
                assertTrue(energy[1] > energy[0]);
                assertTrue(power[0] > energy[1]);
                assertTrue(power[1] > power[0]);
                assertTrue(power[1] <= 1);
            }
        }
    }

    @Test
    public void unknownSamplesBreakAllSplineTypesWithoutIntroducingZeroOrBridging() throws Exception {
        Method build = RenderNetworkInfoPanel.class
            .getDeclaredMethod("chartPath", double[].class, double[].class, int.class, int.class);
        build.setAccessible(true);
        for (int spline = 0; spline < 4; spline++) {
            double[][] path = (double[][]) build.invoke(
                null,
                new double[] { 10, 20, Double.NaN, 30, 40, Double.POSITIVE_INFINITY, 50 },
                new double[] { 0, 100 },
                spline,
                2);
            boolean separator = false;
            for (int i = 0; i < path.length; i++) {
                if (!Double.isFinite(path[i][0])) {
                    separator = true;
                    continue;
                }
                assertTrue(path[i][1] >= .1);
                assertFalse(path[i][0] > 1 && path[i][0] < 3);
                assertFalse(path[i][0] > 4 && path[i][0] < 6);
                if (i > 0 && Double.isFinite(path[i - 1][0])) assertTrue(path[i][0] - path[i - 1][0] <= 1);
            }
            assertTrue(separator);
        }
    }
}
