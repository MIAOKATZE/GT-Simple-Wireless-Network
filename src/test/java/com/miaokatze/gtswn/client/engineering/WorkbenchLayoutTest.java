package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorkbenchLayoutTest {

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
    public void collapsedPanelsReleaseTheirSpaceAndDashboardRespectsTheSidebar() {
        WorkbenchLayout collapsed = new WorkbenchLayout(800, 450, false, false, false, false);
        assertFalse(collapsed.contains(60, 80));
        assertFalse(collapsed.contains(600, 200));
        WorkbenchLayout expanded = new WorkbenchLayout(800, 450, true, true, true, true);
        assertTrue(expanded.contains(60, 80));
        assertTrue(expanded.contains(350, 220));
        assertTrue(expanded.dashboard.x >= expanded.sidebar.x + expanded.sidebar.w);
        assertTrue(expanded.dashboard.x + expanded.dashboard.w <= expanded.tools[0].x);
        assertEquals(null, expanded.rail);
        assertEquals(null, expanded.power);
    }

    @Test
    public void cameraHintsAndKeysKeepTheirOwnSpaceAtMinimumAndWideCanvasSizes() {
        for (int width : new int[] { 640, 800, 1280 }) {
            WorkbenchLayout layout = new WorkbenchLayout(width, 400, true, true, true, false);
            WorkbenchLayout.Rect lastMode = layout.modes[layout.modes.length - 1];
            assertTrue(lastMode.x + lastMode.w <= layout.cameraHints.x);
            assertTrue(layout.cameraHints.x + layout.cameraHints.w <= layout.shortcuts.x);
            assertTrue(layout.cameraHints.y + layout.cameraHints.h <= layout.terminals[0].y);
            assertTrue(layout.contains(layout.cameraHints.x + 1, layout.cameraHints.y + 1));
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
    public void applicationHeaderKeepsTextNavigationAndCommandsSeparate() {
        for (int width : new int[] { 480, 640, 1280 }) {
            WorkbenchLayout layout = new WorkbenchLayout(width, 400, false, false, false, false, 200, 108, false, true);
            assertEquals(20, layout.cameraHints.h);
            assertTrue(layout.tasks[3].x + layout.tasks[3].w <= layout.shortcuts.x);
            assertTrue(layout.tasks[0].y + layout.tasks[0].h <= layout.cameraHints.y);
            assertTrue(layout.modes[4].x + layout.modes[4].w <= layout.cameraHints.x);
            assertTrue(layout.cameraHints.x + layout.cameraHints.w <= layout.sources.x);
            assertTrue(layout.cameraHints.contains(layout.cameraHints.x + 1, layout.cameraHints.y + 18));
            assertTrue(layout.contains(15, 15));
            assertTrue(layout.contains(width - 1, 58));
        }
        for (int height : new int[] { 100, 124 }) {
            int loaded = FactoryDashboardLayout.regionLoadedLineY(height);
            assertTrue(loaded - 59 >= 12);
            assertTrue(height - 17 - loaded >= 12);
        }
    }

}
