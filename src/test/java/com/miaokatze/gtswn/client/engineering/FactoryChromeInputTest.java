package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import com.miaokatze.gtswn.client.engineering.FactoryShortcuts.Action;
import com.miaokatze.miaokatzecore.client.camera.CameraControl;

/** Headless checks use the production Chrome tree/router; no LWJGL queue, Forge bus or GL is started. */
public class FactoryChromeInputTest {

    private final List<Action> actions = new ArrayList<>();

    @After
    public void closeScene() {
        FactoryChrome.clear();
    }

    private WorkbenchLayout prepare(int width, int height) {
        WorkbenchLayout layout = new WorkbenchLayout(width, height, false, false, false, false);
        FactoryChrome.prepare(width, height, layout, false, actions::add, () -> {}, "Settings");
        return layout;
    }

    private void worldTick(boolean windowFocused, boolean hidden, boolean guiOpen, boolean worldFocused) {
        boolean visible = EngineeringInputPolicy.dashboardHostVisible(true, hidden, false, true, guiOpen);
        FactoryChrome
            .maintainInput(EngineeringInputPolicy.dashboardInputAllowed(visible, windowFocused, false, worldFocused));
    }

    @Test
    public void unopenedDesktopDoesNotCancelChromePressAcrossTicksAndPaints() {
        WorkbenchLayout layout = prepare(640, 400);
        int x = layout.modes[0].x + 20, y = layout.modes[0].y + 12;
        // These are the same world-HUD bounds consulted before Core starts raw camera navigation.
        for (CameraControl control : new CameraControl[] { CameraControl.PAN, CameraControl.YAW, CameraControl.PITCH,
            CameraControl.ZOOM })
            assertFalse(EngineeringInputPolicy.pointerControl(control, false, false, layout.contains(x, y), false));
        assertTrue(FactoryChrome.mouse(x, y, 0, true, 0));
        assertTrue(actions.isEmpty());
        for (int tick = 0; tick < 3; tick++) {
            worldTick(true, false, false, true);
            prepare(640, 400);
        }
        assertTrue(FactoryChrome.mouse(x, y, 0, false, 0));
        assertEquals(java.util.Collections.singletonList(Action.DASHBOARD), actions);
        FactoryChrome.mouse(x, y, 0, false, 0);
        assertEquals(1, actions.size());
    }

    @Test
    public void hiddenGuiAndFocusBoundariesCancelTheOldPress() {
        boolean[][] contexts = { { false, false, false, true }, { true, true, false, true },
            { true, false, true, true }, { true, false, false, false } };
        for (boolean[] context : contexts) {
            prepare(640, 400);
            assertTrue(FactoryChrome.mouse(28, 21, 0, true, 0));
            worldTick(context[0], context[1], context[2], context[3]);
            worldTick(true, false, false, true);
            FactoryChrome.mouse(28, 21, 0, false, 0);
            assertTrue(actions.isEmpty());
            FactoryChrome.clear();
        }
    }

    @Test
    public void capturedPressCannotActivateAnotherButtonOrReleaseOutside() {
        prepare(640, 400);
        assertTrue(FactoryChrome.mouse(28, 21, 0, true, 0));
        worldTick(true, false, false, true);
        assertTrue(FactoryChrome.mouse(115, 21, -1, false, 0));
        assertTrue(FactoryChrome.mouse(115, 21, 0, false, 0));
        assertTrue(actions.isEmpty());
        assertTrue(FactoryChrome.mouse(28, 21, 0, true, 0));
        assertTrue(FactoryChrome.mouse(320, 200, 0, false, 0));
        assertTrue(actions.isEmpty());
    }

    @Test
    public void foreignReleaseAndResizeCannotStealTheMatchingRelease() {
        prepare(640, 400);
        assertTrue(FactoryChrome.mouse(28, 21, 0, true, 0));
        assertTrue(FactoryChrome.mouse(28, 21, 1, false, 0));
        worldTick(true, false, false, true);
        prepare(800, 450);
        assertTrue(FactoryChrome.mouse(28, 21, 0, false, 0));
        assertEquals(java.util.Collections.singletonList(Action.DASHBOARD), actions);
    }

    @Test
    public void explicitHostSuspensionDiscardsOldReleaseThenAcceptsNextClick() {
        prepare(640, 400);
        FactoryChrome.mouse(28, 21, 0, true, 0);
        FactoryChrome.blur();
        FactoryChrome.mouse(28, 21, 0, false, 0);
        assertTrue(actions.isEmpty());
        FactoryChrome.mouse(28, 21, 0, true, 0);
        worldTick(true, false, false, true);
        FactoryChrome.mouse(28, 21, 0, false, 0);
        assertEquals(java.util.Collections.singletonList(Action.DASHBOARD), actions);
    }

    @Test
    public void f1RoundTripCancelsCapturedPressWithoutReplayingItOnRestore() {
        prepare(640, 400);
        FactoryChrome.mouse(28, 21, 0, true, 0);
        boolean[] hidden = { false };
        Runnable visibilityChange = () -> {
            hidden[0] = !hidden[0];
            // WorkbenchHud.suspendInput uses the same production blur boundary.
            FactoryChrome.blur();
        };
        assertTrue(EngineeringInputPolicy.visibilityKey(Keyboard.KEY_F1, true, false, true, visibilityChange));
        worldTick(true, hidden[0], false, true);
        assertTrue(EngineeringInputPolicy.visibilityKey(Keyboard.KEY_F1, true, true, true, visibilityChange));
        assertTrue(hidden[0]);
        assertTrue(EngineeringInputPolicy.visibilityKey(Keyboard.KEY_F1, false, false, true, visibilityChange));
        assertTrue(EngineeringInputPolicy.visibilityKey(Keyboard.KEY_F1, true, false, true, visibilityChange));
        worldTick(true, hidden[0], false, true);
        FactoryChrome.mouse(28, 21, 0, false, 0);
        assertTrue(actions.isEmpty());
        FactoryChrome.mouse(28, 21, 0, true, 0);
        FactoryChrome.mouse(28, 21, 0, false, 0);
        assertEquals(java.util.Collections.singletonList(Action.DASHBOARD), actions);
    }

    @Test
    public void successfulReleaseCanClearChromeWhileOpeningTheDashboard() {
        WorkbenchLayout layout = new WorkbenchLayout(640, 400, false, false, false, false);
        FactoryChrome.prepare(640, 400, layout, false, action -> {
            actions.add(action);
            // WorkbenchHud.openDashboard releases the old world scene from inside the click callback.
            FactoryChrome.blur();
            FactoryChrome.clear();
        }, () -> {}, "Settings");
        assertTrue(FactoryChrome.mouse(28, 21, 0, true, 0));
        worldTick(true, false, false, true);
        assertTrue(FactoryChrome.mouse(28, 21, 0, false, 0));
        assertEquals(java.util.Collections.singletonList(Action.DASHBOARD), actions);
        assertFalse(FactoryChrome.mouse(28, 21, 0, false, 0));
    }
}
