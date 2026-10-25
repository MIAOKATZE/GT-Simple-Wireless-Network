package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import org.junit.Test;

public class DesktopViewportTest {

    @Test
    public void initialResetAndEveryPresetUseSixtyPercentWhileCenterPreservesSavedZoom() {
        DesktopViewport view = new DesktopViewport();
        assertEquals(.6, view.scale, 0);
        for (DesktopPreset preset : DesktopPreset.values()) {
            DesktopLayout layout = preset.createLayout();
            view.scale = 1.7;
            view.reset(layout, 800, 600);
            assertEquals(.6, view.scale, 0);
            assertEquals((layout.bounds().left + layout.bounds().right) / 2.0, view.toLocalX(400, 0), 1e-9);
            assertEquals((layout.bounds().top + layout.bounds().bottom) / 2.0, view.toLocalY(300, 0), 1e-9);
            view.scale = .37;
            view.center(layout, 640, 400);
            assertEquals(.37, view.scale, 0);
        }
    }

    @Test
    public void emptyInitialAreaFitsCenteredAndDoesNotBecomeACanvasBoundary() {
        DesktopLayout layout = new DesktopLayout();
        DesktopViewport view = new DesktopViewport();
        view.fit(layout, 800, 600);
        assertEquals(512, view.toLocalX(400, 0), 1e-9);
        assertEquals(512, view.toLocalY(300, 0), 1e-9);
        assertEquals(568.0 / 1024, view.scale, 1e-9);
        assertNotNull(layout.add(WidgetKind.INPUT, -100, 200));
        view.center(layout, 800, 600);
        DesktopLayout.Bounds bounds = layout.bounds();
        assertEquals((bounds.left + bounds.right) / 2.0, view.toLocalX(400, 0), 1e-9);
        assertEquals((bounds.top + bounds.bottom) / 2.0, view.toLocalY(300, 0), 1e-9);
        assertEquals(568.0 / 1024, view.scale, 1e-9);
    }

    @Test
    public void centeringPreservesScaleThenZoomPreservesCursorAnchor() {
        DesktopViewport view = new DesktopViewport();
        view.scale = .5;
        view.centerInitial(800, 600);
        assertEquals(512, view.toLocalX(410, 10), 1e-9);
        assertEquals(512, view.toLocalY(320, 20), 1e-9);
        double x = view.toLocalX(234, 10), y = view.toLocalY(345, 20);
        view.zoomAt(1.7, 234, 345, 10, 20);
        assertEquals(x, view.toLocalX(234, 10), 1e-9);
        assertEquals(y, view.toLocalY(345, 20), 1e-9);
        DesktopViewport copy = view.copy();
        view.centerInitial(Double.NaN, 100);
        assertEquals(copy.panX, view.panX, 0);
        assertEquals(copy.panY, view.panY, 0);
    }
}
