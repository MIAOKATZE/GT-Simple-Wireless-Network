package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.lwjgl.input.Keyboard;

/** Exercises the production queue-boundary router without creating a native keyboard or client. */
public class EngineeringVisibilityInputTest {

    @Test
    public void bothHostsConsumeF1BeforeTextBindingAndVanillaHandlers() {
        for (boolean direct : new boolean[] { false, true }) {
            Host host = new Host(direct);
            host.event(Keyboard.KEY_F1, true, false, true);
            assertTrue(host.hidden);
            assertFalse(host.visible());
            assertEquals(1, host.suspensions);
            // LWJGL repeat DOWNs and the matching UP must never reach vanilla's F1 toggle.
            for (int repeat = 0; repeat < 4; repeat++) host.event(Keyboard.KEY_F1, true, true, true);
            host.event(Keyboard.KEY_F1, false, false, true);
            assertTrue(host.hidden);
            assertEquals(1, host.suspensions);
            assertEquals(0, host.downstreamEvents);
            host.event(Keyboard.KEY_F1, true, false, true);
            host.event(Keyboard.KEY_F1, false, false, true);
            assertFalse(host.hidden);
            assertTrue(host.visible());
            assertEquals(2, host.suspensions);
            assertEquals(0, host.downstreamEvents);
        }
    }

    @Test
    public void unfocusedF1IsConsumedWithoutChangingVisibilityOrRestoringOldFocus() {
        Host host = new Host(true);
        host.event(Keyboard.KEY_F1, true, false, false);
        host.event(Keyboard.KEY_F1, false, false, false);
        assertFalse(host.hidden);
        assertEquals(0, host.suspensions);
        assertEquals(0, host.downstreamEvents);
    }

    @Test
    public void escapeAndNormalEditorEventsKeepTheirExistingHostRoute() {
        Host host = new Host(true);
        host.event(Keyboard.KEY_F1, true, false, true);
        host.event(Keyboard.KEY_ESCAPE, true, false, true);
        host.event(Keyboard.KEY_A, true, false, true);
        host.event(Keyboard.KEY_A, true, true, true);
        host.event(Keyboard.KEY_A, false, false, true);
        assertEquals(4, host.downstreamEvents);
        assertTrue(host.hidden);
        assertEquals(1, host.suspensions);
    }

    private static final class Host {

        final boolean direct;
        boolean hidden;
        int suspensions, downstreamEvents;

        Host(boolean direct) {
            this.direct = direct;
        }

        void event(int code, boolean pressed, boolean repeat, boolean focused) {
            // GUI handleKeyboardInput and Core's allowRawKey use this same consumed-event boundary.
            if (EngineeringInputPolicy.visibilityKey(code, pressed, repeat, focused, () -> {
                hidden = !hidden;
                suspensions++;
            })) return;
            downstreamEvents++;
        }

        boolean visible() {
            return EngineeringInputPolicy.dashboardHostVisible(true, hidden, direct, !direct, direct);
        }
    }
}
