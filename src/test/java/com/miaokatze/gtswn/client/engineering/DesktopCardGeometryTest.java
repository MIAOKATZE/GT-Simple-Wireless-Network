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
        assertEquals(124, rect.w);
        assertEquals(60, rect.h);
        assertEquals(22, rect.x);
        assertEquals(32, rect.y);
        assertTrue(DesktopCardGeometry.hit(rect, 145, 91, false));
        assertFalse(DesktopCardGeometry.hit(rect, 146, 91, false));
        assertTrue(DesktopCardGeometry.hit(rect, 149, 91, true));
    }

    @Test
    public void gridBoundaryIsAtTheMiddleOfTheGapAtEveryZoom() {
        for (double scale : new double[] { .025, .125, .25, .5, 1, 2 }) {
            CanvasTransform transform = new CanvasTransform(0, 0, 0, 0, scale);
            Rect left = DesktopCardGeometry.bounds(new WidgetSpec("left", WidgetKind.INPUT, 0, 0, 4, 2), transform);
            Rect right = DesktopCardGeometry.bounds(new WidgetSpec("right", WidgetKind.INPUT, 4, 0, 4, 2), transform);
            double grid = transform.toScreenX(4 * 64);
            assertEquals(grid, (left.x + left.w + right.x) / 2.0, 1.0);
            assertTrue(left.w > 1);
            assertTrue(right.x >= left.x + left.w - 1);
        }
    }

    @Test
    public void canonicalContentAndMouseCoordinatesRemainIdenticalAcrossZooms() {
        WidgetSpec widget = new WidgetSpec("trend", WidgetKind.HISTORY, -3, 5, 8, 4);
        Rect canonical = DesktopCardGeometry.canonical(widget);
        assertEquals(504, canonical.w);
        assertEquals(248, canonical.h);
        for (double scale : new double[] { .025, .18, .37, 1, 2.5, 4 }) {
            CanvasTransform viewport = new CanvasTransform(-123, 211, 31, 79, scale);
            CanvasTransform content = DesktopCardGeometry.contentTransform(widget, viewport);
            assertEquals(viewport.toScreenX(widget.x() + 4), content.toScreenX(0), 1e-9);
            assertEquals(viewport.toScreenY(widget.y() + 4), content.toScreenY(0), 1e-9);
            // A chart point and a resource row preserve their canonical hit position and aspect ratio.
            assertEquals(177.5, content.toLocalX(content.toScreenX(177.5)), 1e-9);
            assertEquals(96.25, content.toLocalY(content.toScreenY(96.25)), 1e-9);
            assertEquals(
                canonical.w / (double) canonical.h,
                (content.toScreenX(canonical.w) - content.toScreenX(0))
                    / (content.toScreenY(canonical.h) - content.toScreenY(0)),
                1e-9);
            Rect bounds = DesktopCardGeometry.bounds(widget, viewport);
            assertTrue(DesktopCardGeometry.hit(bounds, content.toScreenX(177.5), content.toScreenY(96.25), false));
        }
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
