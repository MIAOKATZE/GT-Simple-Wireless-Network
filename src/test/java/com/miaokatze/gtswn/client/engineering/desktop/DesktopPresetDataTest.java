package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import org.junit.Test;

public class DesktopPresetDataTest {

    @Test
    public void overviewLeadsWithInformationAboveOriginalSixteenBySevenContent() {
        DesktopLayout layout = DesktopPreset.overviewLayout();
        assertEquals(
            16,
            layout.widgets()
                .size());
        assertEquals(
            16 * DesktopLayout.CELL,
            layout.bounds()
                .width());
        assertEquals(
            9 * DesktopLayout.CELL,
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
        assertEquals(7, layout.find("status").row);
        assertEquals(6, layout.find("status").columns);
        assertEquals(2, layout.find("status").rows);
        for (int index = 1; index <= 5; index++) {
            WidgetSpec resource = layout.find("resource" + index);
            assertEquals(WidgetKind.RESOURCES, resource.kind);
            assertTrue(resource.autoResourcePending);
            assertTrue(resource.singleResource);
            assertEquals((index - 1) * 2, resource.column);
            assertEquals(7, resource.row);
            assertEquals(2, resource.columns);
            assertEquals(2, resource.rows);
            assertNull(resource.resource);
        }
        for (String id : new String[] { "clock", "gameClock", "session", "team", "player" }) {
            WidgetSpec information = layout.find(id);
            assertTrue(information.kind.isInformation());
            assertEquals(0, information.row);
            assertEquals(2, information.rows);
            assertEquals(2, information.columns);
        }
        assertEquals(java.util.Arrays.asList("gameTime", "gameDay"), layout.find("gameClock").fields);
        assertEquals(java.util.Arrays.asList("localTime", "localDate", "timezone"), layout.find("clock").fields);
        WidgetSpec world = layout.find("world");
        assertEquals(WidgetKind.WORLD, world.kind);
        assertEquals(10, world.column);
        assertEquals(0, world.row);
        assertEquals(6, world.columns);
        assertEquals(2, world.rows);
        assertEquals(java.util.Arrays.asList("dimension", "coordinates", "weather"), world.informationFields());
        for (int index = 0; index < 6; index++) assertTrue(
            layout.widgets()
                .get(index).kind.isInformation());
        assertEquals(2, layout.find("machines").row);
        assertEquals(2, layout.find("wireless").row);
        assertEquals(2, layout.find("netRate").row);
        assertEquals(4, layout.find("history").row);
        for (WidgetSpec widget : layout.widgets()) {
            assertTrue(layout.fits(widget, widget.id));
            assertNull(widget.screen);
            assertNull(widget.device);
            assertNull(widget.userTarget);
            assertFalse(widget.kind == WidgetKind.INPUT || widget.kind == WidgetKind.OUTPUT);
        }
    }

    @Test
    public void everyBuiltInPresetCoversItsEntireRectangleExactlyOnce() {
        for (DesktopPreset preset : DesktopPreset.values()) {
            DesktopLayout layout = preset.createLayout();
            DesktopLayout.Bounds bounds = layout.bounds();
            assertEquals(preset.name(), 0, bounds.left);
            assertEquals(preset.name(), 0, bounds.top);
            int columns = bounds.width() / DesktopLayout.CELL;
            int rows = bounds.height() / DesktopLayout.CELL;
            int[][] occupied = new int[rows][columns];
            for (WidgetSpec widget : layout.widgets()) {
                for (int row = widget.row; row < widget.row + widget.rows; row++)
                    for (int column = widget.column; column < widget.column + widget.columns; column++)
                        occupied[row][column]++;
            }
            for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++)
                assertEquals(preset.name() + " cell " + column + "," + row, 1, occupied[row][column]);
        }
    }

    @Test
    public void specialistPresetsContainOnlyTheirOperationalCards() {
        for (DesktopPreset preset : new DesktopPreset[] { DesktopPreset.POWER, DesktopPreset.PRODUCTION,
            DesktopPreset.LOGISTICS, DesktopPreset.DIAGNOSTICS, DesktopPreset.REGIONS }) {
            for (WidgetSpec widget : preset.createLayout()
                .widgets()) assertFalse(preset.name() + " includes " + widget.kind, widget.kind.isInformation());
        }
    }

    @Test
    public void informationPresetGroupsSixCompactRealInformationSources() {
        DesktopLayout layout = DesktopPreset.INFORMATION.createLayout();
        String[] ids = { "clock", "gameClock", "session", "team", "player", "world" };
        WidgetKind[] kinds = { WidgetKind.CLOCK, WidgetKind.CLOCK, WidgetKind.SESSION, WidgetKind.TEAM,
            WidgetKind.PLAYER, WidgetKind.WORLD };
        assertEquals(
            ids.length,
            layout.widgets()
                .size());
        assertEquals(
            6 * DesktopLayout.CELL,
            layout.bounds()
                .width());
        assertEquals(
            4 * DesktopLayout.CELL,
            layout.bounds()
                .height());
        for (int index = 0; index < ids.length; index++) {
            WidgetSpec widget = layout.find(ids[index]);
            assertEquals(kinds[index], widget.kind);
            assertEquals(index % 3 * 2, widget.column);
            assertEquals(index / 3 * 2, widget.row);
            assertEquals(2, widget.columns);
            assertEquals(2, widget.rows);
            assertFalse(
                widget.informationFields()
                    .isEmpty());
        }
        assertEquals(DesktopInformationTemplate.LOCAL_CLOCK.fields(), layout.find("clock").fields);
        assertEquals(DesktopInformationTemplate.GAME_CLOCK.fields(), layout.find("gameClock").fields);
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
