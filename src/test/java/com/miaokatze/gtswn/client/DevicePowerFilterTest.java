package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

public class DevicePowerFilterTest {

    @Test
    public void idleElectricConsumerAndGeneratorKeepTheirCategories() {
        assertModes(1, true, true, false, false, true, false, false);
        assertModes(2, true, true, false, true, false, false, false);
    }

    @Test
    public void steamMachineAndBoilerKeepSteamCategoriesAtZeroFlow() {
        assertModes(4, true, false, true, false, false, false, true);
        assertModes(8, true, false, true, false, false, true, false);
    }

    @Test
    public void ordinaryTurbineProducesElectricityAndConsumesSteam() {
        assertModes(2 | 4, true, true, true, true, false, false, true);
    }

    @Test
    public void highPressureAndSupercriticalTurbinesRetainBothSteamDirections() {
        assertModes(2 | 4 | 8, true, true, true, true, false, true, true);
    }

    @Test
    public void balancedBidirectionalChannelsRemainInBothDirectionalFilters() {
        assertModes(1 | 2 | 4 | 8, true, true, true, true, true, true, true);
    }

    @Test
    public void electricRecipeSteamTransactionDoesNotCreateSteamCapability() {
        // The server admits steam bits only for supported steam equipment.
        assertModes(1, true, true, false, false, true, false, false);
    }

    @Test
    public void allIncludesUnclassifiedEntriesAndUnknownModeFallsBackToAll() {
        assertModes(0, true, false, false, false, false, false, false);
        assertEquals(true, DevicePowerFilter.matches(0, -1));
        assertEquals(true, DevicePowerFilter.matches(0, DevicePowerFilter.MODE_COUNT));
    }

    @Test
    public void ignoredEntryIsExcludedFromEveryChannelFilter() {
        Entry ignored = new Entry(
            "0:1:2:3",
            "Ignored turbine",
            (byte) 3,
            (byte) 1,
            64L,
            64L,
            32D,
            32D,
            0,
            1,
            2,
            3,
            "recipe input",
            "recipe output",
            0,
            (byte) 2,
            (byte) 0,
            (byte) 3,
            16L,
            16L,
            8D,
            8D,
            "Name",
            (byte) 15,
            true,
            (byte) 15);
        assertEquals(true, DevicePowerFilter.matches(ignored, DevicePowerFilter.ALL));
        for (int mode = 1; mode < DevicePowerFilter.MODE_COUNT; mode++) {
            assertEquals("ignored mode=" + mode, false, DevicePowerFilter.matches(ignored, mode));
        }
        assertEquals("Name", ignored.displayName);
        assertEquals(1, ignored.x);
        assertEquals(2, ignored.y);
        assertEquals(3, ignored.z);
    }

    private static void assertModes(int capabilities, boolean... expected) {
        assertEquals(DevicePowerFilter.MODE_COUNT, expected.length);
        for (int mode = 0; mode < expected.length; mode++) {
            assertEquals(
                "capabilities=" + capabilities + ", mode=" + mode,
                expected[mode],
                DevicePowerFilter.matches(capabilities, mode));
        }
    }
}
