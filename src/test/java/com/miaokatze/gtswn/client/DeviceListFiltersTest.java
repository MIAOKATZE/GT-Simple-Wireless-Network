package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.desktop.DesktopDeviceFields;
import com.miaokatze.gtswn.common.device.DeviceLinkSummary;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

public class DeviceListFiltersTest {

    @Test
    public void allCachedFiltersComposeWithoutLosingUnknownOrOtherOwners() {
        UUID owner = UUID.randomUUID();
        Entry row = row(owner, "ore", "dust", DeviceLinkSummary.KNOWN);
        DeviceListFilters filters = new DeviceListFilters();
        filters.query = "MACHINE";
        filters.kind = 3;
        filters.dimension = -1;
        filters.tier = 4;
        filters.link = 2;
        filters.owner = 1;
        assertTrue(filters.matches(row, owner));
        filters.owner = 2;
        assertFalse(filters.matches(row, owner));
        assertTrue(filters.matches(row, UUID.randomUUID()));
        filters.owner = 0;
        filters.link = 1;
        assertFalse(filters.matches(row, owner));
        filters.link = 4;
        assertFalse(filters.matches(row, owner));
        assertTrue(filters.matches(row(null, "", "", DeviceLinkSummary.UNKNOWN), owner));
        filters.link = 5;
        assertFalse(filters.matches(row(null, "", "", DeviceLinkSummary.UNKNOWN), owner));
    }

    @Test
    public void recipePresentationHasOneLineAndExplicitMissingSides() {
        assertEquals("ore → dust", DesktopDeviceFields.recipeLine(row(null, "ore", "dust", DeviceLinkSummary.KNOWN)));
        assertEquals("— → dust", DesktopDeviceFields.recipeLine(row(null, "", "dust", DeviceLinkSummary.KNOWN)));
        assertEquals("ore → —", DesktopDeviceFields.recipeLine(row(null, "ore", "", DeviceLinkSummary.KNOWN)));
        assertEquals("—", DesktopDeviceFields.recipeLine(row(null, "", "", DeviceLinkSummary.KNOWN)));
    }

    @Test
    public void searchUsesCachedCoordinatesAndOwnerWithoutAnotherRead() {
        UUID owner = UUID.randomUUID();
        Entry row = row(owner, "", "", DeviceLinkSummary.KNOWN);
        DeviceListFilters filters = new DeviceListFilters();
        for (String query : new String[] { "-1:1:64:0", "1/64/0", "1 64 0", owner.toString() }) {
            filters.query = query;
            assertTrue(query, filters.matches(row, owner));
        }
        filters.query = "7/8/9";
        assertFalse(filters.matches(row, owner));
    }

    private static Entry row(UUID owner, String input, String output, byte state) {
        DeviceLinkSummary summary = state == DeviceLinkSummary.KNOWN ? new DeviceLinkSummary(state, 6, 9, 2, 3)
            : DeviceLinkSummary.UNVERIFIED;
        return new Entry(
            "-1:1:64:0",
            "machine",
            (byte) 1,
            (byte) 0,
            0,
            0,
            0,
            0,
            -1,
            1,
            64,
            0,
            input,
            output,
            -1,
            (byte) 0,
            (byte) 3,
            "",
            (byte) 15,
            false,
            (byte) 1,
            (byte) 0,
            summary,
            owner,
            (byte) 3,
            (byte) 4);
    }
}
