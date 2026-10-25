package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorkbenchLayoutTest {

    @Test
    public void onlyDashboardEntryIsWideAndLabelsStayInsideUncoveredWorld() {
        WorkbenchLayout layout = new WorkbenchLayout(800, 450, true, true, true, false, 200, 108, true);
        assertEquals(92, layout.modes[0].w);
        for (int i = 1; i < layout.modes.length; i++) {
            assertEquals(27, layout.modes[i].w);
            assertTrue(layout.modes[i - 1].x + layout.modes[i - 1].w < layout.modes[i].x);
        }
        assertTrue(layout.worldViewport.x > layout.sidebar.x + layout.sidebar.w);
        assertTrue(layout.worldViewport.x + layout.worldViewport.w < layout.rail.x);
        assertTrue(layout.worldViewport.y > layout.modes[0].y + layout.modes[0].h);
        assertTrue(layout.worldViewport.y + layout.worldViewport.h < layout.alerts.y);
        WorkbenchLayout dashboard = new WorkbenchLayout(800, 450, true, true, true, true);
        assertEquals(0, dashboard.worldViewport.w);
        assertEquals(0, dashboard.worldViewport.h);
    }

    @Test
    public void deviceListKeepsASelectableRowBesideTheTerminalSidebarAndPower() {
        WorkbenchLayout layout = new WorkbenchLayout(640, 360, true, true, true, false, 126, 108, true, true, true);
        assertEquals(162, WorkbenchLayout.railHeight(false, 1));
        int rowTop = layout.rail.y + WorkbenchLayout.DEVICE_ROWS_TOP;
        int bottom = layout.rail.y + layout.rail.h - WorkbenchLayout.RAIL_BOTTOM;
        assertTrue(rowTop + WorkbenchLayout.RAIL_ROW_HEIGHT <= bottom);
        assertTrue(layout.contains(layout.rail.x + 6, rowTop + 10));
        assertTrue(layout.sidebar.x + layout.sidebar.w < layout.rail.x);
        assertTrue(layout.rail.y + layout.rail.h < layout.power.y);
        for (WorkbenchLayout.Rect terminal : layout.terminals) assertTrue(terminal.x + terminal.w < layout.sidebar.x);
    }

    @Test
    public void shortCanvasAndExpandedObjectListGivePriorityToListRows() {
        WorkbenchLayout compact = new WorkbenchLayout(640, 300, true, true, true, false, 126, 108, true, true, true);
        assertEquals(null, compact.power);
        assertTrue(compact.rail.h >= WorkbenchLayout.railHeight(false, 1));
        assertTrue(compact.rail.y + compact.rail.h <= 300);
        WorkbenchLayout objects = new WorkbenchLayout(640, 360, false, true, true, false, 200, 108, true, true, true);
        assertEquals(null, objects.power);
        assertEquals(null, objects.alerts);
        for (WorkbenchLayout.Rect terminal : objects.terminals) assertTrue(terminal.x + terminal.w < objects.rail.x);
    }

    @Test
    public void dockAndWorkbenchControlsShareBoundsWithoutBlockingTheWorldCenter() {
        WorkbenchLayout layout = new WorkbenchLayout(800, 450, false, true, true, false);
        assertEquals(8, layout.terminals.length);
        for (int i = 0; i < 8; i++) {
            WorkbenchLayout.Rect r = layout.terminals[i];
            assertTrue(layout.contains(r.x + 14, r.y + 14));
            assertFalse(r.contains(r.x + r.w, r.y + 1));
        }
        assertTrue(layout.contains(layout.rail.x + 4, layout.rail.y + 4));
        assertTrue(layout.contains(layout.power.x + 4, layout.power.y + 4));
        assertFalse(layout.contains(350, 220));
    }

    @Test
    public void collapsedPanelsReleaseTheirSpaceAndDashboardCoversTheWholeCanvas() {
        WorkbenchLayout collapsed = new WorkbenchLayout(800, 450, false, false, false, false);
        assertFalse(collapsed.contains(60, 80));
        assertFalse(collapsed.contains(600, 200));
        WorkbenchLayout expanded = new WorkbenchLayout(800, 450, true, true, true, true);
        assertTrue(expanded.contains(60, 80));
        assertTrue(expanded.contains(350, 220));
        assertEquals(0, expanded.dashboard.x);
        assertEquals(0, expanded.dashboard.y);
        assertEquals(800, expanded.dashboard.w);
        assertEquals(450, expanded.dashboard.h);
        assertEquals(null, expanded.sidebar);
        assertEquals(null, expanded.rail);
        assertEquals(null, expanded.power);
        for (WorkbenchLayout.Rect r : expanded.terminals) assertEquals(0, r.w);
        for (WorkbenchLayout.Rect r : expanded.modes) assertEquals(0, r.w);
        for (WorkbenchLayout.Rect r : expanded.tools) assertEquals(0, r.w);
    }

    @Test
    public void compactControlsLeaveTheTopWorldSpaceUncaptured() {
        for (int width : new int[] { 640, 800, 1280 }) {
            WorkbenchLayout layout = new WorkbenchLayout(width, 400, true, true, true, false);
            WorkbenchLayout.Rect lastMode = layout.modes[layout.modes.length - 1];
            assertTrue(lastMode.x + lastMode.w < layout.shortcuts.x);
            assertEquals(0, layout.cameraHints.w);
            assertFalse(layout.contains(width / 2, 12));
            assertFalse(layout.contains(width / 2, 38));
            assertTrue(layout.contains(layout.shortcuts.x + 1, layout.shortcuts.y + 1));
        }
    }

    @Test
    public void smallestFactoryCanvasFitsCommonActionsAndKeepsPanelsSeparate() {
        WorkbenchLayout layout = new WorkbenchLayout(640, 400, true, true, true, false, 500, 108);
        assertTrue(layout.sidebar.y + 69 + 7 * 23 <= layout.sidebar.y + layout.sidebar.h - 5);
        assertTrue(layout.sidebar.x + layout.sidebar.w < layout.rail.x);
        assertTrue(layout.rail.y + layout.rail.h < layout.power.y);
        assertTrue(layout.power.y + layout.power.h <= 400);
        assertTrue(layout.terminals[7].y + layout.terminals[7].h <= 400);
        assertTrue(layout.contains(layout.shortcuts.x + 4, layout.shortcuts.y + 4));
    }

    @Test
    public void worldTerminalsStayVisibleWithToolsOpenAndNoFullWidthHeader() {
        for (int width : new int[] { 480, 640, 1280 }) {
            WorkbenchLayout layout = new WorkbenchLayout(width, 400, false, false, false, false, 200, 108, false, true);
            for (WorkbenchLayout.Rect r : layout.tasks) assertEquals(0, r.w);
            for (WorkbenchLayout.Rect r : layout.terminals) {
                assertEquals(28, r.w);
                assertTrue(layout.contains(r.x + 1, r.y + 1));
            }
            assertTrue(layout.contains(15, 15));
            assertFalse(layout.contains(width - 1, 58));
            assertFalse(layout.contains(width / 2, 12));
            WorkbenchLayout tools = new WorkbenchLayout(
                width,
                400,
                false,
                true,
                false,
                false,
                200,
                108,
                false,
                true,
                true);
            for (WorkbenchLayout.Rect r : tools.terminals) assertTrue(r.x + r.w < tools.rail.x);
        }
        for (int height : new int[] { 100, 124 }) {
            int loaded = FactoryDashboardLayout.regionLoadedLineY(height);
            assertTrue(loaded - 59 >= 12);
            assertTrue(height - 17 - loaded >= 12);
        }
    }

}
