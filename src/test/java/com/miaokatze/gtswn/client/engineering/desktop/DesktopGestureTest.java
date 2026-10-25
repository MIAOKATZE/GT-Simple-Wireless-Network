package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import org.junit.Test;

public class DesktopGestureTest {

    @Test
    public void visibleResizeHandleStartsBeforeHoldThresholdAndCommitsOnRelease() {
        DesktopGesture gesture = new DesktopGesture();
        DesktopLayout layout = DesktopTestLayouts.dragFixture();
        DesktopDrag drag = new DesktopDrag();
        gesture.begin(true, true, 0, 0, 0);
        assertTrue(gesture.update(-128, -64, 1000000));
        assertTrue(drag.begin(layout, "machines", DesktopDrag.Edge.TOP_LEFT, 0, 0));
        assertTrue(gesture.moveAllowed(-128, -64));
        assertTrue(drag.update(-128, -64));
        assertEquals(6, layout.find("machines").columns);
        assertEquals(DesktopGesture.Release.DRAG, gesture.release(-128, -64, 2000000, false));
        assertTrue(drag.finish());
        assertEquals(-2, layout.find("machines").column);
        assertFalse(gesture.active());
    }

    @Test
    public void directHandleClickStillSelectsWithoutJitterOrMovement() {
        DesktopGesture gesture = new DesktopGesture();
        gesture.begin(true, true, 0, 0, 0);
        assertFalse(gesture.update(3, 4, 1000000));
        assertEquals(DesktopGesture.Release.ACTIVATE, gesture.release(3, 4, 2000000, true));
    }

    @Test
    public void firstClickOnlySelectsAndSecondClickActivatesOnRelease() {
        DesktopGesture gesture = new DesktopGesture();
        gesture.begin(false, 100, 100, 0);
        assertTrue(gesture.active());
        assertFalse(gesture.update(103, 104, 100000000));
        assertEquals(DesktopGesture.Release.SELECT, gesture.release(103, 104, 200000000, true));
        gesture.begin(true, 100, 100, 300000000);
        assertEquals(DesktopGesture.Release.ACTIVATE, gesture.release(100, 100, 400000000, true));
        assertEquals(DesktopGesture.Release.NONE, gesture.release(100, 100, 500000000, true));
    }

    @Test
    public void preThresholdMovementOrReleaseOutsideCannotOpenOrMoveWidget() {
        DesktopGesture gesture = new DesktopGesture();
        gesture.begin(true, 0, 0, 0);
        assertFalse(gesture.update(6, 0, 100000000));
        assertFalse(gesture.update(0, 0, 900000000));
        assertEquals(DesktopGesture.Release.NONE, gesture.release(0, 0, 1000000000, true));
        gesture.begin(true, 0, 0, 0);
        assertEquals(DesktopGesture.Release.NONE, gesture.release(0, 0, 100000000, false));
    }

    @Test
    public void longPressMovesOnlyAfterThresholdAndReleaseNeverActivates() {
        DesktopGesture gesture = new DesktopGesture();
        DesktopLayout layout = DesktopTestLayouts.dragFixture();
        WidgetSpec original = layout.find("machines");
        DesktopDrag drag = new DesktopDrag();
        gesture.begin(true, 30, 30, 0);
        assertFalse(gesture.update(30, 30, DesktopGesture.HOLD_NANOS - 1));
        assertSame(original, layout.find("machines"));
        assertTrue(gesture.update(30, 30, DesktopGesture.HOLD_NANOS));
        assertTrue(drag.begin(layout, "machines", DesktopDrag.Edge.MOVE, 30, 30));
        assertFalse(gesture.moveAllowed(33, 34));
        assertSame(original, layout.find("machines"));
        assertFalse(gesture.update(-226, 30, 500000000));
        assertTrue(gesture.moveAllowed(-226, 30));
        assertTrue(drag.update(-226, 30));
        assertEquals(-4, layout.find("machines").column);
        assertEquals(DesktopGesture.Release.DRAG, gesture.release(-226, 30, 600000000, true));
        assertTrue(drag.finish());
        assertEquals(-4, layout.find("machines").column);
    }

    @Test
    public void cancellationRollsBackPreviewAndLateReleaseDoesNothing() {
        DesktopGesture gesture = new DesktopGesture();
        DesktopLayout layout = DesktopTestLayouts.dragFixture();
        WidgetSpec original = layout.find("machines");
        DesktopDrag drag = new DesktopDrag();
        gesture.begin(true, 0, 0, 0);
        assertTrue(gesture.update(0, 0, DesktopGesture.HOLD_NANOS));
        drag.begin(layout, "machines", DesktopDrag.Edge.MOVE, 0, 0);
        drag.update(-256, 0);
        gesture.cancel();
        drag.cancel();
        assertSame(original, layout.find("machines"));
        assertEquals(DesktopGesture.Release.NONE, gesture.release(-256, 0, 800000000, true));
    }
}
