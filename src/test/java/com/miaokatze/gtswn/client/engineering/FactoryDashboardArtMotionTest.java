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
}
