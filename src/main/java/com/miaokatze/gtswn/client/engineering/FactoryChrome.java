package com.miaokatze.gtswn.client.engineering;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtswn.client.engineering.FactoryShortcuts.Action;
import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.DashboardTheme;
import com.miaokatze.miaokatzecore.api.ui.component.UiEvent;
import com.miaokatze.miaokatzecore.api.ui.component.UiNode;
import com.miaokatze.miaokatzecore.api.ui.component.UiScene;
import com.miaokatze.miaokatzecore.api.ui.component.UiStyle;
import com.miaokatze.miaokatzecore.client.ui.MinecraftUiRenderer;

/** Compact world controls, with Core retaining pointer capture and keyboard focus. */
final class FactoryChrome {

    private static DashboardTheme styledTheme;
    private static UiStyle styledPalette;
    private static UiScene scene;
    private static String signature = "";

    private FactoryChrome() {}

    static void render(FontRenderer font, int width, int height, WorkbenchLayout l, boolean settings,
        Consumer<Action> action, Runnable toggleSettings) {
        prepare(width, height, l, settings, action, toggleSettings, tr("settings"));
        scene.find("command-FOLLOW")
            .selected(EngineeringModeController.INSTANCE.following());
        scene.find("command-CAMERA")
            .selected(EngineeringModeController.INSTANCE.cameraPanel());
        Minecraft mc = Minecraft.getMinecraft();
        scene.render(new MinecraftUiRenderer(font, width, height, mc.displayWidth, mc.displayHeight));
    }

    /** Shared retained tree for rendering and headless input-lifecycle checks. No client state is read here. */
    static void prepare(int width, int height, WorkbenchLayout l, boolean settings, Consumer<Action> action,
        Runnable toggleSettings, String settingsLabel) {
        DashboardTheme theme = FactoryDisplayPreferences.theme();
        if (styledTheme != theme) {
            styledTheme = theme;
            styledPalette = new UiStyle(
                new UiStyle.Paint(theme.cardBottom, theme.edge, theme.text),
                new UiStyle.Paint(theme.header, theme.accent, theme.text),
                new UiStyle.Paint(theme.track, theme.accent, theme.text),
                new UiStyle.Paint(theme.header, theme.accent, theme.text),
                new UiStyle.Paint(theme.cardBottom, theme.edge, theme.muted));
        }
        UiStyle style = styledPalette;
        String next = width + ":" + height + ":" + settings + ":" + theme;
        if (scene == null || !signature.equals(next) && scene.captured() == null) {
            if (scene != null) scene.close();
            signature = next;
            UiNode root = UiNode.panel("factory-chrome")
                .pointerTransparent(true)
                .style(new UiStyle(new UiStyle.Paint(0, 0, 0xFFE7ECEA)));
            Action[] commands = { Action.DASHBOARD, Action.CAMERA, Action.RETURN, Action.FOLLOW, Action.TOGGLE };
            for (int i = 0; i < commands.length; i++) {
                Action command = commands[i];
                Rect r = l.modes[i];
                // Atlas glyphs and binding tooltips are painted by WorkbenchHud over these exact hit bounds.
                root.add(
                    UiNode.button("command-" + command, "", () -> action.accept(command))
                        .bounds(r.x, r.y, r.w, r.h)
                        .style(style)
                        .on(UiEvent.Type.DOWN, e -> {
                            if (e.button == 1) {
                                FactoryShortcuts.beginBinding(command);
                                e.consume();
                            }
                        }));
            }
            root.add(
                UiNode.button("settings", settingsLabel, toggleSettings)
                    .bounds(l.shortcuts.x, l.shortcuts.y, l.shortcuts.w, l.shortcuts.h)
                    .style(style)
                    .selected(settings));
            scene = new UiScene(root);
        }
        scene.layout(width, height);
    }

    /** Only this toolbar's world host can revoke its press; an unopened desktop cannot. */
    static void maintainInput(boolean hostInputAllowed) {
        if (!hostInputAllowed) blur();
    }

    static boolean mouse(int x, int y, int button, boolean pressed, int wheel) {
        if (scene == null) return false;
        if (button >= 0 && pressed && scene.hitTest(x, y) == null && scene.captured() == null) scene.blur();
        if (wheel != 0) return scene.scroll(x, y, -Math.signum(wheel) * 24);
        if (button >= 0) return pressed ? scene.pointerDown(x, y, button) : scene.pointerUp(x, y, button);
        return scene.pointerMove(x, y);
    }

    static void blur() {
        if (scene != null) scene.blur();
    }

    static void clear() {
        if (scene != null) scene.close();
        scene = null;
        signature = "";
    }

    static boolean key(int code, char character, boolean pressed) {
        return scene != null && pressed
            && code != Keyboard.KEY_ESCAPE
            && scene.keyTyped(
                character,
                code,
                Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT),
                Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
    }

    static boolean escape() {
        if (scene != null && (scene.captured() != null || scene.focused() != null)) {
            scene.blur();
            return true;
        }
        return false;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.factory." + key);
    }
}
