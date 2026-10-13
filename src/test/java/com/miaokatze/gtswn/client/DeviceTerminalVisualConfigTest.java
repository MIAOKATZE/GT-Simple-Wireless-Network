package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DeviceTerminalVisualConfigTest {

    private int heatSize;
    private int labelSize;

    @Before
    public void saveCurrentSizes() {
        heatSize = DeviceTerminalVisualConfig.heatSize;
        labelSize = DeviceTerminalVisualConfig.labelSize;
    }

    @After
    public void restoreCurrentSizes() {
        DeviceTerminalVisualConfig.heatSize = heatSize;
        DeviceTerminalVisualConfig.labelSize = labelSize;
    }

    @Test
    public void legalSizeEndpointsAreAcceptedAndInvalidEditsPreserveLastGoodValues() {
        assertEquals(10, DeviceTerminalVisualConfig.HEAT_SIZE_DEFAULT);
        assertEquals(10, DeviceTerminalVisualConfig.LABEL_SIZE_DEFAULT);
        assertTrue(DeviceTerminalVisualConfig.setHeatSize(1));
        assertTrue(DeviceTerminalVisualConfig.setHeatSize(1000));
        assertFalse(DeviceTerminalVisualConfig.setHeatSize(1001));
        assertFalse(DeviceTerminalVisualConfig.setHeatSize(0));
        assertFalse(DeviceTerminalVisualConfig.setHeatSize(Integer.MAX_VALUE));
        assertEquals(1000, DeviceTerminalVisualConfig.heatSize);
        assertTrue(DeviceTerminalVisualConfig.setLabelSize(1));
        assertTrue(DeviceTerminalVisualConfig.setLabelSize(100));
        assertFalse(DeviceTerminalVisualConfig.setLabelSize(101));
        assertFalse(DeviceTerminalVisualConfig.setLabelSize(0));
        assertEquals(100, DeviceTerminalVisualConfig.labelSize);
    }

    @Test
    public void everyLegalSizePairHasDistinctCacheIdentity() {
        java.util.Set<Long> identities = new java.util.HashSet<>();
        for (int heat = 1; heat <= 1000; heat++) {
            DeviceTerminalVisualConfig.setHeatSize(heat);
            for (int label = 1; label <= 100; label++) {
                DeviceTerminalVisualConfig.setLabelSize(label);
                assertTrue(
                    "Size changes must not reuse stale radius or label bounds",
                    identities.add(DeviceTerminalVisualConfig.appearanceKey()));
            }
        }
        assertEquals(100000, identities.size());
    }
}
