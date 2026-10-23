package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.miaokatze.miaokatzecore.api.camera.HeightSlice;

public class FactoryXrayStateTest {

    @Test
    public void firstToggleStartsAtTheCameraLayerWithinTheWorldHeight() {
        FactoryXrayState state = new FactoryXrayState();
        assertFalse(
            state.requested()
                .enabled());
        state.toggle(72.8);
        assertTrue(
            state.requested()
                .enabled());
        assertEquals(
            73,
            state.requested()
                .maximumY());
        state.reset();
        state.toggle(500);
        assertEquals(
            256,
            state.requested()
                .maximumY());
        state.reset();
        state.toggle(-20);
        assertEquals(
            1,
            state.requested()
                .maximumY());
    }

    @Test
    public void togglingAfterToolbarEditsRestoresTheWholeRequestedPolicy() {
        FactoryXrayState state = new FactoryXrayState();
        HeightSlice edited = HeightSlice.above(42, 1.5)
            .withFeatheredCaps(true)
            .withLightCompensation(true);
        state.request(edited);
        state.toggle(100);
        assertFalse(
            state.requested()
                .enabled());
        state.toggle(100);
        assertSame(edited, state.requested());
        state.request(HeightSlice.disabled());
        state.toggle(100);
        assertSame(edited, state.requested());
    }

    @Test
    public void leavingFactoryModeClearsRememberedSlices() {
        FactoryXrayState state = new FactoryXrayState();
        state.request(HeightSlice.between(10, 30, .75));
        state.reset();
        assertFalse(
            state.requested()
                .enabled());
        state.toggle(80);
        assertFalse(
            state.requested()
                .hasLower());
        assertEquals(
            81,
            state.requested()
                .maximumY());
    }
}
