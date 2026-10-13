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
}
