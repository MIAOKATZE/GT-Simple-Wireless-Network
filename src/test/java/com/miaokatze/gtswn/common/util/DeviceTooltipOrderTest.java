// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class DeviceTooltipOrderTest {

    @Test
    public void compactDeviceKeepsStatusAndNativeLinesSummaryParameterAndSource() {
        List<String> lines = new ArrayList<>(
            Arrays.asList(
                "Device",
                "State",
                "Owner",
                "",
                "Description",
                "Monitor",
                "Long explanation",
                "32 EU/t",
                "",
                "Controls",
                "Right-click",
                "",
                "Brand",
                "NBT: 2 tags"));
        DeviceTooltipOrder.normalize(lines, "State", "Description", "Controls", "Brand");
        DeviceTooltipOrder.collapse(lines, "Description", "Controls", "Brand", Arrays.asList("32 EU/t"));
        assertEquals(
            Arrays.asList("Device", "State", "Owner", "NBT: 2 tags", "", "Description", "Monitor", "32 EU/t", "Brand"),
            lines);
    }

    @Test
    public void matrixCollapseChangesOnlyItsOwnCompleteDescription() {
        List<String> own = Arrays.asList("Machine", "Brief", "Capacity", "Tier", "", "Placement", "", "Brand");
        List<String> lines = new ArrayList<>(own);
        lines.add(0, "Item");
        lines.add("Native voltage");
        assertTrue(DeviceTooltipOrder.matrixPage(lines, own, "Brand", true));
        assertTrue(lines.contains("Placement"));
        assertTrue(DeviceTooltipOrder.matrixPage(lines, own, "Brand", false));
        assertEquals(Arrays.asList("Item", "Machine", "Brief", "Capacity", "Tier", "Brand", "Native voltage"), lines);
        assertFalse(DeviceTooltipOrder.matrixPage(lines, own, "Brand", false));
    }

    @Test
    public void machineCoverColorAndAdvancedDetailsStayInStateWithBrandLast() {
        List<String> lines = new ArrayList<>(
            Arrays.asList(
                "Monitor",
                "State",
                "Ready to place",
                "",
                "Description",
                "Grid monitor",
                "",
                "Controls",
                "Right-click",
                "",
                "Brand",
                "Installed cover: Energy Link",
                "Color: Blue",
                "Upgrade: Muffler",
                "",
                "Durability: 9 / 10",
                "Item ID: 123",
                "NBT: 3 tags"));
        DeviceTooltipOrder.normalize(lines, "State", "Description", "Controls", "Brand");
        assertEquals(
            Arrays.asList(
                "Monitor",
                "State",
                "Ready to place",
                "Installed cover: Energy Link",
                "Color: Blue",
                "Upgrade: Muffler",
                "Durability: 9 / 10",
                "Item ID: 123",
                "NBT: 3 tags",
                "",
                "Description",
                "Grid monitor",
                "",
                "Controls",
                "Right-click",
                "",
                "Brand"),
            lines);
        List<String> once = new ArrayList<>(lines);
        DeviceTooltipOrder.normalize(lines, "State", "Description", "Controls", "Brand");
        assertEquals(once, lines);
    }

    @Test
    public void nativeChargeAfterPortableTooltipIsPreservedAndPaddingCollapses() {
        List<String> lines = new ArrayList<>(
            Arrays.asList(
                "Terminal",
                "State",
                "Owner: Player",
                "",
                "",
                "Description",
                "Portable grid viewer",
                "",
                "Controls",
                "Open viewer",
                "",
                "",
                "Brand",
                "",
                "Charge: 10 / 100 EU",
                "",
                ""));
        DeviceTooltipOrder.normalize(lines, "State", "Description", "Controls", "Brand");
        assertEquals(
            Arrays.asList(
                "Terminal",
                "State",
                "Owner: Player",
                "Charge: 10 / 100 EU",
                "",
                "Description",
                "Portable grid viewer",
                "",
                "Controls",
                "Open viewer",
                "",
                "Brand"),
            lines);
    }

    @Test
    public void thirdPartyAndIncompleteTooltipsAreUntouched() {
        for (List<String> original : Arrays.asList(
            Arrays.asList("Other machine", "State", "Description", "Controls", "Other brand", "Color: Blue"),
            Arrays.asList("Other machine", "Brand", "Durability: 9 / 10"),
            Arrays.asList("Other machine", "State", "Controls", "Description", "Brand", "NBT: 3 tags"))) {
            List<String> lines = new ArrayList<>(original);
            DeviceTooltipOrder.normalize(lines, "State", "Description", "Controls", "Brand");
            assertEquals(original, lines);
        }
    }
}
