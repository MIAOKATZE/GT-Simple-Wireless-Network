package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDrag;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopViewport;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;

public class DesktopCardGeometryTest {

    @Test
    public void tinyOverviewCardsUseOnlyTheirPaintedBoundsAndCanReturnToSelectedAtOne() {
        WidgetSpec widget = new WidgetSpec("far", WidgetKind.DEVICE, -1000, 250000, 4, 3);
        DesktopViewport view = new DesktopViewport();
        view.scale = .025;
        WorkbenchLayout.Rect canvas = new WorkbenchLayout.Rect(8, 93, 360, 230);
        Rect tiny = DesktopCardGeometry.bounds(widget, view.transform(canvas.x, canvas.y));
        assertFalse(DesktopCardGeometry.showResizeHandles(view.scale));
        assertFalse(
            DesktopCardGeometry
                .hit(tiny, tiny.x + tiny.w + 4, tiny.y, DesktopCardGeometry.showResizeHandles(view.scale)));
        DesktopCardGeometry.focusAtOne(view, widget, canvas);
        CanvasTransform transform = view.transform(canvas.x, canvas.y);
        assertEquals(1, view.scale, 0);
        assertEquals(canvas.x + canvas.w / 2.0, transform.toScreenX(widget.x() + widget.width() / 2.0), 1e-9);
        assertEquals(canvas.y + canvas.h / 2.0, transform.toScreenY(widget.y() + widget.height() / 2.0), 1e-9);
        assertTrue(DesktopCardGeometry.showResizeHandles(view.scale));
    }

    @Test
    public void configuredMetadataNeverCoversChartAndResourceHeaderControls() {
        for (WidgetKind kind : new WidgetKind[] { WidgetKind.HISTORY, WidgetKind.RESOURCES }) {
            WidgetSpec widget = new WidgetSpec("card", kind, 0, 0, 10, 7);
            int reserved = DesktopCardGeometry.headerReserved(widget);
            assertTrue(reserved > 0);
            assertEquals(
                reserved,
                DesktopCardGeometry
                    .headerReserved(widget.configured("Title", java.util.Collections.singletonList("source"), 1)));
            assertEquals(
                reserved,
                DesktopCardGeometry
                    .headerReserved(widget.configured("Title", java.util.Collections.<String>emptyList(), 1)));
        }
    }

    @Test
    public void paintedGapAndPointerGapUseTheSameBounds() {
        WidgetSpec widget = new WidgetSpec("card", WidgetKind.INPUT, 0, 0, 4, 2);
        Rect rect = DesktopCardGeometry.bounds(widget, new CanvasTransform(0, 0, 20, 30, .5));
        assertEquals(120, rect.w);
        assertEquals(56, rect.h);
        assertTrue(DesktopCardGeometry.hit(rect, 139, 85, false));
        assertFalse(DesktopCardGeometry.hit(rect, 140, 85, false));
        assertTrue(DesktopCardGeometry.hit(rect, 143, 85, true));
    }

    @Test
    public void everyVisibleHandleUsesScreenPixelsAtAnyZoom() {
        for (double scale : new double[] { .25, 1, 2, 4 }) {
            Rect rect = DesktopCardGeometry.bounds(
                new WidgetSpec("card", WidgetKind.INPUT, 3, -2, 4, 2),
                new CanvasTransform(0, 0, 100, 200, scale));
            assertEquals(
                DesktopDrag.Edge.BOTTOM_RIGHT,
                DesktopCardGeometry.edge(rect, rect.x + rect.w - 3, rect.y + rect.h - 3));
            assertEquals(DesktopDrag.Edge.LEFT, DesktopCardGeometry.edge(rect, rect.x + 3, rect.y + rect.h / 2));
            assertEquals(
                DesktopDrag.Edge.MOVE,
                DesktopCardGeometry.edge(rect, rect.x + rect.w / 2, rect.y + rect.h / 2));
        }
    }

    @Test
    public void plainEdgesRemainMoveHandlesBetweenTheEightResizePoints() {
        Rect rect = new Rect(20, 30, 256, 256);
        assertEquals(DesktopDrag.Edge.MOVE, DesktopCardGeometry.edge(rect, 22, 100));
        assertEquals(DesktopDrag.Edge.MOVE, DesktopCardGeometry.edge(rect, 90, 32));
        assertEquals(DesktopDrag.Edge.LEFT, DesktopCardGeometry.edge(rect, 22, 158));
    }
}
