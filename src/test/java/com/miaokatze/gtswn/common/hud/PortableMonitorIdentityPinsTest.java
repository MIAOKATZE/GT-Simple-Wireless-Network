package com.miaokatze.gtswn.common.hud;

import static org.junit.Assert.*;

import org.junit.Test;

public class PortableMonitorIdentityPinsTest {

    @Test
    public void versionedItemAndFluidKeepIndependentRateSelections() {
        for (String key : new String[] { "v2:item:abc", "v2:fluid:def" }) {
            String prefix = "panel:";
            String instant = PortableMonitorPins.stock(key, false);
            String average = PortableMonitorPins.stock(key, true);
            String[] pins = PortableMonitorPins.toggle(new String[0], prefix, average, 10);
            assertTrue(PortableMonitorPins.selected(pins, prefix, key));
            assertTrue(PortableMonitorPins.selected(pins, prefix, average));
            assertFalse(PortableMonitorPins.selected(pins, prefix, instant));
            assertEquals(key, PortableMonitorPins.requestKey(average));
            pins = PortableMonitorPins.toggle(pins, prefix, instant, 10);
            assertEquals(2, pins.length);
            assertTrue(PortableMonitorPins.selected(pins, prefix, instant));
        }
    }

    @Test
    public void unidentifiedLegacyPinsAreNotAssignedToAnyNewVariant() {
        String[] pins = { "panel:item:example:thing:0" };
        assertTrue(PortableMonitorPins.selected(pins, "panel:", "item:example:thing:0"));
        assertFalse(PortableMonitorPins.selected(pins, "panel:", "v2:item:abc"));
        String[] changed = PortableMonitorPins
            .toggle(pins, "panel:", PortableMonitorPins.stock("v2:item:abc", true), 10);
        assertEquals("panel:item:example:thing:0", changed[0]);
        assertEquals(2, changed.length);
    }
}
