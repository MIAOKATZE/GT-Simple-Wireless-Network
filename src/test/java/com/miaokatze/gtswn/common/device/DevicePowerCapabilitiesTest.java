package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import org.junit.Test;

public class DevicePowerCapabilitiesTest {

    @Test
    public void electricDirectionsRetainRoleAndObservedPeriodicProduction() {
        assertEquals(1, DevicePowerCapabilities.defaultMask((byte) 0));
        assertEquals(2, DevicePowerCapabilities.defaultMask((byte) 1));
        assertEquals(3, DevicePowerCapabilities.observedCapabilities((byte) 1, 0, 0, new double[] { 0, .08 }));
        assertEquals(3, DevicePowerCapabilities.observedCapabilities((byte) 2, 10, 0, new double[2]));
        assertEquals(2, DevicePowerCapabilities.observedCapabilities((byte) 14, 0, 0, new double[2]));
    }
}
