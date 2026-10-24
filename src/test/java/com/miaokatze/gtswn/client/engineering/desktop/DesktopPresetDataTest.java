package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import org.junit.Test;

public class DesktopPresetDataTest {

    @Test
    public void overviewFitsCompactSixteenByEightCanvasAndUsesGridStockAndNetChange() {
        DesktopLayout layout = DesktopPreset.overviewLayout();
        assertEquals(
            8,
            layout.widgets()
                .size());
        assertEquals(
            16 * DesktopLayout.CELL,
            layout.bounds()
                .width());
        assertEquals(
            8 * DesktopLayout.CELL,
            layout.bounds()
                .height());
        assertEquals(WidgetKind.WIRELESS, layout.find("wireless").kind);
        assertEquals("stored", layout.find("wireless").metricField);
        assertEquals(WidgetKind.WIRELESS, layout.find("netRate").kind);
        assertEquals("netRate", layout.find("netRate").metricField);
        assertEquals(2, layout.find("region").rows);
        assertEquals(3, layout.find("resources").rows);
        assertEquals(3, layout.find("attention").rows);
        assertEquals(WidgetKind.DEVICE, layout.find("device").kind);
        for (WidgetSpec widget : layout.widgets()) {
            assertTrue(layout.fits(widget, widget.id));
            assertNull(widget.screen);
            assertNull(widget.device);
            assertNull(widget.userTarget);
            assertFalse(widget.kind == WidgetKind.INPUT || widget.kind == WidgetKind.OUTPUT);
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
