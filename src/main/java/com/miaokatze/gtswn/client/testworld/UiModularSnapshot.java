package com.miaokatze.gtswn.client.testworld;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.cleanroommc.modularui.api.layout.IViewport;
import com.cleanroommc.modularui.api.widget.ISynced;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.api.widget.ModernInteractable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.GuiViewportStack;
import com.cleanroommc.modularui.widget.AbstractScrollWidget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

/** Read-only observations of the active MUI2 tree; never creates widgets or runs event handlers. */
final class UiModularSnapshot {

    private static final int MAX_WIDGETS = 512;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_FINDINGS = 512;
    private static final int MAX_TEXT = 8192;
    private static final String UNKNOWN = "UNKNOWN";

    private UiModularSnapshot() {}

    static boolean supports(GuiScreen screen) {
        if (screen == null) return false;
        try {
            // IMuiScreen extends optional animation/input APIs absent from the compile classpath.
            // Resolve the actual runtime interface without making javac inspect those parents.
            return Class.forName(
                "com.cleanroommc.modularui.api.IMuiScreen",
                false,
                screen.getClass()
                    .getClassLoader())
                .isInstance(screen);
        } catch (ClassNotFoundException | LinkageError | SecurityException e) {
            return false;
        }
    }

    static ModularScreen wrappedScreen(GuiScreen screen) {
        try {
            Object actual = screen.getClass()
                .getMethod("getScreen")
                .invoke(screen);
            if (actual instanceof ModularScreen) return (ModularScreen) actual;
            throw new IllegalStateException("MUI2 wrapper getScreen returned no ModularScreen");
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new IllegalStateException("Cannot observe the actual MUI2 wrapper screen", e);
        }
    }

    static Map<String, Object> capture(Minecraft mc, GuiScreen screen) {
        Probe probe = new Probe(mc, screen);
        return probe.capture();
    }

    /** Resolves an explicit path to an observed center, without performing an input action. */
    static Map<String, Object> actionTarget(Minecraft mc, GuiScreen screen, String path) {
        if (path == null || !supports(screen)) return null;
        Map<String, Object> snapshot = capture(mc, screen);
        if (Boolean.TRUE.equals(snapshot.get("truncated"))) return null;
        Object observations = snapshot.get("findings");
        if (!(observations instanceof List<?>)) return null;
        for (Object observation : (List<?>) observations) {
            if (!(observation instanceof Map<?, ?>)) return null;
            Object code = ((Map<?, ?>) observation).get("code");
            if ("CAPTURE_INCOMPLETE".equals(code) || "CYCLE_OR_SHARED_CHILD".equals(code)
                || "NULL_CHILD".equals(code)
                || "UNKNOWN_CHILDREN".equals(code)
                || "UNKNOWN_WIDGET_DATA".equals(code)) return null;
        }
        Object entries = snapshot.get("widgets");
        if (!(entries instanceof List<?>)) return null;
        for (Object entry : (List<?>) entries) {
            if (!(entry instanceof Map<?, ?>)) continue;
            Map<?, ?> widget = (Map<?, ?>) entry;
            if (!path.equals(widget.get("path")) || !Boolean.TRUE.equals(widget.get("visible"))
                || !Boolean.TRUE.equals(widget.get("enabled"))
                || !Boolean.TRUE.equals(widget.get("interactive"))) continue;
            if (Boolean.TRUE.equals(widget.get("animating"))) return null;
            Object clipped = widget.get("visibleRect");
            if (!(clipped instanceof Map<?, ?>)) return null;
            Map<?, ?> rect = (Map<?, ?>) clipped;
            if (!(rect.get("w") instanceof Number) || !(rect.get("h") instanceof Number)) return null;
            int width = ((Number) rect.get("w")).intValue();
            int height = ((Number) rect.get("h")).intValue();
            if (width <= 0 || height <= 0) return null;
            return map(
                "path",
                path,
                "class",
                widget.get("class"),
                "x",
                ((Number) rect.get("x")).intValue() + width / 2,
                "y",
                ((Number) rect.get("y")).intValue() + height / 2,
                "coordinateSpace",
                "GUI_SCREEN",
                "occlusion",
                UNKNOWN);
        }
        return null;
    }

