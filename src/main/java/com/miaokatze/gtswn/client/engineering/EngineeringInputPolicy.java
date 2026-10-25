package com.miaokatze.gtswn.client.engineering;

import org.lwjgl.input.Keyboard;

import com.miaokatze.miaokatzecore.client.camera.CameraControl;
import com.miaokatze.miaokatzecore.client.camera.CameraSettings;
import com.miaokatze.miaokatzecore.client.camera.WorldCameras;

/** World keyboard polling and raw pointer capture have separate ownership rules. */
final class EngineeringInputPolicy {

    private EngineeringInputPolicy() {}

    /** Both GUI and world hosts own F1 before text fields, binding prompts and vanilla dispatch. */
    static boolean visibilityKey(int code, boolean pressed, boolean repeat, boolean focused, Runnable toggle) {
        if (code != Keyboard.KEY_F1) return false;
        // Consume the complete sequence. Passing repeats to vanilla would toggle hideGUI again.
        if (focused && pressed && !repeat) toggle.run();
        return true;
    }

    static boolean dashboardHostVisible(boolean live, boolean hidden, boolean directScreen, boolean factory,
        boolean screenOpen) {
        return live && !hidden && (directScreen || factory && !screenOpen);
    }

    static boolean dashboardInputAllowed(boolean visible, boolean displayFocused, boolean directScreen,
        boolean worldFocused) {
        return visible && displayFocused && (directScreen || worldFocused);
    }

    static boolean dashboardBinding(int code, int dashboard, int factory, int wheel) {
        return code > Keyboard.KEY_NONE && code < Keyboard.KEYBOARD_SIZE
            && code == dashboard
            && code != factory
            && code != wheel
            && code != Keyboard.KEY_ESCAPE
            && code != Keyboard.KEY_F1;
    }

    static boolean overlayVisible(boolean dashboardOpen, boolean hidden) {
        return dashboardOpen && !hidden;
    }

    static boolean worldInput(boolean active, boolean screenOpen, boolean focused) {
        return active && !screenOpen && focused;
    }

    static boolean worldNavigation(boolean active, boolean screenOpen, boolean focused, boolean desktop) {
        return worldInput(active, screenOpen, focused) && !desktop;
    }

    static boolean playerMovement(boolean following, boolean desktop) {
        return following && !desktop;
    }

    static WorldCameras.MovementPolicy movement(boolean following, boolean desktop) {
        return playerMovement(following, desktop) ? WorldCameras.MovementPolicy.NONE : WorldCameras.MovementPolicy.ALL;
    }

    static boolean pointerControl(CameraControl control, boolean following, boolean captured, boolean overHud,
        boolean desktop) {
        return !desktop && pointerControl(control, following, captured, overHud);
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
        return hud(cameraPanel, scale, false);
    }

    static CameraSettings.Hud hud(boolean cameraPanel, double scale, boolean desktop) {
        return hud(cameraPanel, scale, desktop, false);
    }

    static CameraSettings.Hud hud(boolean cameraPanel, double scale, boolean desktop, boolean hidden) {
        // Core chooses its compact X-ray bar or the combined camera panel from these two flags.
        return CameraSettings.Hud.builder()
            .crosshair(CameraSettings.Hud.Crosshair.HIDDEN)
            .panelVisible(cameraPanel && !desktop && !hidden)
            .sliceBarVisible(!desktop && !hidden)
            .panelPosition((int) (264 * scale), (int) (56 * scale))
            .panelWidth(Math.max(1, (int) (192 * scale)))
            .build();
    }

    static CameraSettings.Navigation navigation(boolean following) {
        return navigation(following, 1, 1);
    }

    static CameraSettings.Navigation navigation(boolean following, double rotation, double wheel) {
        return navigation(following, rotation, wheel, false);
    }

    static CameraSettings.Navigation navigation(boolean following, double rotation, double wheel, boolean desktop) {
        return CameraSettings.Navigation.builder()
            .pan(!desktop && !following)
            .yaw(!desktop)
            .pitch(!desktop)
            .zoom(!desktop)
            .reset(!desktop)
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
