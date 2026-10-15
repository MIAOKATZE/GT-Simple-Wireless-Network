package com.miaokatze.gtswn.client.testworld;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** Explicit, guarded screen-level dispatch. Does not synthesize LWJGL events or invoke widget callbacks. */
final class UiModularInput {

    private static final String BUTTON = "com.cleanroommc.modularui.widgets.ButtonWidget";

    private UiModularInput() {}

    static Map<String, Object> click(Minecraft mc, GuiScreen wrapper, String path) {
        Map<String, Object> result = UiDebugSnapshot.map(
            "path",
            path,
            "status",
            "NOT_RUN",
            "inputEvidence",
            "NONE",
            "functionalStatus",
            "NOT_VERIFIED",
            "pressAttempted",
            false,
            "releaseAttempted",
            false,
            "contextRestored",
            false);
        ModularScreen screen = null;
        ModularPanel panel = null;
        ModularGuiContext context = null;
        Api api = null;
        int oldX = 0, oldY = 0;
        float oldPartial = 0;
        boolean moved = false, attempted = false;
        try {
            require(
                mc != null && mc.currentScreen == wrapper && UiModularSnapshot.supports(wrapper),
                "Original MUI2 screen is not current");
            ModContainer mod = Loader.instance()
                .getIndexedModList()
                .get("modularui2");
            String version = mod == null ? "UNKNOWN" : mod.getVersion();
            result.put("muiVersion", version);
            require(
                "2.3.92".equals(version) || "2.3.92-1.7.10".equals(version),
                "MUI2 input is gated to version 2.3.92");
            screen = UiModularSnapshot.wrappedScreen(wrapper);
            api = new Api(); // Resolve every reflective method/field before coordinates or inputs change.
            require(screen.isActive() && !screen.isOverlay(), "Inactive or overlay screen");
            require(api.noOverlays(), "Global MUI2 overlays are unsupported");
            require(
                mc.thePlayer != null && mc.thePlayer.inventory.getItemStack() == null,
                "Cursor inventory must be empty");
            require(!mc.gameSettings.touchscreen, "Touchscreen input is unsupported");
            require(Mouse.isCreated() && Keyboard.isCreated(), "Native input state is unavailable");
            for (int button = 0; button < Mouse.getButtonCount(); button++)
                require(!Mouse.isButtonDown(button), "A physical mouse button is held");
            require(
                !GuiScreen.isShiftKeyDown() && !GuiScreen.isCtrlKeyDown()
                    && !Keyboard.isKeyDown(Keyboard.KEY_LMENU)
                    && !Keyboard.isKeyDown(Keyboard.KEY_RMENU),
                "A keyboard modifier is held");
            List<ModularPanel> panels = screen.getPanelManager()
                .getOpenPanels();
            require(panels.size() == 1, "Only a single stable panel is supported");
            panel = panels.get(0);
            require(
                panel.isValid() && panel.isEnabled()
                    && "OPEN".equals(String.valueOf(panel.getState()))
                    && !panel.isOpening()
                    && !panel.isClosing()
                    && !panel.requiresResize(),
                "Panel is not stable");
            require(api.resize.get(panel) == null, "A panel resize operation is active");
            require(
                !api.held.getBoolean(api.mouse.get(panel)) && !api.held.getBoolean(api.keyboard.get(panel)),
                "A panel input is held");
            context = screen.getContext();
            require(!context.hasDraggable() && !context.isFocused(), "Dragging or a focused input is unsupported");
            require(api.ghost.invoke(null, context) == null, "NEI ghost drag is active");
            IWidget target = resolve(screen, path);
            require(
                target != null && BUTTON.equals(
                    target.getClass()
                        .getName()),
                "Only an exact standard ButtonWidget path is supported");
            require(
                target.isValid() && target.isEnabled() && target.getPanel() == panel && !target.requiresResize(),
                "Target is invalid, disabled, or awaiting layout");
            Map<String, Object> center = UiModularSnapshot.actionTarget(mc, wrapper, path);
            require(center != null, "Target has no known enabled visible rectangle");
            int x = ((Number) center.get("x")).intValue(), y = ((Number) center.get("y")).intValue();
            result.put("x", x);
            result.put("y", y);
            oldX = context.getAbsMouseX();
            oldY = context.getAbsMouseY();
            oldPartial = context.getPartialTicks();
            moved = true;
            api.update.invoke(context, x, y, oldPartial);
            api.hover.invoke(panel);
            api.contextFrame.invoke(context);
            require(
                mc.currentScreen == wrapper && panel.isValid() && target.isValid(),
                "UI changed during hover refresh");
            require(
                api.noOverlays() && !context.hasDraggable() && api.resize.get(panel) == null,
                "Overlay or dragging appeared during hover refresh");
            boolean found = false;
            for (Object located : panel.getAllHoveringList(false)) {
                IWidget hit = (IWidget) api.element.invoke(located);
                require(hit != null && hit.isValid(), "Unknown or invalid hit-test entry");
                require(api.additional.invoke(located) == null, "Custom hit-test or resize regions are unsupported");
                if (hit == target) {
                    found = true;
                    break;
                }
                require(
                    !(hit instanceof Interactable) && hit.canClickThrough(),
                    "An earlier hit-test entry can intercept this button");
            }
            require(
                found && screen.getPanelManager()
                    .getTopHoveredPanel() == panel,
                "Target center is clipped or obscured in the real hit-test chain");
            require(
                target.isEnabled() && !target.requiresResize()
                    && !panel.requiresResize()
                    && !panel.isOpening()
                    && !panel.isClosing()
                    && !context.isFocused(),
                "UI became unstable during hover refresh");
            for (int button = 0; button < Mouse.getButtonCount(); button++)
                require(!Mouse.isButtonDown(button), "A physical mouse button became held");
            require(
                !GuiScreen.isShiftKeyDown() && !GuiScreen.isCtrlKeyDown()
                    && !Keyboard.isKeyDown(Keyboard.KEY_LMENU)
                    && !Keyboard.isKeyDown(Keyboard.KEY_RMENU),
                "A keyboard modifier became held");
            require(
                !api.held.getBoolean(api.mouse.get(panel)) && !api.held.getBoolean(api.keyboard.get(panel))
                    && api.ghost.invoke(null, context) == null
                    && mc.thePlayer.inventory.getItemStack() == null,
                "Another input became active during hover refresh");
            // Mark before invoke: a callback may run and then throw or return false. Never retry it.
            attempted = true;
            result.put("pressAttempted", true);
            result.put("status", "SENT");
            result.put("inputEvidence", "SYNTHETIC_FRAMEWORK_DISPATCH");
            result.put("pressConsumed", api.press.invoke(screen, 0));
            Object pressed = api.lastPressed.get(api.mouse.get(panel));
            result.put("targetObservedPressed", pressed != null && api.element.invoke(pressed) == target);
            result.put("reason", "Production MUI2 screen press dispatched once; server effects are NOT_VERIFIED");
        } catch (Exception | LinkageError failure) {
            Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
                ? failure.getCause()
                : failure;
            result.put(
                "reason",
                cause.getClass()
                    .getSimpleName() + ": "
                    + cause.getMessage());
        } finally {
            if (attempted && api != null && screen != null) {
                try {
                    // Never send release to a replacement screen or invoke a disposed original panel.
                    if (panel != null && panel.isValid() && screen.isActive()) {
                        result.put("releaseAttempted", true);
                        result.put("releaseConsumed", api.release.invoke(screen, 0));
                        result.put("releaseStatus", "SENT");
                    } else result.put("releaseStatus", "NOT_VERIFIED_ORIGINAL_SCREEN_DISPOSED");
                } catch (Exception | LinkageError failure) {
                    result.put(
                        "releaseStatus",
                        "NOT_VERIFIED_" + failure.getClass()
                            .getSimpleName());
                }
            }
            if (moved && api != null && context != null) {
                try {
                    api.update.invoke(context, oldX, oldY, oldPartial);
                    result.put("contextRestored", true);
                    if (mc.currentScreen == wrapper && panel != null && panel.isValid() && screen.isActive()) {
                        api.hover.invoke(panel);
                        api.contextFrame.invoke(context);
                        result.put("hoverRestored", true);
                    }
                } catch (Exception | LinkageError failure) {
                    result.put(
                        "restoreStatus",
                        "NOT_VERIFIED_" + failure.getClass()
                            .getSimpleName());
                }
            }
        }
        return result;
    }

