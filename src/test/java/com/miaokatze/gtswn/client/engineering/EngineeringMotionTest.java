package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EngineeringMotionTest {

    @Test
    public void movementScalesWithVisibleSpanAtActualZoom() {
        double[] close = EngineeringMotion.delta(37, 2, 1, 0, 0);
        double[] far = EngineeringMotion.delta(37, .5, 1, 0, 0);
        for (int i = 0; i < 3; i++) assertEquals(close[i] * 4, far[i], 1e-12);
    }

    @Test
    public void diagonalAndVerticalDoNotAccelerateNavigation() {
        assertEquals(.5, length(EngineeringMotion.delta(45, 1, 1, 0, 0)), 1e-12);
        assertEquals(.5, length(EngineeringMotion.delta(45, 1, 1, 1, 0)), 1e-12);
        assertEquals(.5, length(EngineeringMotion.delta(45, 1, 1, 1, 1)), 1e-12);
    }

    @Test
    public void yawMapsScreenDirectionsAndOppositeInputsCancel() {
        assertArrayEquals(new double[] { 0, 0, -.5 }, EngineeringMotion.delta(0, 1, 1, 0, 0), 1e-12);
        assertArrayEquals(new double[] { .5, 0, 0 }, EngineeringMotion.delta(90, 1, 1, 0, 0), 1e-12);
        assertArrayEquals(new double[] { 0, 0, 0 }, EngineeringMotion.delta(42, 1, 0, 0, 0), 0);
    }

    @Test
    public void invalidSamplesCannotPoisonTheCamera() {
        assertArrayEquals(new double[] { 0, 0, 0 }, EngineeringMotion.delta(0, 0, 1, 0, 0), 0);
        assertArrayEquals(new double[] { 0, 0, 0 }, EngineeringMotion.delta(Double.NaN, 1, 1, 0, 0), 0);
        assertArrayEquals(new double[] { 0, 0, 0 }, EngineeringMotion.delta(0, 1, Double.POSITIVE_INFINITY, 0, 0), 0);
    }

    @Test
    public void playerWalkingHasItsOwnWorldSpeedWithoutAProjectionZoom() {
        double walkingSpeed = .216;
        assertEquals(walkingSpeed, length(EngineeringMotion.playerDelta(45, 1, 1, walkingSpeed)), 1e-12);
        assertEquals(walkingSpeed, length(EngineeringMotion.playerDelta(132, 1, 0, walkingSpeed)), 1e-12);
        // Only free-camera displacement scales by the visible world span.
        assertEquals(4, length(EngineeringMotion.delta(45, .125, 1, 0, 0)), 1e-12);
        assertEquals(.0625, length(EngineeringMotion.delta(45, 8, 1, 0, 0)), 1e-12);
    }

    private static double length(double[] delta) {
        return Math.sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]);
    }
}
