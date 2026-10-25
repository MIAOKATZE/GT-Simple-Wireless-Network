package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

public class DesktopDragTest {

    @Test
    public void resizeOvershootClampsAtCompactMinimumAndPreservesOppositeCorner() {
        DesktopLayout layout = new DesktopLayout();
        WidgetSpec card = new WidgetSpec("card", WidgetKind.DEVICE, 10, 20, 8, 7);
        assertTrue(layout.add(card));
        DesktopDrag drag = new DesktopDrag();
        assertTrue(drag.begin(layout, card.id, DesktopDrag.Edge.TOP_LEFT, 0, 0));
        assertTrue(drag.update(10000, 10000));
        WidgetSpec small = layout.find(card.id);
        assertEquals(3, small.columns);
        assertEquals(3, small.rows);
        assertEquals(18, small.column + small.columns);
        assertEquals(27, small.row + small.rows);
        drag.cancel();
        assertSame(card, layout.find(card.id));
    }

    @Test
    public void snappedDragAcrossViewportCanCancelAndReturnToPressGeometry() {
        DesktopLayout layout = DesktopTestLayouts.dragFixture();
        WidgetSpec original = layout.find("machines");
        DesktopDrag drag = new DesktopDrag();
        assertTrue(drag.begin(layout, original.id, DesktopDrag.Edge.MOVE, 32, 32));
        assertTrue(drag.update(-800, -608));
        assertEquals(-13, layout.find(original.id).column);
        assertEquals(-10, layout.find(original.id).row);
        drag.cancel();
        assertSame(original, layout.find(original.id));
        assertFalse(drag.active());
    }

    @Test
    public void rejectedOverlapPreservesLastAcceptedPositionAndFinishCommitsIt() {
        DesktopLayout layout = DesktopTestLayouts.dragFixture();
        DesktopDrag drag = new DesktopDrag();
        drag.begin(layout, "machines", DesktopDrag.Edge.MOVE, 0, 0);
        assertTrue(drag.update(-256, 0));
        assertFalse(drag.update(256, 0));
        assertEquals(-4, layout.find("machines").column);
        assertEquals(4, drag.preview().column);
        assertTrue(drag.finish());
        assertEquals(-4, layout.find("machines").column);
    }

    @Test
    public void cornerResizeKeepsOppositeCornerAndRejectsNonFiniteInput() {
        DesktopLayout layout = DesktopTestLayouts.dragFixture();
        DesktopDrag drag = new DesktopDrag();
        drag.begin(layout, "machines", DesktopDrag.Edge.TOP_LEFT, 0, 0);
        assertTrue(drag.update(-128, -128));
        WidgetSpec resized = layout.find("machines");
        assertEquals(4, resized.column + resized.columns);
        assertEquals(3, resized.row + resized.rows);
        assertFalse(drag.update(Double.NaN, 0));
        assertFalse(drag.update(Double.MAX_VALUE, 0));
        assertFalse(drag.update(-Double.MAX_VALUE, 0));
        drag.cancel();
        assertEquals(0, layout.find("machines").column);
    }

    @Test
    public void zoomAnchorsCursorAndPanRemainsInDesktopCoordinates() {
        DesktopViewport view = new DesktopViewport();
        view.scale = 1;
        view.panX = -200;
        view.panY = 300;
        double x = view.toLocalX(250, 10), y = view.toLocalY(200, 20);
        view.zoomAt(.5, 250, 200, 10, 20);
        assertEquals(x, view.toLocalX(250, 10), 1e-9);
        assertEquals(y, view.toLocalY(200, 20), 1e-9);
        view.pan(64, -32);
        assertEquals(x - 128, view.toLocalX(250, 10), 1e-9);
        assertEquals(y + 64, view.toLocalY(200, 20), 1e-9);
        assertEquals(.6, new DesktopViewport().scale, 0);
    }

    @Test
    public void overviewFitsDefaultAndNewlyMountedCardsInsideSmallViewportWithMargins() {
        DesktopLayout layout = DesktopLayout.defaultLayout();
        assertNotNull(layout.add(WidgetKind.HISTORY, 0, 16));
        assertNotNull(layout.addDevice(new DeviceBinding(UUID.randomUUID(), "mounted", 0, 1, 2, 3, 4)));
        DesktopViewport view = new DesktopViewport();
        view.fit(layout, 360, 230);
        assertTrue("Overview can zoom out beyond the old quarter-size floor", view.scale < .25);
        CanvasTransform transform = view.transform(0, 0);
        for (WidgetSpec widget : layout.widgets()) {
            assertTrue(transform.toScreenX(widget.x()) >= 16 - 1e-9);
            assertTrue(transform.toScreenY(widget.y()) >= 16 - 1e-9);
            assertTrue(transform.toScreenX(widget.x() + widget.width()) <= 344 + 1e-9);
            assertTrue(transform.toScreenY(widget.y() + widget.height()) <= 214 + 1e-9);
        }
    }

    @Test
    public void zoomPreservesCursorAnchorAtBothExpandedLimits() {
        DesktopViewport view = new DesktopViewport();
        view.panX = -200;
        view.panY = 300;
        double x = view.toLocalX(250, 10), y = view.toLocalY(200, 20);
        view.zoomAt(1e-100, 250, 200, 10, 20);
        assertEquals(DesktopViewport.MIN_SCALE, view.scale, 0);
        assertEquals(x, view.toLocalX(250, 10), 1e-9);
        assertEquals(y, view.toLocalY(200, 20), 1e-9);
        view.zoomAt(1e100, 250, 200, 10, 20);
        assertEquals(DesktopViewport.MAX_SCALE, view.scale, 0);
        assertEquals(x, view.toLocalX(250, 10), 1e-9);
        assertEquals(y, view.toLocalY(200, 20), 1e-9);
    }

    @Test
    public void extremelyDistantCardsKeepNumericallyBoundedOverview() {
        DesktopLayout layout = DesktopLayout.defaultLayout();
        assertNotNull(layout.add(WidgetKind.MACHINES, DesktopLayout.MAX_COORD - 4, DesktopLayout.MAX_COORD - 2));
        DesktopViewport view = new DesktopViewport();
        view.fit(layout, 360, 230);
        assertEquals(DesktopViewport.MIN_SCALE, view.scale, 0);
        assertTrue(Double.isFinite(view.panX));
        assertTrue(Double.isFinite(view.panY));
        assertTrue(
            Double.isFinite(
                view.transform(0, 0)
                    .toScreenX(DesktopLayout.MAX_COORD * (double) DesktopLayout.CELL)));
    }
}
