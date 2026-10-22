package com.miaokatze.gtswn.client.engineering;

import com.miaokatze.miaokatzecore.client.camera.CameraControl;
import com.miaokatze.miaokatzecore.client.camera.CameraSettings;

/** World keyboard polling and raw pointer capture have separate ownership rules. */
final class EngineeringInputPolicy {

    private EngineeringInputPolicy() {}

    static boolean worldInput(boolean active, boolean screenOpen, boolean focused) {
        return active && !screenOpen && focused;
    }

    static boolean pointerControl(CameraControl control, boolean following, boolean captured, boolean overHud) {
        if (control == CameraControl.PAN && following) return false;
        // Hover only gates the start of a mouse gesture. A captured drag must finish across HUD boundaries.
        return captured || !overHud
            || control != CameraControl.PAN && control != CameraControl.YAW
                && control != CameraControl.PITCH
                && control != CameraControl.ZOOM;
    }

    static CameraSettings.Navigation navigation(boolean following) {
        return CameraSettings.Navigation.builder()
            .pan(!following)
            .yaw(true)
            .pitch(true)
            .zoom(true)
            .reset(true)
            .rotateDrag(1, 0)
            .wheelZoom(CameraSettings.Navigation.WheelZoom.DIRECT)
            .wheelDeltaUnit(CameraSettings.Navigation.WheelDeltaUnit.AUTO)
            .pitchRange(15, 85)
            .scaleRange(2, 128)
            .basePixelsPerWorldUnit(16)
            .sliceRange(0, 256)
            .build();
    }
}
