// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

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
    public void retiredRecipeFieldsNeverExposeOldCachedDescriptions() {
        assertEquals("—", DesktopDeviceFields.recipeLine(row(null, "ore", "dust", DeviceLinkSummary.KNOWN)));
        assertEquals("—", DesktopDeviceFields.recipeSide(row(null, "ore", "dust", DeviceLinkSummary.KNOWN), true));
        assertEquals("—", DesktopDeviceFields.recipeSide(row(null, "ore", "dust", DeviceLinkSummary.KNOWN), false));
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

    @Test
    public void selectedTagsMatchAnyLabelAndComposeWithOtherFilters() {
        Entry old = row(null, "", "", DeviceLinkSummary.KNOWN);
        Entry tagged = new Entry(
            old.key,
            old.name,
            old.state,
            old.powerType,
            old.instIn,
            old.instOut,
            old.avgIn,
            old.avgOut,
            old.dim,
            old.x,
            old.y,
            old.z,
            old.recipeIn,
            old.recipeOut,
            old.metaTileId,
            old.evidenceKind,
            old.linkNodeType,
            old.displayName,
            old.nameColor,
            old.nameBold,
            old.capabilityMask,
            old.recognitionMode,
            old.linkSummary,
            old.machineOwner,
            old.machineKind,
            old.machineTier,
            "Power, Main",
            1);
        DeviceListFilters filters = new DeviceListFilters();
        filters.tags.add("Steam");
        assertFalse(filters.matches(tagged, null));
        filters.tags.add("power");
        assertTrue(filters.matches(tagged, null));
        filters.dimension = 0;
        assertFalse(filters.matches(tagged, null));
        filters.dimension = null;
        filters.query = "Main";
        assertTrue(filters.matches(tagged, null));
        assertFalse(filters.matches(old, null));
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
