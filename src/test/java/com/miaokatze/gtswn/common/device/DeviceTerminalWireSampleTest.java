package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import org.junit.Test;

public class DeviceTerminalWireSampleTest {

    private boolean sample(DeviceTerminalDataStore.MachineRecord r, String name, double average) {
        return r.applySample(
            1,
            (byte) 0,
            123,
            (byte) 0,
            (byte) 0,
            (byte) 1,
            10,
            0,
            0,
            0,
            average,
            0,
            0,
            0,
            0,
            1,
            64,
            2,
            name,
            "",
            "recipe");
    }

    @Test
    public void sameWireValuesIgnoreFifoBookkeepingAndKeepAliases() {
        DeviceTerminalDataStore.MachineRecord r = new DeviceTerminalDataStore.MachineRecord();
        r.displayName = "Personal label";
        r.nameColor = 4;
        r.nameBold = true;
        assertTrue(sample(r, "Aa", 1.25));
        r.fifoIn[0] = 999;
        r.idx = 1;
        r.count = 1;
        assertFalse(sample(r, "Aa", 1.25));
        assertEquals("Personal label", r.displayName);
        assertEquals((byte) 4, r.nameColor);
        assertTrue(r.nameBold);
        assertEquals(999, r.fifoIn[0], 0.0);
    }

    @Test
    public void exactSerializedComparisonDoesNotMissHashCollisionOrFractionalChange() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        DeviceTerminalDataStore.MachineRecord r = new DeviceTerminalDataStore.MachineRecord();
        sample(r, "Aa", 0.08);
        assertTrue(sample(r, "BB", 0.08));
        assertTrue(sample(r, "BB", 0.09));
        assertFalse(sample(r, new String("BB"), 0.09));
    }
}
