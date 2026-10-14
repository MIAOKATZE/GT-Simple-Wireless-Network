package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

public class DevicePowerFilterTest {

    @Test
    public void actualElectricDirectionOverridesMachineCategory() {
        assertModes(entry(0L, 64L, 0D, 32D, (byte) 0, (byte) 0), true, true, false);
        assertModes(entry(64L, 0L, 32D, 0D, (byte) 1, (byte) 0), true, false, true);
    }

    @Test
    public void balancedInputOutputRemainsInBothDirectionalFilters() {
        assertModes(entry(64L, 64L, 32D, 32D, (byte) 0, (byte) 0), true, true, true);
    }

    @Test
    public void recentAverageActivityRetainsItsElectricalDirection() {
        assertModes(entry(0L, 0L, 32D, 0D, (byte) 1, (byte) 0), true, false, true);
        assertModes(entry(0L, 0L, 0D, 32D, (byte) 0, (byte) 0), true, true, false);
    }

    @Test
    public void zeroReadingsBelongOnlyToAllEvenWithStaleCapabilities() {
        Entry idle = entry(0L, 0L, 0D, 0D, (byte) 1, (byte) 0);
        assertModes(idle, true, false, false);
        assertEquals(true, DevicePowerFilter.matches(idle, -1));
        assertEquals(true, DevicePowerFilter.matches(idle, DevicePowerFilter.MODE_COUNT));
    }

    @Test
    public void ignoredEntryIsExcludedFromDirectionsDespiteStaleReadings() {
        assertModes(entry(64L, 64L, 32D, 32D, (byte) 1, (byte) 2), true, false, false);
    }

    private static Entry entry(long in, long out, double averageIn, double averageOut, byte powerType, byte mode) {
        return new Entry(
            "0:1:2:3",
            "Machine",
            (byte) 1,
            powerType,
            in,
            out,
            averageIn,
            averageOut,
            0,
            1,
            2,
            3,
            "",
            "",
            -1,
            (byte) 0,
            (byte) 0,
            "Name",
            (byte) 15,
            false,
            (byte) 3,
            mode);
    }

    private static void assertModes(Entry entry, boolean... expected) {
        assertEquals(DevicePowerFilter.MODE_COUNT, expected.length);
        for (int mode = 0; mode < expected.length; mode++) {
            assertEquals("mode=" + mode, expected[mode], DevicePowerFilter.matches(entry, mode));
        }
    }
}
