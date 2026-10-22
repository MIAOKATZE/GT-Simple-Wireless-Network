package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.miaokatze.miaokatzecore.client.camera.CameraControl;
import com.miaokatze.miaokatzecore.client.camera.CameraSettings;

public class EngineeringInputPolicyTest {

    @Test
    public void normalWheelUsesCoreDirectZoomAndExistingDragButtons() {
        CameraSettings.Navigation navigation = EngineeringInputPolicy.navigation(false);
        assertEquals(CameraSettings.Navigation.WheelZoom.DIRECT, navigation.wheelZoom());
        assertEquals(CameraSettings.Navigation.WheelDeltaUnit.AUTO, navigation.wheelDeltaUnit());
        assertTrue(navigation.zoom());
        assertEquals(1, navigation.rotateButton());
        assertEquals(0, navigation.rotateKey());
        assertEquals(2, navigation.panButton());
        assertTrue(navigation.pan());
        assertFalse(
            EngineeringInputPolicy.navigation(true)
                .pan());
    }

    @Test
    public void realGuiAndLostFocusOwnInputUntilWorldContextReturns() {
        assertTrue(EngineeringInputPolicy.worldInput(true, false, true));
        assertFalse(EngineeringInputPolicy.worldInput(true, true, true));
        assertFalse(EngineeringInputPolicy.worldInput(true, false, false));
        assertFalse(EngineeringInputPolicy.worldInput(false, false, true));
    }

    @Test
    public void hudBlocksNewMouseNavigationButCannotInterruptCapturedDrag() {
        for (CameraControl control : new CameraControl[] { CameraControl.PAN, CameraControl.YAW, CameraControl.PITCH,
            CameraControl.ZOOM }) {
            assertFalse(EngineeringInputPolicy.pointerControl(control, false, false, true));
            assertTrue(EngineeringInputPolicy.pointerControl(control, false, true, true));
            assertTrue(EngineeringInputPolicy.pointerControl(control, false, false, false));
        }
        assertTrue(EngineeringInputPolicy.pointerControl(CameraControl.SLICE, false, false, true));
        assertFalse(EngineeringInputPolicy.pointerControl(CameraControl.PAN, true, true, false));
    }

    @Test
    public void keyboardPanIsIndependentOfPointerHoverAndUsesActualZoom() {
        // A HUD consumes its mouse wheel; it does not change the keyboard world-input predicate.
        assertFalse(EngineeringInputPolicy.pointerControl(CameraControl.PAN, false, false, true));
        assertTrue(EngineeringInputPolicy.worldInput(true, false, true));
        double[] close = EngineeringMotion.delta(45, 2, 1, 1, 0);
        double[] far = EngineeringMotion.delta(45, .5, 1, 1, 0);
        for (int i = 0; i < close.length; i++) assertEquals(close[i] * 4, far[i], 1e-12);
    }
}
