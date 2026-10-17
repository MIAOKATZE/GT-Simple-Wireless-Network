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
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.PageButton;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** Explicit, guarded screen-level dispatch. Does not synthesize LWJGL events or invoke widget callbacks. */
final class UiModularInput {

    private UiModularInput() {}

    static Map<String, Object> click(Minecraft mc, GuiScreen wrapper, String path) {
        return click(mc, wrapper, path, 0);
    }

    static Map<String, Object> click(Minecraft mc, GuiScreen wrapper, String path, int mouseButton) {
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
            require(mouseButton == 0 || mouseButton == 1, "Only left/right mouse buttons are supported");
            result.put("mouseButton", mouseButton);
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
            IWidget target = resolve(screen, path);
            require(target != null, "No observed widget at the exact path");
            panel = target.getPanel();
            require(panel != null && panels.contains(panel), "Target panel is not open");
            for (ModularPanel above : panels) {
                if (above == panel) break;
                require(!above.disablePanelsBelow(), "A modal panel blocks input to this lower panel");
            }
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
            require(!context.hasDraggable(), "Dragging is unsupported");
            require(api.ghost.invoke(null, context) == null, "NEI ghost drag is active");
            require(
                target instanceof ButtonWidget<?> || target instanceof PageButton || target instanceof TextFieldWidget,
                "Only a native ButtonWidget or TextFieldWidget is supported");
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
                    && !panel.isClosing(),
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
            result.put("pressConsumed", api.press.invoke(screen, mouseButton));
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
                        result.put("releaseConsumed", api.release.invoke(screen, mouseButton));
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

    static IWidget resolve(ModularScreen screen, String path) {
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

    /** Uses the production focus, key and text-event routes; never mutates a value/handler directly. */
    static Map<String, Object> type(Minecraft mc, GuiScreen wrapper, String path, String text) {
        Map<String, Object> result = click(mc, wrapper, path);
        result.put("operation", "type");
        if (!"SENT".equals(result.get("status"))) return result;
        try {
            ModularScreen screen = UiModularSnapshot.wrappedScreen(wrapper);
            IWidget widget = resolve(screen, path);
            require(widget instanceof TextFieldWidget, "Target is not a native text field");
            TextFieldWidget field = (TextFieldWidget) widget;
            require(field.isFocused() && mc.currentScreen == wrapper, "Production click did not focus target");
            require(text.length() <= 256, "Text is limited to 256 characters");
            Class<?> event = Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents$TextEvent");
            Method textEvent = ModularScreen.class.getMethod("onTextEvent", event);
            // Home then Delete uses normal key handlers, with no modifier injection or clipboard changes.
            screen.onKeyPressed('\0', Keyboard.KEY_HOME);
            screen.onKeyRelease('\0', Keyboard.KEY_HOME);
            int count = field.getText()
                .length();
            for (int i = 0; i < count; i++) {
                require(field.isFocused() && mc.currentScreen == wrapper, "Focus changed during deletion");
                screen.onKeyPressed('\0', Keyboard.KEY_DELETE);
                screen.onKeyRelease('\0', Keyboard.KEY_DELETE);
            }
            require(
                field.getText()
                    .isEmpty(),
                "Normal delete route did not clear target");
            textEvent.invoke(
                screen,
                event.getConstructor(String.class)
                    .newInstance(text));
            result.put("inputEvidence", "PRODUCTION_MUI2_FOCUS_KEY_AND_TEXT_EVENT");
            result.put("observedTextMatchesRequest", text.equals(field.getText()));
            result.put("observedText", field.getText());
            result.put(
                "reason",
                "Native text event delivered to focused field; server effect requires independent data assertion");
        } catch (Exception | LinkageError failure) {
            result.put("reason", failure.toString());
            result.put("textStatus", "NOT_VERIFIED");
        }
        return result;
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
