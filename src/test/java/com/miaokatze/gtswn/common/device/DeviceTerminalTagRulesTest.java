package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import org.junit.Test;

public class DeviceTerminalTagRulesTest {

    @Test
    public void labelsTrimDeduplicateAndKeepTheirFirstSpelling() {
        assertEquals("Power, Steam", DeviceTerminalTagRules.normalize(" Power, , Steam，power "));
        assertTrue(DeviceTerminalTagRules.contains("Power, Steam", "POWER"));
        assertFalse(DeviceTerminalTagRules.contains("Power, Steam", "Pow"));
        assertEquals("", DeviceTerminalTagRules.normalize(" , "));
    }

    @Test
    public void editedLabelsHaveExplicitCountLengthAndTextBounds() {
        assertNull(DeviceTerminalTagRules.normalize("bad\nlabel"));
        assertNull(DeviceTerminalTagRules.normalize("\u00a7cColor"));
        assertNull(DeviceTerminalTagRules.normalize("1234567890123456789012345"));
        assertNull(DeviceTerminalTagRules.normalize("1,2,3,4,5,6,7,8,9"));
        assertNotNull(DeviceTerminalTagRules.normalize("1,2,3,4,5,6,7,8,1"));
    }
}
