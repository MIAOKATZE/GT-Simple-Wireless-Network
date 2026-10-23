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
        assertEquals(.75, length(EngineeringMotion.delta(45, 1, 1, 0, 0)), 1e-12);
        assertEquals(.75, length(EngineeringMotion.delta(45, 1, 1, 1, 0)), 1e-12);
        assertEquals(.75, length(EngineeringMotion.delta(45, 1, 1, 1, 1)), 1e-12);
    }

    @Test
    public void yawMapsScreenDirectionsAndOppositeInputsCancel() {
        assertArrayEquals(new double[] { 0, 0, -.75 }, EngineeringMotion.delta(0, 1, 1, 0, 0), 1e-12);
        assertArrayEquals(new double[] { .75, 0, 0 }, EngineeringMotion.delta(90, 1, 1, 0, 0), 1e-12);
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
        assertEquals(6, length(EngineeringMotion.delta(45, .125, 1, 0, 0)), 1e-12);
        assertEquals(.09375, length(EngineeringMotion.delta(45, 8, 1, 0, 0)), 1e-12);
    }

    @Test
    public void keyboardSensitivityAndNativePlayerHeadingStayIndependent() {
        assertEquals(1.5, length(EngineeringMotion.delta(45, 1, 1, 1, 0, 2)), 1e-12);
        assertArrayEquals(new double[] { 0, 0, 0 }, EngineeringMotion.delta(0, 1, 1, 0, 0, Double.NaN), 0);
        for (double yaw : new double[] { -45, 0, 45, 90, 270 }) {
            double nativeYaw = Math.toRadians(EngineeringMotion.playerYaw(yaw));
            double[] cameraForward = EngineeringMotion.delta(yaw, 1, 1, 0, 0);
            assertEquals(cameraForward[0] / .75, -Math.sin(nativeYaw), 1e-7);
            assertEquals(cameraForward[2] / .75, Math.cos(nativeYaw), 1e-7);
            double[] cameraRight = EngineeringMotion.delta(yaw, 1, 0, 1, 0);
            assertEquals(cameraRight[0] / .75, -Math.cos(nativeYaw), 1e-7);
            assertEquals(cameraRight[2] / .75, -Math.sin(nativeYaw), 1e-7);
        }
    }

    private static double length(double[] delta) {
        return Math.sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]);
    }
}
