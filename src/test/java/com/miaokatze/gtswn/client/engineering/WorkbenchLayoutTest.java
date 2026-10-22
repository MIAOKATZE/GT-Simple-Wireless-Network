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
}