    private static IWidget resolve(ModularScreen screen, String path) {
        if (path == null || path.length() > 1024) return null;
        String[] parts = path.split("/", -1);
        if (parts.length > 33 || !parts[0].matches("panel\\[[0-9]{1,3}\\]")) return null;
        int index = Integer.parseInt(parts[0].substring(6, parts[0].length() - 1));
        List<ModularPanel> panels = screen.getPanelManager()
            .getOpenPanels();
        if (index >= panels.size()) return null;
        IWidget widget = panels.get(index);
        for (int depth = 1; depth < parts.length; depth++) {
            if (!parts[depth].matches("child\\[[0-9]{1,3}\\]") || !widget.isValid()
                || !widget.isEnabled()
                || widget.requiresResize()) return null;
            index = Integer.parseInt(parts[depth].substring(6, parts[depth].length() - 1));
            List<IWidget> children = widget.getChildren();
            if (children == null || index >= children.size() || index >= 512) return null;
            IWidget child = children.get(index);
            if (child == null || child == widget || child.getParent() != widget) return null;
            widget = child;
        }
        return widget;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    private static final class Api {

        private final Method update, hover, contextFrame, press, release, element, additional, overlays, ghost;
        private final Field mouse, keyboard, held, lastPressed, resize;

        private Api() throws ReflectiveOperationException {
            Class<?> guiContext = Class.forName("com.cleanroommc.modularui.screen.viewport.GuiContext");
            Class<?> located = Class.forName("com.cleanroommc.modularui.screen.viewport.LocatedElement");
            Class<?> input = Class.forName("com.cleanroommc.modularui.screen.ModularPanel$Input");
            Class<?> overlay = Class.forName("com.cleanroommc.modularui.overlay.OverlayStack");
            Class<?> nei = Class.forName("com.cleanroommc.modularui.integration.nei.NEIUtil");
            update = guiContext.getDeclaredMethod("updateState", int.class, int.class, float.class);
            hover = ModularPanel.class.getDeclaredMethod("findHoveredWidgets");
            hover.setAccessible(true);
            contextFrame = ModularGuiContext.class.getDeclaredMethod("onFrameUpdate");
            press = ModularScreen.class.getDeclaredMethod("onMousePressed", int.class);
            release = ModularScreen.class.getDeclaredMethod("onMouseRelease", int.class);
            element = located.getDeclaredMethod("getElement");
            additional = Class.forName("com.cleanroommc.modularui.screen.viewport.LocatedWidget")
                .getDeclaredMethod("getAdditionalHoverInfo");
            overlays = overlay.getDeclaredMethod("foreach", Consumer.class, boolean.class);
            ghost = nei.getDeclaredMethod("getNEIDragAndDropTarget", ModularGuiContext.class);
            mouse = field(ModularPanel.class, "mouse");
            keyboard = field(ModularPanel.class, "keyboard");
            resize = field(ModularPanel.class, "currentResizing");
            held = field(input, "held");
            lastPressed = field(input, "lastPressed");
        }

        private boolean noOverlays() throws ReflectiveOperationException {
            boolean[] empty = { true };
            overlays.invoke(null, (Consumer<Object>) value -> empty[0] = false, true);
            return empty[0];
        }

        private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }
}