    static String namedPath(Minecraft mc, GuiScreen screen, String name) {
        Object rows = capture(mc, screen).get("widgets");
        String path = null;
        if (rows instanceof List<?>) for (Object row : (List<?>) rows) {
            if (!(row instanceof Map<?, ?>)) continue;
            Map<?, ?> widget = (Map<?, ?>) row;
            if (!name.equals(widget.get("name")) || !Boolean.TRUE.equals(widget.get("enabled"))
                || !Boolean.TRUE.equals(widget.get("visible"))) continue;
            if (path != null) throw new IllegalStateException("Ambiguous visible MUI2 control name: " + name);
            path = (String) widget.get("path");
        }
        return path;
    }

    private static final class Probe {

        private final Minecraft mc;
        private final GuiScreen wrapper;
        private final List<Map<String, Object>> widgets = new ArrayList<>();
        private final List<Map<String, Object>> findings = new ArrayList<>();
        private final List<String> limitations = new ArrayList<>();
        private final Set<IWidget> seen = Collections.newSetFromMap(new IdentityHashMap<IWidget, Boolean>());
        private boolean truncated;
        private Area screenArea;

        private Probe(Minecraft mc, GuiScreen wrapper) {
            this.mc = mc;
            this.wrapper = wrapper;
        }

        private Map<String, Object> capture() {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("explicitClick", "GUARDED_NATIVE_BUTTON_AND_TEXTFIELD");
            limitations.add(
                "Native ButtonWidget/TextFieldWidget clicks use guarded production screen press/release and real hit testing. Text uses native focus/key/text-event routes. Server effects, wheel, drag and overlays require separate assertions.");
            limitations.add(
                "Custom draw/foreground/tooltip pixels and custom stencil shapes are UNKNOWN; no draw callbacks are invoked.");
            limitations.add(
                "visible means enabled with a nonempty rectangle inside known screen/scroll clips; pixel visibility and occlusion are UNKNOWN.");
            limitations.add(
                "Overlaps are warnings: decorative overlays, sibling z order and modal panels can be intentional.");
            limitations.add(
                "textWidth is the widest unwrapped vanilla font line times text scale; wrapping and theme effects are not inferred.");
            try {
                if (!supports(wrapper)) {
                    limitations.add("The current screen does not implement MUI2 IMuiScreen.");
                } else {
                    ModularScreen screen = wrappedScreen(wrapper);
                    screenArea = new Area(screen.getScreenArea());
                    state.put("muiOwner", screen.getOwner());
                    state.put("muiName", screen.getName());
                    state.put("serverData", UiDebugSnapshot.dataObservation(mc, wrapper));
                    state.put("active", screen.isActive());
                    state.put("overlay", screen.isOverlay());
                    state.put("screenArea", rect(screenArea));
                    net.minecraft.inventory.Container container = mc.thePlayer == null ? null
                        : mc.thePlayer.openContainer;
                    if (container != null) {
                        state.put(
                            "containerClass",
                            container.getClass()
                                .getName());
                        state.put("containerWindowId", container.windowId);
                        state.put("containerSlotCount", container.inventorySlots.size());
                        if (container instanceof com.miaokatze.gtswn.common.gui.ContainerTerminal) {
                            boolean count = container.inventorySlots.size() == 37;
                            int heldSlot = 28 + mc.thePlayer.inventory.currentItem;
                            boolean locked = count && !container.getSlot(heldSlot)
                                .canTakeStack(mc.thePlayer)
                                && !container.getSlot(heldSlot)
                                    .isItemValid(mc.thePlayer.getHeldItem());
                            state.put("terminalHeldSlotLocked", locked);
                            if (!count) finding(
                                "FAIL",
                                "TERMINAL_SLOT_COUNT",
                                "screen",
                                null,
                                "The native terminal must expose exactly 37 slots");
                            if (!locked) finding(
                                "FAIL",
                                "TERMINAL_HELD_SLOT_UNLOCKED",
                                "screen",
                                null,
                                "The held terminal player slot must reject insertion and extraction");
                        }
                    }
                    widgets.add(
                        map(
                            "path",
                            "screen",
                            "parent",
                            null,
                            "class",
                            wrapper.getClass()
                                .getName(),
                            "kind",
                            "root",
                            "x",
                            screenArea.x,
                            "y",
                            screenArea.y,
                            "w",
                            screenArea.width,
                            "h",
                            screenArea.height,
                            "visible",
                            true,
                            "enabled",
                            true));
                    List<ModularPanel> panels = screen.getPanelManager()
                        .getOpenPanels();
                    state.put("panelCount", panels.size());
                    for (int i = 0; i < panels.size() && widgets.size() < MAX_WIDGETS; i++) {
                        GuiViewportStack stack = new GuiViewportStack();
                        stack.pushViewport(null, screenArea);
                        try {
                            visit(
                                panels.get(i),
                                "panel[" + i + "]",
                                "screen",
                                null,
                                screenArea,
                                stack,
                                0,
                                panels.get(i)
                                    .isOpening()
                                    || panels.get(i)
                                        .isClosing());
                        } finally {
                            stack.reset();
                        }
                    }
                    if (panels.size() > 0 && widgets.size() >= MAX_WIDGETS) truncate("WIDGET_LIMIT", "screen");
                    overlaps();
                }
            } catch (RuntimeException | LinkageError e) {
                limitations.add(
                    "MUI2 capture incomplete: " + e.getClass()
                        .getSimpleName());
                finding(
                    "WARNING",
                    "CAPTURE_INCOMPLETE",
                    "screen",
                    null,
                    e.getClass()
                        .getSimpleName());
            }
            return map(
                "schema",
                1,
                "screenClass",
                wrapper == null ? UNKNOWN
                    : wrapper.getClass()
                        .getName(),
                "evidence",
                "LIVE_CLIENT_OBJECTS",
                "source",
                "MODULAR_UI_2_ACTIVE_TREE",
                "functionalStatus",
                "MANUAL_SERVER_ASSERTION",
                "state",
                state,
                "widgets",
                widgets,
                "findings",
                findings,
                "truncated",
                truncated,
                "limitations",
                String.join(" ", limitations));
        }

