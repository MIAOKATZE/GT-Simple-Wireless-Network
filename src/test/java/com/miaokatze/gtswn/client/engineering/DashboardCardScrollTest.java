package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;

import org.junit.After;
import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;
import com.miaokatze.miaokatzecore.api.ui.component.UiEvent;
import com.miaokatze.miaokatzecore.api.ui.component.UiNode;
import com.miaokatze.miaokatzecore.api.ui.component.UiRect;
import com.miaokatze.miaokatzecore.api.ui.component.UiScene;

/** Real Core dispatch: inner scrolling consumes edges, captures thumbs, and preserves canvas gestures. */
public class DashboardCardScrollTest {

    private UiScene scene;
    private UiNode card, body;
    private WidgetSpec widget;
    private boolean control;
    private int zooms, downs;

    @After
    public void close() {
        if (scene != null) scene.close();
        DashboardCardScroll.clear();
    }

    private void prepare(double scale, double x, double y) {
        widget = new WidgetSpec("overflow", WidgetKind.CLOCK, 0, 0, 2, 2)
            .configured("", Arrays.asList("localTime", "localDate", "timezone", "gameTime", "gameDay"), 1);
        UiNode root = UiNode.panel("canvas")
            .bounds(0, 0, 1600, 1000)
            .on(UiEvent.Type.SCROLL, e -> {
                zooms++;
                e.consume();
            })
            .on(UiEvent.Type.DOWN, e -> {
                downs++;
                e.consume();
            });
        card = UiNode.panel("card-overflow")
            .bounds(
                x,
                y,
                DesktopCardGeometry.canonical(widget).w * scale,
                DesktopCardGeometry.canonical(widget).h * scale);
        DashboardCardScroll.attach(card, widget, () -> control);
        root.add(card);
        scene = new UiScene(root);
        measure(scale);
        body = scene.find("card-scroll-overflow");
        assertTrue(body.visible());
        assertTrue(body.maxScrollY() > 0);
    }

    private void measure(double scale) {
        DashboardCardScroll.update(card, widget, scale, true);
        scene.layout(1600, 1000);
        DashboardCardScroll.restore(card, widget);
        scene.layout(1600, 1000);
    }

    @Test
    public void innerWheelConsumesBothEdgesWhileControlAndBlankCanvasZoom() {
        prepare(1, 100, 80);
        UiRect b = body.bounds();
        assertTrue(scene.scroll(b.x + 10, b.y + 10, 1));
        assertEquals(0, zooms);
        assertEquals(0, DashboardCardScroll.offset(widget.id));
        for (int i = 0; i < 40; i++) scene.scroll(b.x + 10, b.y + 10, -1);
        assertEquals(body.maxScrollY(), body.scrollY(), .01);
        assertEquals(0, zooms);
        int offset = DashboardCardScroll.offset(widget.id);
        control = true;
        scene.scroll(b.x + 10, b.y + 10, -1);
        assertEquals(1, zooms);
        assertEquals(offset, DashboardCardScroll.offset(widget.id));
        control = false;
        scene.scroll(1500, 900, -1);
        assertEquals(2, zooms);
    }

    @Test
    public void thumbAndTrackCaptureCannotSelectCardAndBlurCancelsDrag() {
        prepare(1, 100, 80);
        UiRect b = body.bounds();
        scene.pointerDown(b.right() - 3, b.bottom() - 3, 0);
        assertSame(body, scene.captured());
        assertEquals(0, downs);
        assertTrue(body.scrollY() > 0);
        scene.pointerMove(1500, 900);
        assertEquals(body.maxScrollY(), body.scrollY(), .01);
        scene.pointerUp(1500, 900, 0);
        assertNull(scene.captured());
        assertEquals(0, downs);
        scene.pointerDown(b.right() - 3, b.bottom() - 3, 0);
        scene.blur();
        assertNull(scene.captured());
        int old = DashboardCardScroll.offset(widget.id);
        scene.pointerMove(0, 0);
        assertEquals(old, DashboardCardScroll.offset(widget.id));
    }

    @Test
    public void screenSpaceHitAndDraggingRemainCorrectAfterPanAndZoom() {
        prepare(.5, 375, 240);
        UiRect b = body.bounds();
        assertEquals(375, b.x, .01);
        assertEquals(252.5, b.y, .01);
        scene.scroll(b.x + 4, b.y + 4, -1);
        assertEquals(29, DashboardCardScroll.offset(widget.id));
        assertEquals("card-overflow", DashboardCardScroll.cardId(scene.hitTest(b.x + 4, b.y + 4)));
        scene.pointerDown(b.right() - 1, b.y + 1, 0);
        assertSame(body, scene.captured());
        scene.pointerMove(b.right() - 1, b.bottom() + 100);
        assertEquals(body.maxScrollY(), body.scrollY(), .01);
        assertEquals(0, downs);
    }

    @Test
    public void fieldReductionAndWidgetRemovalClearTransientOffsetAndCapture() {
        prepare(1, 100, 80);
        UiRect b = body.bounds();
        scene.pointerDown(b.right() - 2, b.bottom() - 2, 0);
        assertNotNull(scene.captured());
        widget = widget.configured("", Collections.singletonList("localTime"), 1);
        measure(1);
        assertEquals(0, DashboardCardScroll.offset(widget.id));
        assertFalse(body.visible());
        assertNull(scene.captured());
        scene.scroll(card.bounds().x + 5, card.bounds().y + 30, -1);
        assertEquals(1, zooms);
        DashboardCardScroll.retain(Collections.emptyList());
        assertFalse(DashboardCardScroll.overflowing(widget.id));
    }

    @Test
    public void scrolledActionRectsUseTheSamePanZoomAndClipAsVisibleRows() throws Exception {
        java.lang.reflect.Field transform = FactoryDashboard.class.getDeclaredField("paintingTransform");
        java.lang.reflect.Field offset = FactoryDashboard.class.getDeclaredField("paintingScroll");
        java.lang.reflect.Field viewport = FactoryDashboard.class.getDeclaredField("paintingViewport");
        transform.setAccessible(true);
        offset.setAccessible(true);
        viewport.setAccessible(true);
        Class<?> hit = Class.forName(FactoryDashboard.class.getName() + "$Hit");
        java.lang.reflect.Constructor<?> constructor = hit.getDeclaredConstructor(Rect.class, Runnable.class);
        constructor.setAccessible(true);
        java.lang.reflect.Field rectangle = hit.getDeclaredField("rect");
        rectangle.setAccessible(true);
        try {
            transform.set(null, new CanvasTransform(0, 0, 375, 240, .5));
            offset.setInt(null, 80);
            viewport.set(null, new Rect(1, 25, 118, 50));
            Rect visible = (Rect) rectangle
                .get(constructor.newInstance(new Rect(6, 130, 100, 18), (Runnable) () -> {}));
            assertEquals(378, visible.x);
            assertEquals(265, visible.y);
            assertEquals(9, visible.h);
            Rect hidden = (Rect) rectangle.get(constructor.newInstance(new Rect(6, 80, 100, 18), (Runnable) () -> {}));
            assertEquals(0, hidden.h);
            assertFalse(hidden.contains(380, 250));
        } finally {
            transform.set(null, CanvasTransform.identity());
            offset.setInt(null, 0);
            viewport.set(null, null);
        }
    }
}
