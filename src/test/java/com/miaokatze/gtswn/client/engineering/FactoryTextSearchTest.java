package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

public class FactoryTextSearchTest {

    @Test
    public void termsCanCombineCaseInsensitiveNameCoordinatesAndSource() {
        FactoryTextSearch search = new FactoryTextSearch();
        search.value = "TURBINE 12, 64, -7 north";
        assertTrue(search.matches("Large Turbine DIM 0 12/64/-7 North terminal"));
        assertFalse(search.matches("Large Turbine DIM 0 12/64/-8 North terminal"));
        search.value = "  ";
        assertTrue(search.matches(null));
    }

    @Test
    public void machineSearchIncludesCustomNameRecipeStateAndOrigin() {
        Entry entry = new Entry(
            "0:12:64:-7",
            "Large Turbine",
            (byte) 1,
            (byte) 0,
            0,
            128,
            0,
            128,
            0,
            12,
            64,
            -7,
            "Steam",
            "Water",
            1,
            (byte) 0,
            (byte) 0,
            "North generator",
            (byte) 15,
            false,
            (byte) 2);
        FactoryTextSearch search = new FactoryTextSearch();
        search.value = "north steam running chest";
        assertTrue(search.matchesDevice(entry, "Chest terminal", true));
        assertFalse(search.matchesDevice(entry, "Chest terminal", false));
        search.value = "water 12/64/-7";
        assertTrue(search.matchesDevice(entry, "Inventory", true));
    }
}