        private void visit(IWidget widget, String path, String parent, Area parentRect, Area clip,
            GuiViewportStack stack, int depth, boolean animating) {
            if (widgets.size() >= MAX_WIDGETS) {
                truncate("WIDGET_LIMIT", path);
                return;
            }
            if (depth > MAX_DEPTH) {
                truncate("DEPTH_LIMIT", path);
                return;
            }
            if (widget == null || !seen.add(widget)) {
                finding(
                    "WARNING",
                    widget == null ? "NULL_CHILD" : "CYCLE_OR_SHARED_CHILD",
                    path,
                    null,
                    "Traversal stopped at this branch.");
                return;
            }
            Map<String, Object> item = map(
                "path",
                path,
                "parent",
                parent,
                "class",
                widget.getClass()
                    .getName(),
                "kind",
                widget instanceof ModularPanel ? "panel"
                    : widget instanceof TextFieldWidget ? "textfield"
                        : widget instanceof TextWidget<?> ? "label" : "widget",
                "visible",
                UNKNOWN,
                "enabled",
                UNKNOWN,
                "label",
                UNKNOWN,
                "textWidth",
                UNKNOWN,
                "x",
                UNKNOWN,
                "y",
                UNKNOWN,
                "w",
                UNKNOWN,
                "h",
                UNKNOWN,
                "interactive",
                widget instanceof Interactable || widget instanceof ModernInteractable,
                "animating",
                animating,
                "clipRect",
                rect(clip));
            widgets.add(item);
            stack.pushMatrix();
            boolean viewportPushed = false;
            try {
                boolean enabled = widget.isEnabled();
                item.put("enabled", enabled);
                item.put("valid", widget.isValid());
                item.put("name", widget.getName());

                IWidget actualParent = widget.getParent();
                item.put(
                    "parentClass",
                    actualParent == null ? UNKNOWN
                        : actualParent.getClass()
                            .getName());
                item.put("parentName", actualParent == null ? UNKNOWN : actualParent.getName());
                Area area = widget.getArea();
                if (area == null) throw new IllegalStateException("Widget area is null");
                item.put("layoutRect", rect(area));
                item.put("relativeRect", map("x", area.rx, "y", area.ry, "w", area.width, "h", area.height));
                item.put("z", area.z());
                widget.transform(stack);
                Area absolute = transformed(area, stack);
                item.putAll(rect(absolute));
                Area visibleRect = intersect(absolute, clip);
                item.put("visibleRect", rect(visibleRect));
                boolean visible = enabled && visibleRect.width > 0 && visibleRect.height > 0;
                item.put("visible", visible);
                if (area.width < 0 || area.height < 0)
                    finding("FAIL", "INVALID_SIZE", path, null, "Negative layout size.");
                else if (enabled && (area.width == 0 || area.height == 0))
                    finding("WARNING", "ZERO_SIZE", path, null, "Zero size may be an intentional layout anchor.");
                if (enabled && !contains(screenArea, absolute)) finding(
                    "WARNING",
                    "OUTSIDE_SCREEN",
                    path,
                    null,
                    "Transformed rectangle extends outside the screen.");
                if (enabled && parentRect != null && !contains(parentRect, absolute)) finding(
                    "WARNING",
                    "OUTSIDE_PARENT",
                    path,
                    parent,
                    "May be intentional overflow or scroll content.");
                if (!enabled) {
                    item.put("pruned", "DISABLED_BRANCH");
                    return;
                }
                text(widget, item);
                if (widget instanceof ISynced<?>) {
                    ISynced<?> synced = (ISynced<?>) widget;
                    item.put("synced", synced.isSynced());
                    if (synced.isSynced()) item.put(
                        "syncHandlerClass",
                        synced.getSyncHandler()
                            .getClass()
                            .getName());
                }
                Area childClip = clip;
                if (widget instanceof AbstractScrollWidget<?, ?>) childClip = intersect(clip, absolute);
                item.put("childrenClipRect", rect(childClip));
                // Scroll viewports are the known rectangular stencil producers in MUI2.
                // Plain parents do not clip overflowing children, so keep traversing those branches.
                if (childClip.width <= 0 || childClip.height <= 0) {
                    item.put("pruned", "CLIPPED_BRANCH");
                    return;
                }
                if (widget instanceof IViewport) {
                    IViewport viewport = (IViewport) widget;
                    stack.pushViewport(viewport, area);
                    viewportPushed = true;
                    viewport.transformChildren(stack);
                }
                List<IWidget> children = widget.getChildren();
                item.put("childCount", children == null ? UNKNOWN : children.size());
                if (children == null) {
                    finding("WARNING", "UNKNOWN_CHILDREN", path, null, "getChildren returned null.");
                    return;
                }
                for (int i = 0; i < children.size(); i++) {
                    if (widgets.size() >= MAX_WIDGETS) {
                        truncate("WIDGET_LIMIT", path);
                        break;
                    }
                    visit(
                        children.get(i),
                        path + "/child[" + i + "]",
                        path,
                        absolute,
                        childClip,
                        stack,
                        depth + 1,
                        animating);
                }
            } catch (RuntimeException | LinkageError e) {
                item.put(
                    "observationError",
                    e.getClass()
                        .getSimpleName());
                finding(
                    "WARNING",
                    "UNKNOWN_WIDGET_DATA",
                    path,
                    null,
                    e.getClass()
                        .getSimpleName());
            } finally {
                if (viewportPushed) stack.popViewport((IViewport) widget);
                stack.popMatrix();
            }
        }

