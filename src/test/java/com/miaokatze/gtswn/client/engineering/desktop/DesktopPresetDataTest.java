package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import org.junit.Test;

public class DesktopPresetDataTest {

    @Test
    public void overviewFitsSixteenBySevenCanvasWithFiveSmallResourcesAndLiveMachineStatus() {
        DesktopLayout layout = DesktopPreset.overviewLayout();
        assertEquals(
            10,
            layout.widgets()
                .size());
        assertEquals(
            16 * DesktopLayout.CELL,
            layout.bounds()
                .width());
        assertEquals(
            7 * DesktopLayout.CELL,
            layout.bounds()
                .height());
        assertEquals(WidgetKind.WIRELESS, layout.find("wireless").kind);
        assertEquals("stored", layout.find("wireless").metricField);
        assertEquals(WidgetKind.WIRELESS, layout.find("netRate").kind);
        assertEquals("netRate", layout.find("netRate").metricField);
        assertEquals(WidgetPresentation.KPI, layout.find("machines").presentation);
        assertEquals("count", layout.find("machines").metricField);
        assertEquals(WidgetPresentation.GAUGE, layout.find("wireless").presentation);
        assertEquals(10, layout.find("wireless").column);
        assertEquals(5, layout.find("wireless").rows);
        assertEquals(WidgetPresentation.COMBO, layout.find("history").presentation);
        assertNull(layout.find("resources"));
        assertNull(layout.find("device"));
        assertEquals(WidgetKind.MACHINES, layout.find("status").kind);
        assertEquals(WidgetPresentation.STATUS_LIST, layout.find("status").presentation);
        assertEquals(10, layout.find("status").column);
        assertEquals(5, layout.find("status").row);
        assertEquals(6, layout.find("status").columns);
        assertEquals(2, layout.find("status").rows);
        for (int index = 1; index <= 5; index++) {
            WidgetSpec resource = layout.find("resource" + index);
            assertEquals(WidgetKind.RESOURCES, resource.kind);
            assertTrue(resource.autoResourcePending);
            assertTrue(resource.singleResource);
            assertEquals((index - 1) * 2, resource.column);
            assertEquals(5, resource.row);
            assertEquals(2, resource.columns);
            assertEquals(2, resource.rows);
            assertNull(resource.resource);
        }
        for (WidgetSpec widget : layout.widgets()) {
            assertTrue(layout.fits(widget, widget.id));
            assertNull(widget.screen);
            assertNull(widget.device);
            assertNull(widget.userTarget);
            assertFalse(widget.kind == WidgetKind.INPUT || widget.kind == WidgetKind.OUTPUT);
        }
    }

    @Test
    public void logisticsAlsoUsesFiveTwoByTwoResourceSlots() {
        DesktopLayout layout = DesktopPreset.LOGISTICS.createLayout();
        for (int index = 1; index <= 5; index++) {
            WidgetSpec resource = layout.find("resource" + index);
            assertEquals((index - 1) * 2, resource.column);
            assertEquals(7, resource.row);
            assertEquals(2, resource.columns);
            assertEquals(2, resource.rows);
            assertTrue(resource.singleResource);
            assertTrue(resource.autoResourcePending);
        }
    }

    @Test
    public void productionHasDistinctMachineExamplesThatCanBePlacedBeforeBinding() {
        DesktopLayout layout = DesktopPreset.PRODUCTION.createLayout();
        assertEquals(WidgetPresentation.DEFAULT, layout.find("device").presentation);
        assertEquals(WidgetPresentation.KPI, layout.find("deviceInput").presentation);
        assertEquals("instantInput", layout.find("deviceInput").metricField);
        assertEquals(WidgetPresentation.BAR, layout.find("deviceOutput").presentation);
        assertEquals("averageOutput", layout.find("deviceOutput").metricField);
        for (String id : new String[] { "device", "deviceInput", "deviceOutput" }) {
            assertEquals(WidgetKind.DEVICE, layout.find(id).kind);
            assertNull(layout.find(id).device);
            assertNull(layout.find(id).userTarget);
        }
    }

    @Test
    public void presetCandidatesNeverOverwriteExistingUserGeometryOrInventBindings() {
        DesktopLayout existing = DesktopPreset.overviewLayout();
        assertTrue(existing.move("wireless", -20, -20));
        for (DesktopPreset preset : DesktopPreset.values()) {
            DesktopLayout candidate = preset.createLayout();
            for (WidgetSpec widget : candidate.widgets()) {
                assertTrue(candidate.fits(widget, widget.id));
                assertNull(widget.screen);
                assertNull(widget.device);
                assertNull(widget.userTarget);
                assertFalse(widget.kind == WidgetKind.INPUT || widget.kind == WidgetKind.OUTPUT);
            }
        }
        assertEquals(-20, existing.find("wireless").column);
    }
}
