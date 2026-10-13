package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import org.junit.Test;

public class DeviceTerminalNameRulesTest {

    @Test
    public void allowsPlainUnicodeAndClearButNeverEmbeddedStyleOrControls() {
        assertEquals("Machine", DeviceTerminalNameRules.normalize(" Machine "));
        assertEquals("", DeviceTerminalNameRules.normalize("   "));
        assertNotNull(DeviceTerminalNameRules.normalize("\u673a\u5668\uD83D\uDE00"));
        for (String name : new String[] { "\u00a7cRed", "bad\nname", "bad\u0000name", "bad\u2028name", "\u202eRTL",
            "\uDB40\uDC01", "\uDB40\uDC20", "\uD83D", "\uDC00" }) assertNull(DeviceTerminalNameRules.normalize(name));
    }

    @Test
    public void enforcesLengthAndOnlyNativeColors() {
        char[] valid = new char[48];
        java.util.Arrays.fill(valid, 'x');
        assertNotNull(DeviceTerminalNameRules.normalize(new String(valid)));
        assertNull(DeviceTerminalNameRules.normalize(new String(valid) + "x"));
        assertFalse(DeviceTerminalNameRules.validColor(-1));
        assertFalse(DeviceTerminalNameRules.validColor(16));
        for (int i = 0; i < 16; i++) assertTrue(DeviceTerminalNameRules.validColor(i));
    }
}
