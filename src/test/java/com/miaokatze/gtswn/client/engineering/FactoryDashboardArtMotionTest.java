package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import org.junit.Test;

public class FactoryDashboardArtMotionTest {

    @Test
    public void counterRotatingBracketsCrossTheWrappedPhaseSeamContinuouslyInBothDirections() {
        double epsilon = 1e-7;
        for (int bracket = 0; bracket < 3; bracket++) {
            double before = FactoryDashboardArt.innerOrbitStart(bracket, 1 - epsilon);
            double after = FactoryDashboardArt.innerOrbitStart(bracket, epsilon);
            double forwardDelta = shortestTurn(after - before);
            assertEquals(-2 * epsilon, forwardDelta, 1e-12);
            assertEquals(2 * epsilon, shortestTurn(before - after), 1e-12);
            assertTrue(Math.abs(Math.cos(before * Math.PI * 2) - Math.cos(after * Math.PI * 2)) < 2e-6);
            assertTrue(Math.abs(Math.sin(before * Math.PI * 2) - Math.sin(after * Math.PI * 2)) < 2e-6);
        }
    }

    private static double shortestTurn(double turn) {
        return turn - Math.floor(turn + .5);
    }

    @Test
    public void sweepAndPulseEnterAndLeaveWithZeroOpacityAndSmoothVelocity() {
        double epsilon = 1e-5;
        assertEquals(0, FactoryDashboardArt.cycleEnvelope(0), 0);
        assertEquals(0, FactoryDashboardArt.cycleEnvelope(1), 1e-30);
        assertEquals(FactoryDashboardArt.cycleEnvelope(epsilon), FactoryDashboardArt.cycleEnvelope(1 - epsilon), 1e-15);
        assertTrue(FactoryDashboardArt.cycleEnvelope(epsilon) / epsilon < .001);
        assertEquals(0, FactoryDashboardArt.edgeEnvelope(0), 0);
        assertEquals(0, FactoryDashboardArt.edgeEnvelope(1), 0);
        assertEquals(1, FactoryDashboardArt.edgeEnvelope(.5), 0);
        assertEquals(0, FactoryDashboardArt.smooth(0), 0);
        assertEquals(1, FactoryDashboardArt.smooth(1), 0);
        assertTrue(FactoryDashboardArt.smooth(epsilon) / epsilon < .001);
        assertTrue((1 - FactoryDashboardArt.smooth(1 - epsilon)) / epsilon < .001);
    }
}