        private void text(IWidget widget, Map<String, Object> item) {
            String label = null;
            float scale = 1;
            if (widget instanceof TextFieldWidget) {
                TextFieldWidget field = (TextFieldWidget) widget;
                label = field.getText();
                scale = field.getScale();
                item.put("focused", field.isFocused());
                item.put("hint", field.getHintText());
            } else if (widget instanceof TextWidget<?>) {
                TextWidget<?> text = (TextWidget<?>) widget;
                scale = text.getScale();
                // Reading the render cache avoids re-evaluating dynamic IKey suppliers or resizing widgets.
                try {
                    Field cached = TextWidget.class.getDeclaredField("lastText");
                    cached.setAccessible(true);
                    Object value = cached.get(text);
                    if (value instanceof String) label = (String) value;
                } catch (ReflectiveOperationException | SecurityException e) {
                    item.put("textObservation", UNKNOWN);
                }
                item.put(
                    "textObservation",
                    label == null ? "UNKNOWN_NOT_RENDERED_OR_UNAVAILABLE" : "LAST_RENDERED_CACHE");
                item.put("alignment", String.valueOf(text.getAlignment()));
            }
            if (label == null) return;
            item.put("label", label.length() > MAX_TEXT ? label.substring(0, MAX_TEXT) : label);
            item.put("textTruncated", label.length() > MAX_TEXT);
            item.put("textScale", scale);
            if (mc != null && mc.fontRenderer != null && label.length() <= MAX_TEXT) {
                int width = 0;
                for (String line : label.split("\n", -1)) width = Math.max(width, mc.fontRenderer.getStringWidth(line));
                item.put("textWidth", width * scale);
            }
        }

