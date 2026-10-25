package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.List;
import java.util.UUID;

import org.junit.Test;

public class DesktopPresetPlacementTest {

    @Test
    public void transformedPreviewAndExactCommitPreserveSparseRelativeGeometryAndPreferences() {
        DesktopLayout template = new DesktopLayout();
        DeviceBinding binding = new DeviceBinding(UUID.randomUUID(), "source", 0, 1, 2, 3, 4);
        assertTrue(
            template.add(
                new WidgetSpec("first", WidgetKind.DEVICE, -12, 4, 6, 3).boundDevice(binding)
                    .titled("Custom title")
                    .withNumberFormat(WidgetNumberFormat.NORMAL)));
        assertTrue(template.add(new WidgetSpec("second", WidgetKind.CLOCK, -4, 9, 3, 3)));
        DesktopLayout desktop = new DesktopLayout();
        DesktopPresetPlacement placement = new DesktopPresetPlacement(template);
        DesktopViewport view = new DesktopViewport();
        view.scale = .37;
        view.panX = -700;
        view.panY = 450;
        double sx = view.transform(30, 60)
            .toScreenX(-128.5);
        double sy = view.transform(30, 60)
            .toScreenY(256.5);
        placement.point(view.toLocalX(sx, 30), view.toLocalY(sy, 60));
        List<WidgetSpec> ghosts = placement.widgets();
        assertEquals(-3, ghosts.get(0).column);
        assertEquals(4, ghosts.get(0).row);
        assertEquals(5, ghosts.get(1).column);
        assertEquals(9, ghosts.get(1).row);
        assertNull(ghosts.get(0).device);
        assertSame(binding, template.find("first").device);
        assertTrue(placement.valid(desktop));
        assertTrue(
            desktop.widgets()
                .isEmpty());
        assertTrue(
            placement.release(desktop, sx, sy, true)
                .isEmpty());
        placement.press(sx, sy);
        List<WidgetSpec> placed = placement.release(desktop, sx, sy, true);
        assertEquals(2, placed.size());
        for (int i = 0; i < placed.size(); i++) {
            assertEquals(ghosts.get(i).column, placed.get(i).column);
            assertEquals(ghosts.get(i).row, placed.get(i).row);
            assertNotEquals(ghosts.get(i).id, placed.get(i).id);
        }
        assertEquals("Custom title", placed.get(0).title);
        assertEquals(WidgetNumberFormat.NORMAL, placed.get(0).numberFormat);
        assertNull(placed.get(0).device);
        assertTrue(placed.get(0).autoDevicePending);
    }

    @Test
    public void laterCardCollisionAndCoordinateOrCountLimitRejectTheEntireExactGroup() {
        DesktopLayout desktop = new DesktopLayout(), template = new DesktopLayout();
        assertTrue(desktop.add(new WidgetSpec("old", WidgetKind.CLOCK, 8, 4, 3, 3)));
        assertTrue(template.add(new WidgetSpec("a", WidgetKind.CLOCK, 0, 0, 3, 3)));
        assertTrue(template.add(new WidgetSpec("b", WidgetKind.CLOCK, 8, 4, 3, 3)));
        String before = DesktopPresetCode.export(desktop);
        for (int[] at : new int[][] { { 0, 0 }, { DesktopLayout.MAX_COORD - 3, 0 }, { -DesktopLayout.MAX_COORD - 1, 0 },
            { 0, DesktopLayout.MAX_COORD - 3 }, { Integer.MAX_VALUE, Integer.MIN_VALUE } }) {
            assertFalse(desktop.canAppendPresetExact(template, at[0], at[1]));
            assertTrue(
                desktop.appendPresetExact(template, at[0], at[1])
                    .isEmpty());
            assertEquals(before, DesktopPresetCode.export(desktop));
        }
        assertEquals(
            2,
            desktop.appendPresetExact(template, -DesktopLayout.MAX_COORD, -DesktopLayout.MAX_COORD)
                .size());
        for (int i = desktop.widgets()
            .size(); i < DesktopLayout.MAX_WIDGETS - 1; i++)
            assertTrue(desktop.add(new WidgetSpec("filler" + i, WidgetKind.CLOCK, i * 4, 20, 3, 3)));
        assertTrue(
            desktop.appendPresetExact(template, 0, 100)
                .isEmpty());
        assertEquals(
            DesktopLayout.MAX_WIDGETS - 1,
            desktop.widgets()
                .size());
    }
}
