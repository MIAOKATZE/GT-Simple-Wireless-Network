package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import org.junit.Test;

public class DeviceTerminalVisualRulesTest {

    @Test
    public void normalizesLookAndRequiresForwardConeWithin128() {
        assertTrue(DeviceTerminalVisualRules.isAimedAt(0, 0, 0, 0, 0, 2, 0, 0, 128));
        assertFalse(DeviceTerminalVisualRules.isAimedAt(0, 0, 0, 0, 0, 1, 0, 0, 129));
        assertFalse(DeviceTerminalVisualRules.isAimedAt(0, 0, 0, 0, 0, 1, 0, 0, -10));
        assertFalse(DeviceTerminalVisualRules.isAimedAt(0, 0, 0, 0, 0, 0, 0, 0, 10));
        assertFalse(DeviceTerminalVisualRules.isAimedAt(0, 0, 0, 0, 0, 1, 1, 0, 10));
        assertTrue(DeviceTerminalVisualRules.targetable(100, DeviceTerminalVisualRules.MIN_ALIGNMENT, 128));
        assertFalse(DeviceTerminalVisualRules.targetable(100, DeviceTerminalVisualRules.MIN_ALIGNMENT - 0.001, 128));
    }

    @Test
    public void rejectsNonFiniteInputsAndRangeExpansion() {
        assertFalse(DeviceTerminalVisualRules.targetable(100, Double.NaN, 128));
        assertFalse(DeviceTerminalVisualRules.targetable(Double.POSITIVE_INFINITY, 1, 128));
        assertFalse(DeviceTerminalVisualRules.targetable(129 * 129, 1, 1000));
        assertFalse(DeviceTerminalVisualRules.targetable(100, 1, 0));
        assertEquals(-1, DeviceTerminalVisualRules.alignment(0, 0, 0, 1, 0, 0, Double.NaN, 0, 0), 0.0);
    }

    @Test
    public void actualForgeClientAndMpRepresentationsHaveTheSamePhysicalEye() {
        double feet = 64.0;
        double clientOffset = (double) 1.62F, clientDefault = (double) 0.12F, serverDefault = (double) 1.62F;
        double client = DeviceTerminalVisualRules.eyeY(feet + clientOffset, clientOffset, clientDefault, clientDefault);
        double server = DeviceTerminalVisualRules.eyeY(feet, 0, serverDefault, serverDefault);
        assertEquals(server, client, 0.000001);
        assertEquals(65.62, server, 0.000001);
        double crouchClient = DeviceTerminalVisualRules
            .eyeY(feet + clientOffset, clientOffset, clientDefault - 0.08, clientDefault);
        double crouchServer = DeviceTerminalVisualRules.eyeY(feet, 0, serverDefault - 0.08, serverDefault);
        assertEquals(crouchServer, crouchClient, 0.000001);
        // The old posY+getEyeHeight client expression disagreed by .12 blocks near the marker.
        assertTrue(Math.abs((feet + clientOffset + clientDefault) - server) > 0.1);
    }

    @Test
    public void nearMachineBodyCanBeAimedAtEvenWhenMarkerIsAboveTheCrosshair() {
        double eye = DeviceTerminalVisualRules.eyeY(64, 0, 1.62, 1.62);
        double lookY = 64.5 - eye;
        assertFalse(DeviceTerminalVisualRules.isAimedAt(0.5, eye, -1, 0, lookY, 1.5, 0.5, 65.6, 0.5));
        assertTrue(DeviceTerminalVisualRules.isAimedAtMachine(0.5, eye, -1, 0, lookY, 1.5, 0, 64, 0));
        double clientEye = DeviceTerminalVisualRules.eyeY(64 + (double) 1.62F, 1.62F, 0.12F, 0.12F);
        assertTrue(DeviceTerminalVisualRules.isAimedAtMachine(0.5, clientEye, -1, 0, lookY, 1.5, 0, 64, 0));
        // Looking at the old elevated marker is still supported.
        assertTrue(DeviceTerminalVisualRules.isAimedAtMachine(0.5, eye, -1, 0, 65.6 - eye, 1.5, 0, 64, 0));
    }

    @Test
    public void bodyRayHasOnlySmallFixedMarginAndRejectsOtherMachinesOrBackwardAim() {
        assertTrue(DeviceTerminalVisualRules.isAimedAtMachineBody(-0.04, 64.5, -2, 0, 0, 7, 0, 64, 0));
        assertFalse(DeviceTerminalVisualRules.isAimedAtMachineBody(-0.06, 64.5, -2, 0, 0, 7, 0, 64, 0));
        assertFalse(DeviceTerminalVisualRules.isAimedAtMachineBody(0.5, 64.5, -2, 0, 0, -1, 0, 64, 0));
        assertFalse(DeviceTerminalVisualRules.isAimedAtMachine(0.5, 64.5, -2, 0, 0, 1, 2, 64, 0));
        assertFalse(DeviceTerminalVisualRules.isAimedAtMachine(0.5, 64.5, -2, 0, 0, 1, 0, 64, 128));
        assertFalse(DeviceTerminalVisualRules.isAimedAtMachineBody(0.5, 64.5, -2, 0, 0, 0, 0, 64, 0));
        assertFalse(DeviceTerminalVisualRules.isAimedAtMachineBody(Double.NaN, 64.5, -2, 0, 0, 1, 0, 64, 0));
        assertFalse(
            DeviceTerminalVisualRules.isAimedAtMachineBody(0.5, 64.5, -2, 0, 0, Double.POSITIVE_INFINITY, 0, 64, 0));
    }

    @Test
    public void negativeCoordinatesAndAllThreeSlabAxesUseTheSameBoundedRay() {
        assertTrue(DeviceTerminalVisualRules.isAimedAtMachineBody(-3, 64.5, -2.5, 1, 0, 0, -2, 64, -3));
        assertTrue(DeviceTerminalVisualRules.isAimedAtMachineBody(-1.5, 67, -2.5, 0, -1, 0, -2, 64, -3));
        assertTrue(DeviceTerminalVisualRules.isAimedAtMachineBody(-1.5, 64.5, 0, 0, 0, -1, -2, 64, -3));
        assertFalse(DeviceTerminalVisualRules.isAimedAtMachineBody(-3, 66, -2.5, 1, 0, 0, -2, 64, -3));
    }
}
