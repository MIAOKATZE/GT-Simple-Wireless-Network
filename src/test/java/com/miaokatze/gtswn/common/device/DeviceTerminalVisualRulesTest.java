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
}
