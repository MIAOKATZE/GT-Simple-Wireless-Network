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

    static CameraSettings.Hud hud(boolean cameraPanel, double scale) {
        // Core chooses its compact X-ray bar or the combined camera panel from these two flags.
        return CameraSettings.Hud.builder()
            .crosshair(CameraSettings.Hud.Crosshair.HIDDEN)
            .panelVisible(cameraPanel)
            .sliceBarVisible(true)
            .panelPosition((int) (264 * scale), (int) (56 * scale))
            .panelWidth(Math.max(1, (int) (192 * scale)))
            .build();
    }

    static CameraSettings.Navigation navigation(boolean following) {
        return navigation(following, 1, 1);
    }

    static CameraSettings.Navigation navigation(boolean following, double rotation, double wheel) {
        return CameraSettings.Navigation.builder()
            .pan(!following)
            .yaw(true)
            .pitch(true)
            .zoom(true)
            .reset(true)
            .rotateDrag(1, 0)
            .rotationDegreesPerPixel(.35 * FactoryControlSettings.Setting.ROTATION.sanitize(rotation))
            .wheelZoom(CameraSettings.Navigation.WheelZoom.DIRECT)
            .wheelDeltaUnit(CameraSettings.Navigation.WheelDeltaUnit.AUTO)
            .wheelSensitivity(FactoryControlSettings.Setting.WHEEL_ZOOM.sanitize(wheel))
            .pitchRange(15, 85)
            .scaleRange(.4, 128)
            .basePixelsPerWorldUnit(16)
            .sliceRange(0, 256)
            .build();
    }
}
