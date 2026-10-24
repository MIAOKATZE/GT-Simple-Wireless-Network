package com.miaokatze.gtswn.client.engineering;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtswn.client.engineering.FactoryShortcuts.Action;
import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.miaokatzecore.api.ui.component.UiEvent;
import com.miaokatze.miaokatzecore.api.ui.component.UiNode;
import com.miaokatze.miaokatzecore.api.ui.component.UiScene;
import com.miaokatze.miaokatzecore.api.ui.component.UiStyle;
import com.miaokatze.miaokatzecore.client.ui.MinecraftUiRenderer;

/** Shared application chrome assembled from the same Core components as desktop editing. */
final class FactoryChrome {

    private static final UiStyle STYLE = new UiStyle(
        new UiStyle.Paint(0xFF1C2D38, 0xFF304652, 0xFFE7ECEA),
        new UiStyle.Paint(0xFF344D5B, 0xFF577382, 0xFFFFFFFF),
        new UiStyle.Paint(0xFF203B46, 0xFF75C3B7, 0xFFFFFFFF),
        new UiStyle.Paint(0xFF375C5B, 0xFF75C3B7, 0xFFFFFFFF),
        new UiStyle.Paint(0xFF172731, 0xFF304652, 0xFF879CA8));
    private static UiScene scene;
    private static String signature = "";

    private FactoryChrome() {}

    static void render(FontRenderer font, int width, int height, WorkbenchLayout l, int task, boolean dashboard,
        boolean sources, boolean settings, Consumer<Action> action, IntConsumer navigate, Runnable toggleSources,
        Runnable toggleSettings) {
        String next = width + ":" + height + ":" + task + ":" + sources + ":" + settings + ":" + dashboard;
        if (scene == null || !signature.equals(next) && scene.captured() == null) {
            if (scene != null) scene.close();
            signature = next;
            UiNode root = UiNode.panel("factory-chrome")
                .pointerTransparent(true)
                .style(new UiStyle(new UiStyle.Paint(0, 0, 0xFFE7ECEA)));
            UiNode header = UiNode.panel("application-header")
                .bounds(0, 0, width, 60)
                .style(new UiStyle(new UiStyle.Paint(0xFF14212B, 0xFF344A59, 0xFFE7ECEA)));
            root.add(header);
            header.add(
                UiNode.label("brand", "GTSWN")
                    .bounds(10, 7, 105, 27));
            for (int i = 0; i < l.tasks.length; i++) {
                final int index = i;
                Rect r = l.tasks[i];
                header.add(
                    new UiNode("page-" + i, UiNode.Kind.TAB).bounds(r.x, r.y, r.w, r.h)
                        .text(tr("task." + i))
                        .style(STYLE)
                        .selected(task == i)
                        .onClick(() -> navigate.accept(index)));
            }
            Action[] commands = { Action.CAMERA, Action.RETURN, Action.FOLLOW, Action.TOGGLE };
            String[] names = { "camera", "return", "following", "exit" };
            for (int i = 0; i < commands.length; i++) {
                Action command = commands[i];
                Rect r = l.modes[i + 1];
                header.add(
                    UiNode.button("command-" + command, tr(names[i]), () -> action.accept(command))
                        .bounds(r.x, r.y, r.w, r.h)
                        .style(STYLE)
                        .on(UiEvent.Type.DOWN, e -> {
                            if (e.button == 1) {
                                FactoryShortcuts.beginBinding(command);
                                e.consume();
                            }
                        }));
            }
            header.add(
                UiNode.label("context", tr(dashboard ? "desktop.local" : "desktop.world"))
                    .bounds(l.cameraHints.x, l.cameraHints.y, l.cameraHints.w, l.cameraHints.h));
            header.add(
                UiNode.button("sources", tr("sources"), toggleSources)
                    .bounds(l.sources.x, l.sources.y, l.sources.w, l.sources.h)
                    .style(STYLE)
                    .selected(sources));
            header.add(
                UiNode.button("settings", tr("settings"), toggleSettings)
                    .bounds(l.shortcuts.x, l.shortcuts.y, l.shortcuts.w, l.shortcuts.h)
                    .style(STYLE)
                    .selected(settings));
            scene = new UiScene(root);
        }
        scene.find("command-FOLLOW")
            .selected(EngineeringModeController.INSTANCE.following());
        scene.find("command-CAMERA")
            .selected(EngineeringModeController.INSTANCE.cameraPanel());
        scene.layout(width, height);
        Minecraft mc = Minecraft.getMinecraft();
        scene.render(new MinecraftUiRenderer(font, width, height, mc.displayWidth, mc.displayHeight));
    }

    static boolean mouse(int x, int y, int button, boolean pressed, int wheel) {
        if (scene == null) return false;
        if (button >= 0 && pressed && y >= 60 && scene.captured() == null) scene.blur();
        if (wheel != 0) return scene.scroll(x, y, -Math.signum(wheel) * 24);
        if (button >= 0) return pressed ? scene.pointerDown(x, y, button) : scene.pointerUp(x, y, button);
        return scene.pointerMove(x, y);
    }

    static void blur() {
        if (scene != null) scene.blur();
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