        private void overlaps() {
            for (int i = 0; i < widgets.size(); i++) {
                Map<String, Object> first = widgets.get(i);
                if (!Boolean.TRUE.equals(first.get("visible")) || "root".equals(first.get("kind"))) continue;
                for (int j = i + 1; j < widgets.size(); j++) {
                    Map<String, Object> second = widgets.get(j);
                    if (!Boolean.TRUE.equals(second.get("visible")) || !first.get("parent")
                        .equals(second.get("parent"))) continue;
                    Area a = rectFrom(first.get("visibleRect"));
                    Area b = rectFrom(second.get("visibleRect"));
                    if (a != null && b != null && a.intersects(b)) finding(
                        "WARNING",
                        "SIBLING_OVERLAP",
                        (String) first.get("path"),
                        (String) second.get("path"),
                        "Visible sibling rectangles overlap; inspect layering intent.");
                    if (findings.size() >= MAX_FINDINGS) return;
                }
            }
        }

        private void truncate(String code, String path) {
            truncated = true;
            finding("WARNING", code, path, null, "Capture bound reached; omitted data is UNKNOWN.");
        }

        private void finding(String severity, String code, String widget, String other, String message) {
            if (findings.size() >= MAX_FINDINGS) {
                truncated = true;
                return;
            }
            findings.add(map("severity", severity, "code", code, "widget", widget, "other", other, "message", message));
        }
    }

    private static Area transformed(Area area, GuiViewportStack stack) {
        int[] xs = { stack.transformX(0, 0), stack.transformX(area.width, 0), stack.transformX(0, area.height),
            stack.transformX(area.width, area.height) };
        int[] ys = { stack.transformY(0, 0), stack.transformY(area.width, 0), stack.transformY(0, area.height),
            stack.transformY(area.width, area.height) };
        int left = xs[0], right = xs[0], top = ys[0], bottom = ys[0];
        for (int i = 1; i < 4; i++) {
            left = Math.min(left, xs[i]);
            right = Math.max(right, xs[i]);
            top = Math.min(top, ys[i]);
            bottom = Math.max(bottom, ys[i]);
        }
        return new Area(left, top, right - left, bottom - top);
    }

    private static Area intersect(Area a, Area b) {
        int x = Math.max(a.x, b.x), y = Math.max(a.y, b.y);
        int right = Math.min(a.x + a.width, b.x + b.width);
        int bottom = Math.min(a.y + a.height, b.y + b.height);
        return new Area(x, y, Math.max(0, right - x), Math.max(0, bottom - y));
    }

    private static boolean contains(Area outer, Area inner) {
        return inner.x >= outer.x && inner.y >= outer.y
            && (long) inner.x + inner.width <= (long) outer.x + outer.width
            && (long) inner.y + inner.height <= (long) outer.y + outer.height;
    }

    private static Map<String, Object> rect(Area area) {
        return map("x", area.x, "y", area.y, "w", area.width, "h", area.height);
    }

    private static Area rectFrom(Object value) {
        if (!(value instanceof Map<?, ?>)) return null;
        Map<?, ?> rect = (Map<?, ?>) value;
        if (!(rect.get("x") instanceof Number) || !(rect.get("y") instanceof Number)
            || !(rect.get("w") instanceof Number)
            || !(rect.get("h") instanceof Number)) return null;
        return new Area(
            ((Number) rect.get("x")).intValue(),
            ((Number) rect.get("y")).intValue(),
            ((Number) rect.get("w")).intValue(),
            ((Number) rect.get("h")).intValue());
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) map.put((String) entries[i], entries[i + 1]);
        return map;
    }
}
