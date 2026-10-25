package com.miaokatze.gtswn.client.engineering;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.DevicePowerFilter;
import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopAutoBind;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDeviceSources;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDrag;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopGesture;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopLayout;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopPreset;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopPresetCode;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopStore;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopViewport;
import com.miaokatze.gtswn.client.engineering.desktop.DeviceBinding;
import com.miaokatze.gtswn.client.engineering.desktop.ResourceBinding;
import com.miaokatze.gtswn.client.engineering.desktop.ScreenBinding;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetPresentation;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetResources;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;
import com.miaokatze.miaokatzecore.api.ui.component.UiEvent;
import com.miaokatze.miaokatzecore.api.ui.component.UiNode;
import com.miaokatze.miaokatzecore.api.ui.component.UiRect;
import com.miaokatze.miaokatzecore.api.ui.component.UiRenderer;
import com.miaokatze.miaokatzecore.api.ui.component.UiScene;
import com.miaokatze.miaokatzecore.api.ui.component.UiStyle;
import com.miaokatze.miaokatzecore.client.ui.MinecraftUiRenderer;

/** Local business desktop; Core owns component layout, clipping, focus and pointer capture. */
final class FactoryDesktop {

    private static final int BACK = DashboardVisuals.BACK, GRID = 0x503B647D, TEXT = DashboardVisuals.TEXT,
        ACCENT = DashboardVisuals.CYAN;
    private static final UiStyle PANEL = new UiStyle(new UiStyle.Paint(0xFF1D2D38, 0xFF344955, TEXT));
    private static final UiStyle BUTTON = new UiStyle(
        new UiStyle.Paint(0xFF2B414E, 0xFF3C5563, TEXT),
        new UiStyle.Paint(0xFF3B5866, ACCENT, TEXT),
        new UiStyle.Paint(0xFF1B353F, ACCENT, TEXT),
        new UiStyle.Paint(0xFF376359, ACCENT, TEXT),
        new UiStyle.Paint(0xFF26343C, 0xFF344955, 0xFF9AAEB8));
    private static DesktopStore store;
    private static DesktopLayout layout;
    private static DesktopPreset pendingPreset;
    private static DesktopLayout pendingImport;
    private static String presetDraft = "";
    private static boolean importingPreset;
    private static DesktopViewport view;
    private static String profile = "", selected = "";
    private static UiScene scene;
    private static UiNode zoomLabel;
    private static UiNode statusLabel;
    private static String notice = "";
    private static Rect area, canvas;
    private static FontRenderer font;
    private static int mouseX, mouseY, screenWidth, screenHeight;
    private static final List<DeviceBinding> visibleDevices = new ArrayList<>();
    private static final DesktopDrag drag = new DesktopDrag();
    private static final DesktopGesture gesture = new DesktopGesture();
    private static String pressedWidget = "";
    private static DesktopDrag.Edge pressedEdge;
    private static double pressLocalX, pressLocalY;
    private static final DesktopEditorScroll editorScroll = new DesktopEditorScroll();
    private static boolean edit, libraryOpen, propertiesOpen, initialView, canvasFocus, panning, dirty = true;
    private static boolean bindingOpen, screenBindingOpen, resourceAdding, choosingDeviceSource;
    private static int resourceCategory = 1, screenCategory;
    private static String resourceSignature = "";
    private static UUID pickerSource;
    private static String sourceSignature = "";
    private static List<DesktopDeviceSources.Source> pickerSources = Collections.emptyList();
    private static List<Entry> pickerEntries = Collections.emptyList();
    private static String pickerReason = "";
    private static String screenSignature = "";
    private static List<ScreenBinding> pickerScreens = Collections.emptyList();
    private static final int BINDING_PAGE_SIZE = 16;
    private static int bindingPage;
    private static final FactoryTextSearch deviceSearch = new FactoryTextSearch(),
        resourceSearch = new FactoryTextSearch(), screenSearch = new FactoryTextSearch();
    private static FactoryMachineFilter pickerFilter = FactoryMachineFilter.ALL;
    private static int pickerPower = DevicePowerFilter.ALL;
    private static long nextDefaultBindingCheck;
    private static double lastX, lastY, panBeforeX, panBeforeY;

    private static void ensure() {
        Minecraft mc = Minecraft.getMinecraft();
        String next = DesktopStore.profile(
            EngineeringModeController.preferenceProfile(),
            mc.thePlayer == null ? ""
                : mc.thePlayer.getUniqueID()
                    .toString());
        if (store == null) store = new DesktopStore(new File(mc.mcDataDir, "config/gtswn/desktops"));
        if (layout != null && next.equals(profile)) return;
        blur();
        if (scene != null) scene.close();
        scene = null;
        flush();
        profile = next;
        layout = store.load(profile);
        view = store.loadView(profile);
        initialView = !store.hasView(profile);
        selected = "";
        clearPresetImport();
        pendingPreset = null;
        bindingOpen = screenBindingOpen = resourceAdding = choosingDeviceSource = false;
        pickerSource = null;
        deviceSearch.value = resourceSearch.value = screenSearch.value = "";
        pickerFilter = FactoryMachineFilter.ALL;
        pickerPower = DevicePowerFilter.ALL;
        nextDefaultBindingCheck = 0;
        edit = canvasFocus = panning = false;
        drag.cancel();
        dirty = true;
    }

    static void tick(boolean visible) {
        ensure();
        boolean showing = visible && EngineeringModeController.dashboardHostVisible();
        if (!showing || !EngineeringModeController.dashboardInputAllowed()) {
            FactoryDashboard.blur();
        }
        if (showing) {
            long now = System.nanoTime();
            if (nextDefaultBindingCheck == 0 || now - nextDefaultBindingCheck >= 0) {
                nextDefaultBindingCheck = now + 1000000000L;
                if (DesktopAutoBind.initializeAvailable(layout)) changed(true);
            }
            refreshBindingSources();
            DesktopDeviceSources.setSelectionSource(devicePickerActive() ? pickerSource : null);
            DesktopDeviceSources.setVisibleBindings(visibleDevices);
            DesktopDeviceSources.tick();
            if (devicePickerActive() && pickerSource != null) {
                List<Entry> entries = DesktopDeviceSources.choices(pickerSource);
                String reason = DesktopDeviceSources.selectionReason(pickerSource);
                if (entries != pickerEntries || !reason.equals(pickerReason)) {
                    pickerEntries = entries;
                    pickerReason = reason;
                    dirty = true;
                }
            }
            if (edit && (screenBindingOpen && propertiesOpen || resourceAdding && libraryOpen)) {
                List<ScreenBinding> screens = WidgetResources.screenChoices();
                StringBuilder signature = new StringBuilder();
                for (ScreenBinding binding : screens) signature.append(binding.source)
                    .append(binding.screen)
                    .append(WidgetResources.screenLabel(binding))
                    .append(DesktopSourcePreview.screenSummary(binding))
                    .append('\n');
                if (!screenSignature.equals(signature.toString())) {
                    screenSignature = signature.toString();
                    pickerScreens = screens;
                    dirty = true;
                }
            }
            WidgetSpec current = layout.find(selected);
            if (edit && (resourceAdding && libraryOpen
                || bindingOpen && propertiesOpen && current != null && current.kind == WidgetKind.RESOURCES)) {
                StringBuilder signature = new StringBuilder();
                for (ResourceBinding binding : WidgetResources.choices()) signature.append(binding.source)
                    .append(binding.screen)
                    .append(binding.key)
                    .append(binding.name)
                    .append('\n');
                if (!resourceSignature.equals(signature.toString())) {
                    resourceSignature = signature.toString();
                    dirty = true;
                }
            }
        } else {
            DesktopDeviceSources.release();
            visibleDevices.clear();
        }
        store.saveIfDue(System.nanoTime());
    }

    private static void refreshBindingSources() {
        if (!devicePickerActive()) return;
        List<DesktopDeviceSources.Source> sources = DesktopDeviceSources.sources();
        StringBuilder signature = new StringBuilder();
        boolean found = false;
        for (DesktopDeviceSources.Source source : sources) {
            signature.append(source.id)
                .append(':')
                .append(source.label)
                .append('\n');
            found |= source.id.equals(pickerSource);
        }
        if (!signature.toString()
            .equals(sourceSignature)) {
            sourceSignature = signature.toString();
            pickerSources = sources;
            dirty = true;
        }
        if (pickerSource != null && !found) {
            pickerSource = null;
            pickerEntries = Collections.emptyList();
            dirty = true;
        }
        // The equipped terminal is first in this existing source list. Avoid an extra source click.
        if (pickerSource == null && !choosingDeviceSource && !pickerSources.isEmpty()) {
            pickerSource = pickerSources.get(0).id;
            pickerEntries = Collections.emptyList();
            pickerReason = "waiting";
            dirty = true;
        }
    }

    private static boolean devicePickerActive() {
        WidgetSpec current = layout == null ? null : layout.find(selected);
        return bindingOpen && edit && propertiesOpen && current != null && current.kind.supportsDeviceBinding();
    }

    static void flush() {
        if (gesture.active() || drag.active() || panning) return;
        if (store != null && layout != null) {
            store.put(profile, layout);
            store.putView(profile, view);
            store.flush();
        }
    }

    private static void changed(boolean structure) {
        if (gesture.active() || drag.active() || panning) return;
        store.put(profile, layout);
        store.putView(profile, view);
        dirty |= structure;
    }

    static boolean mount(UUID source, Entry entry) {
        DeviceBinding binding = DesktopDeviceSources.binding(source, entry);
        if (binding == null) return false;
        if (!EngineeringModeController.dashboardHostVisible()) EngineeringModeController.open();
        if (!EngineeringModeController.dashboardHostVisible()) return false;
        ensure();
        WidgetSpec widget = layout.addDevice(binding);
        if (widget == null) return false;
        selected = widget.id;
        view.panX = widget.x() - 18 / view.scale;
        view.panY = widget.y() - 18 / view.scale;
        edit = propertiesOpen = true;
        libraryOpen = false;
        changed(true);
        WorkbenchHud.openDashboard();
        return true;
    }

    static boolean mountResource(ResourceBinding binding) {
        if (binding == null) return false;
        ensure();
        WidgetSpec widget = layout.addResource(binding);
        if (widget == null) return false;
        selected = widget.id;
        DesktopCardGeometry.focusAtOne(view, widget, canvas);
        changed(true);
        return true;
    }

    /** Existing click selection gating calls this only when the user activates this exact empty component. */
    static void requestBinding(String id) {
        WidgetSpec widget = layout == null ? null : layout.find(id);
        if (widget == null || !widget.kind.supportsDeviceBinding() && widget.kind != WidgetKind.RESOURCES) return;
        selected = widget.id;
        edit = propertiesOpen = true;
        libraryOpen = false;
        bindingOpen = widget.kind.supportsDeviceBinding();
        screenBindingOpen = widget.kind == WidgetKind.RESOURCES;
        resourceAdding = false;
        bindingPage = 0;
        pickerSource = widget.device == null ? null : widget.device.source;
        choosingDeviceSource = false;
        pickerEntries = Collections.emptyList();
        if (bindingOpen) {
            pickerSources = DesktopDeviceSources.sources();
            sourceSignature = "";
            refreshBindingSources();
        }
        if (screenBindingOpen) {
            pickerScreens = WidgetResources.screenChoices();
            screenSignature = "";
            ScreenBinding automatic = WidgetResources.defaultScreen();
            if (automatic != null && widget.screen == null) {
                screenBindingOpen = false;
                bindingOpen = true;
            }
        }
        if (view.scale < DesktopCardGeometry.BORDER_LOD_SCALE) DesktopCardGeometry.focusAtOne(view, widget, canvas);
        notice = "";
        dirty = true;
    }

    static String label(WidgetSpec widget) {
        if (!widget.title.isEmpty()) return widget.title;
        if (widget.kind == WidgetKind.WIRELESS) return tr("metric." + widget.metricField);
        DesktopDeviceSources.Reading reading = widget.device == null ? null : DesktopDeviceSources.read(widget.device);
        Entry current = reading == null ? null : reading.entry;
        return DesktopCardLabel.title(
            widget,
            kindLabel(widget.kind),
            current == null ? null : current.displayName,
            current == null ? null : current.name);
    }

    static String kindLabel(WidgetKind kind) {
        return tr(
            "kind." + kind.name()
                .toLowerCase(Locale.ROOT));
    }

    static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.factory.desktop." + key);
    }

    static List<String> fields(WidgetKind kind) {
        return WidgetSpec.availableFields(kind);
    }

    static boolean mouse(int x, int y, int button, boolean pressed, int wheel) {
        if (scene == null) return false;
        if (pressed && button >= 0 && !canvas.contains(x, y)) canvasFocus = false;
        if (wheel != 0 && scene.captured() != null) return true;
        if (button >= 0 && pressed) FactoryDashboard.blurResourceSearch(-1, -1);
        UiNode library = scene.find("library"), properties = scene.find("properties");
        boolean tools = library != null && library.bounds()
            .contains(x, y) || properties != null
                && properties.bounds()
                    .contains(x, y);
        boolean used;
        if (wheel != 0) used = scene.scroll(x, y, !tools && canvas.contains(x, y) ? wheel : -Math.signum(wheel) * 24);
        else if (button >= 0) used = pressed ? scene.pointerDown(x, y, button) : scene.pointerUp(x, y, button);
        else used = scene.pointerMove(x, y) || pointerCaptured();
        return used || area.contains(x, y);
    }

    static boolean key(int code, char character, boolean pressed) {
        if (scene == null || code == Keyboard.KEY_ESCAPE) return false;
        if ((gesture.active() || drag.active() || panning) && (code == Keyboard.KEY_DELETE || code == Keyboard.KEY_TAB))
            return true;
        boolean control = pressed
            && (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
        if (pressed && control && typing()) {
            if (code == Keyboard.KEY_V) {
                scene.insertText(GuiScreen.getClipboardString());
                return true;
            }
            if (code == Keyboard.KEY_C || code == Keyboard.KEY_X) {
                String copied = scene.selectedText();
                if (!copied.isEmpty()) GuiScreen.setClipboardString(copied);
                if (code == Keyboard.KEY_X) scene.insertText("");
                return true;
            }
        }
        if (pressed && scene.keyTyped(
            character,
            code,
            Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT),
            control)) return true;
        if (edit && canvasFocus && scene.focused() == null && code == Keyboard.KEY_DELETE) {
            if (pressed && !selected.isEmpty()) {
                layout.remove(selected);
                selected = "";
                changed(true);
            }
            return true;
        }
        return typing();
    }

    static boolean escape() {
        if (scene != null && (scene.captured() != null || scene.focused() != null)) {
            scene.blur();
            return true;
        }
        if (importingPreset || pendingPreset != null) {
            clearPresetImport();
            pendingPreset = null;
            dirty = true;
            return true;
        }
        if (libraryOpen || propertiesOpen) {
            libraryOpen = propertiesOpen = false;
            dirty = true;
            return true;
        }
        if (edit) {
            edit = canvasFocus = false;
            changed(true);
            return true;
        }
        return false;
    }

    static void blur() {
        if (scene != null) scene.blur();
        cancelGesture();
        canvasFocus = false;
    }

    static void focusOutside(int x, int y) {
        if (area != null && !area.contains(x, y)) blur();
    }

    static void suspend() {
        blur();
        flush();
        DesktopDeviceSources.release();
        visibleDevices.clear();
        FactoryChrome.blur();
    }

    static boolean typing() {
        return scene != null && scene.focused() != null && scene.focused().kind == UiNode.Kind.TEXT_INPUT;
    }

    static int pointerX() {
        return mouseX;
    }

    static int pointerY() {
        return mouseY;
    }

    static boolean pointerCaptured() {
        return scene != null && scene.captured() != null;
    }

    private static WidgetSpec hit(double x, double y) {
        CanvasTransform transform = view.transform(canvas.x, canvas.y);
        double sx = transform.toScreenX(x), sy = transform.toScreenY(y);
        WidgetSpec current = layout.find(selected);
        if (current != null && DesktopCardGeometry
            .hit(bounds(current, transform), sx, sy, DesktopCardGeometry.showResizeHandles(view.scale))) return current;
        for (WidgetSpec widget : layout.widgets())
            if (DesktopCardGeometry.hit(bounds(widget, transform), sx, sy, false)) return widget;
        return null;
    }

    private static void down(UiEvent e) {
        FactoryDashboard.blurResourceSearch((int) e.x, (int) e.y);
        canvasFocus = true;
        double x = view.toLocalX(e.x, canvas.x), y = view.toLocalY(e.y, canvas.y);
        WidgetSpec target = hit(x, y);
        if (e.button == 2 || e.button == 0 && target == null) {
            if (target == null) {
                selected = "";
                dirty = true;
            }
            panning = true;
            lastX = e.x;
            lastY = e.y;
            panBeforeX = view.panX;
            panBeforeY = view.panY;
            e.capturePointer();
            e.consume();
            return;
        }
        if (e.button == 0 && target != null) {
            boolean was = selected.equals(target.id);
            if (!was) bindingOpen = screenBindingOpen = false;
            if (!was) bindingPage = 0;
            selected = target.id;
            if (edit) {
                propertiesOpen = true;
                libraryOpen = false;
            }
            dirty = true;
            Rect r = bounds(target, view.transform(canvas.x, canvas.y));
            pressedWidget = target.id;
            pressedEdge = was && DesktopCardGeometry.showResizeHandles(view.scale) ? edge(r, e.x, e.y)
                : DesktopDrag.Edge.MOVE;
            pressLocalX = x;
            pressLocalY = y;
            boolean direct = was && (pressedEdge != DesktopDrag.Edge.MOVE
                || edit && DesktopCardGeometry.titleHandle(r, view.scale, e.x, e.y));
            gesture.begin(was, direct, e.x, e.y, System.nanoTime());
            e.capturePointer();
            e.consume();
            return;
        }
        e.consume();
    }

    private static void move(UiEvent e) {
        if (panning) {
            view.pan(e.x - lastX, e.y - lastY);
            lastX = e.x;
            lastY = e.y;
        }
        advanceGesture(e.x, e.y);
        if (panning || gesture.active() || drag.active()) e.consume();
    }

    private static void advanceGesture(double x, double y) {
        if (gesture.update(x, y, System.nanoTime())) {
            drag.begin(layout, pressedWidget, pressedEdge, pressLocalX, pressLocalY);
        }
        if (drag.active() && gesture.moveAllowed(x, y))
            drag.update(view.toLocalX(x, canvas.x), view.toLocalY(y, canvas.y));
    }

    private static void up(UiEvent e) {
        move(e);
        if (gesture.active()) {
            WidgetSpec target = hit(view.toLocalX(e.x, canvas.x), view.toLocalY(e.y, canvas.y));
            DesktopGesture.Release result = gesture.release(
                e.x,
                e.y,
                System.nanoTime(),
                target != null && target.id.equals(pressedWidget) && canvas.contains((int) e.x, (int) e.y));
            drag.finish();
            pressedWidget = "";
            changed(true);
            if (result == DesktopGesture.Release.ACTIVATE && !edit && pressedEdge == DesktopDrag.Edge.MOVE) {
                if (target != null && (target.kind.supportsDeviceBinding() && target.device == null
                    || target.kind == WidgetKind.RESOURCES && target.screen == null
                        && WidgetResources.defaultScreen() == null))
                    requestBinding(target.id);
                else if (view.scale < DesktopCardGeometry.BORDER_LOD_SCALE && target != null) {
                    DesktopCardGeometry.focusAtOne(view, target, canvas);
                    changed(false);
                } else FactoryDashboard.widgetClick((int) e.x, (int) e.y);
            }
            e.consume();
        } else if (panning || drag.active()) {
            panning = false;
            drag.finish();
            changed(true);
            e.consume();
        }
    }

    private static void cancelGesture() {
        if (!gesture.active() && !drag.active() && !panning) return;
        gesture.cancel();
        pressedWidget = "";
        drag.cancel();
        if (panning) {
            view.panX = panBeforeX;
            view.panY = panBeforeY;
            panning = false;
        }
        dirty = true;
    }

    private static DesktopDrag.Edge edge(Rect r, double x, double y) {
        return DesktopCardGeometry.edge(r, x, y);
    }

    static void render(FontRenderer f, Rect r, int mx, int my, int width, int height) {
        ensure();
        font = f;
        mouseX = mx;
        mouseY = my;
        screenWidth = width;
        screenHeight = height;
        boolean resized = area == null || area.x != r.x || area.y != r.y || area.w != r.w || area.h != r.h;
        if (resized) blur();
        advanceGesture(mx, my);
        area = r;
        if (scene == null || (dirty || resized) && scene.captured() == null) build();
        zoomLabel.text(Math.round(view.scale * 100) + "%");
        statusLabel.text(notice.isEmpty() ? tr(edit ? "hint.edit" : "hint.browse") : notice);
        CanvasTransform t = view.transform(canvas.x, canvas.y);
        visibleDevices.clear();
        for (WidgetSpec widget : layout.widgets()) {
            UiNode node = scene.find("card-" + widget.id);
            if (node == null) continue;
            Rect b = bounds(widget, t);
            boolean visible = intersects(b, canvas);
            node.bounds(b.x - canvas.x, b.y - canvas.y, b.w, b.h)
                .visible(visible);
            if (visible && widget.device != null) visibleDevices.add(widget.device);
            scene.find("selection-" + widget.id)
                .size(b.w, b.h)
                .visible(selected.equals(widget.id));
        }
        scene.layout(width, height);
        Minecraft mc = Minecraft.getMinecraft();
        scene.render(new MinecraftUiRenderer(f, width, height, mc.displayWidth, mc.displayHeight));
        UiNode hovered = scene.hitTest(mx, my);
        if (!edit && !panning && !gesture.active() && hovered != null && hovered.id.startsWith("card-"))
            FactoryDashboard.drawHover(f, mx, my, width, height);
    }

    private static void build() {
        String focusedInput = typing() ? scene.focused().id : "";
        String focusedDraft = typing() ? scene.focused()
            .text() : "";
        editorScroll.capture(scene, selected);
        if (scene != null) scene.close();
        UiNode root = UiNode.panel("desktop")
            .bounds(0, 0, screenWidth, screenHeight)
            .pointerTransparent(true)
            .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)));
        UiNode shell = UiNode.panel("desktop-shell")
            .bounds(area.x, area.y, area.w, area.h)
            .style(new UiStyle(new UiStyle.Paint(BACK, 0xFF344955, TEXT)));
        root.add(shell);
        canvas = new Rect(area.x, area.y + 34, area.w, Math.max(1, area.h - 52));
        if (initialView) {
            view.reset(layout, canvas.w, canvas.h);
            initialView = false;
        }
        UiNode canvasNode = UiNode.panel("canvas")
            .bounds(0, 34, canvas.w, canvas.h)
            .overflowClip(true)
            .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)))
            .onDraw(FactoryDesktop::drawGrid)
            .on(UiEvent.Type.DOWN, FactoryDesktop::down)
            .on(UiEvent.Type.MOVE, FactoryDesktop::move)
            .on(UiEvent.Type.UP, FactoryDesktop::up)
            .on(UiEvent.Type.CANCEL, e -> cancelGesture())
            .on(UiEvent.Type.SCROLL, e -> {
                if (!drag.active() && !panning) {
                    view.zoomAt(e.wheel > 0 ? 1.12 : 1 / 1.12, e.x, e.y, canvas.x, canvas.y);
                    changed(false);
                }
                e.consume();
            });
        for (WidgetSpec widget : layout.widgets()) {
            UiNode card = UiNode.panel("card-" + widget.id)
                .overflowClip(true)
                .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)))
                .onDraw((node, renderer) -> drawWidget(layout.find(widget.id), node, renderer));
            card.add(
                UiNode.panel("selection-" + widget.id)
                    .bounds(0, 0, widget.width(), widget.height())
                    .pointerTransparent(true)
                    .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)))
                    .onDraw((node, renderer) -> {
                        UiRect b = node.bounds();
                        selection(renderer, new Rect((int) b.x, (int) b.y, (int) b.width, (int) b.height));
                    }));
            canvasNode.add(card);
        }
        shell.add(canvasNode);
        // Tools float above the full-size canvas; editing never squeezes the dashboard.
        shell.add(
            UiNode.label("desktop-title", tr("title"))
                .bounds(10, 12, Math.max(1, area.w - 300), 12));
        int controlsX = Math.max(4, area.w - (edit ? 342 : 276));
        shell.add(
            button(
                "back-world",
                tr(EngineeringModeController.isActive() ? "back.world" : "back.view"),
                controlsX,
                6,
                54,
                22,
                () -> WorkbenchHud.switchTask(1)));
        shell.add(button("edit", tr(edit ? "done" : "edit"), controlsX + 58, 6, 64, 22, () -> {
            FactoryDashboard.blur();
            edit = !edit;
            libraryOpen = propertiesOpen = canvasFocus = false;
            changed(true);
        }));
        int toolsX = controlsX + 126;
        if (edit) {
            shell.add(button("library-toggle", tr("library"), toolsX, 6, 62, 22, () -> {
                FactoryDashboard.blur();
                libraryOpen = !libraryOpen;
                propertiesOpen = false;
                dirty = true;
            }));
            toolsX += 66;
        }
        zoomLabel = button("zoom", "60%", toolsX, 6, 48, 22, () -> {
            WidgetSpec target = layout.find(selected);
            nextDefaultBindingCheck = 0;
            view.reset(layout, canvas.w, canvas.h);
            if (target != null) {
                view.panX = target.x() + (target.width() - canvas.w / view.scale) / 2;
                view.panY = target.y() + (target.height() - canvas.h / view.scale) / 2;
            }
            changed(false);
        });
        shell.add(zoomLabel);
        shell.add(button("fit", tr("fit"), toolsX + 52, 6, 48, 22, () -> {
            view.fit(layout, canvas.w, canvas.h);
            changed(false);
        }));
        if (edit && libraryOpen) shell.add(library(Math.min(resourceAdding ? 290 : 230, area.w - 12)));
        if (edit && propertiesOpen && layout.find(selected) != null) {
            int width = Math.min(230, Math.max(170, area.w / 3));
            shell.add(properties(Math.max(0, area.w - width - 6), width));
        }
        statusLabel = UiNode.label("desktop-hint", tr(edit ? "hint.edit" : "hint.browse"))
            .bounds(12, area.h - 15, area.w - 24, 12);
        shell.add(statusLabel);
        scene = new UiScene(root);
        scene.layout(screenWidth, screenHeight);
        editorScroll.restore(scene);
        if (!focusedInput.isEmpty()) {
            UiNode input = scene.find(focusedInput);
            if (input != null) {
                input.text(focusedDraft);
                scene.focus(input);
            }
        }
        dirty = false;
    }

    private static UiNode button(String id, String label, double x, double y, double w, double h, Runnable action) {
        return UiNode.button(id, label, action)
            .bounds(x, y, w, h)
            .style(BUTTON);
    }

    private static UiNode library(int width) {
        UiNode panel = UiNode.panel("library")
            .bounds(6, 36, width, Math.max(1, canvas.h - 4))
            .style(PANEL)
            .overflowClip(true);
        panel.add(button("close-library", "x", width - 26, 5, 20, 20, () -> {
            libraryOpen = false;
            resourceAdding = false;
            pendingPreset = null;
            clearPresetImport();
            dirty = true;
        }));
        panel.add(
            UiNode.label("library-title", tr("library"))
                .bounds(10, 12, width - 40, 12));
        UiNode list = UiNode.panel("library-list")
            .bounds(6, 34, width - 12, Math.max(1, canvas.h - 42))
            .layout(UiNode.Layout.COLUMN)
            .gap(6)
            .scrollable(true)
            .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)));
        list.add(
            button("add-resource", tr(resourceAdding ? "resource.back" : "resource.add"), 0, 0, width - 12, 28, () -> {
                resourceAdding = !resourceAdding;
                bindingPage = 0;
                resourceSignature = "";
                UiNode currentList = scene.find("library-list");
                if (currentList != null) currentList.scrollTo(0);
                dirty = true;
            }));
        if (resourceAdding) {
            list.add(
                UiNode.label("resource-source-help", tr("resource.source.help"))
                    .size(width - 12, 25));
            addResourceChoices(list, width - 12, null, null);
            panel.add(list);
            return panel;
        }
        list.add(
            UiNode.label("machine-add-help", tr("binding.machine.help"))
                .size(width - 12, 25));
        list.add(
            UiNode.label("presets-title", tr("presets"))
                .size(width - 12, 14));
        for (DesktopPreset preset : DesktopPreset.values()) {
            String id = "preset-" + preset.name();
            if (pendingPreset == preset) {
                list.add(presetActions(id, width - 12, preset::createLayout));
            } else {
                list.add(
                    UiNode.button(
                        id,
                        tr(
                            "preset." + preset.name()
                                .toLowerCase(Locale.ROOT)),
                        () -> {
                            pendingPreset = preset;
                            pendingImport = null;
                            dirty = true;
                        })
                        .size(width - 12, 23)
                        .style(BUTTON));
            }
        }
        addPresetSharing(list, width - 12);
        list.add(
            UiNode.label("add-title", tr("library"))
                .size(width - 12, 14));
        for (WidgetKind kind : WidgetKind.values())
            list.add(UiNode.button("add-" + kind, "+ " + kindLabel(kind), () -> {
                WidgetSpec added = layout.add(kind);
                if (added == null) return;
                selected = added.id;
                bindingOpen = screenBindingOpen = false;
                choosingDeviceSource = false;
                propertiesOpen = true;
                libraryOpen = false;
                view.panX = added.x() - 18 / view.scale;
                view.panY = added.y() - 18 / view.scale;
                changed(true);
            })
                .size(width - 12, 25)
                .style(BUTTON));
        panel.add(list);
        return panel;
    }

    private static UiNode presetActions(String id, int width, Supplier<DesktopLayout> template) {
        UiNode row = UiNode.panel(id + "-actions")
            .size(width, 23);
        int half = (width - 4) / 2;
        row.add(
            button(id + "-replace", tr("preset.overwrite"), 0, 0, half, 23, () -> applyPreset(template.get(), false)));
        row.add(
            button(
                id + "-append",
                tr("preset.append"),
                half + 4,
                0,
                width - half - 4,
                23,
                () -> applyPreset(template.get(), true)));
        return row;
    }

    private static void applyPreset(DesktopLayout template, boolean append) {
        if (template == null) {
            notice = tr("preset.invalid");
            return;
        }
        DesktopLayout prepared = DesktopAutoBind.initialize(template);
        if (append) {
            List<WidgetSpec> added = layout.appendPreset(prepared);
            if (added.isEmpty()) {
                notice = tr("no.space");
                return;
            }
            WidgetSpec first = added.get(0);
            selected = first.id;
            view.panX = first.x() - 18 / view.scale;
            view.panY = first.y() - 18 / view.scale;
        } else {
            layout = prepared;
            selected = "";
            view.reset(layout, canvas.w, canvas.h);
        }
        DesktopAutoBind.initializeAvailable(layout);
        nextDefaultBindingCheck = System.nanoTime() + 1000000000L;
        pendingPreset = null;
        clearPresetImport();
        libraryOpen = propertiesOpen = false;
        bindingOpen = screenBindingOpen = resourceAdding = choosingDeviceSource = false;
        notice = tr(append ? "preset.appended" : "preset.applied");
        changed(true);
    }

    private static void clearPresetImport() {
        importingPreset = false;
        pendingImport = null;
        presetDraft = "";
    }

    private static void addPresetSharing(UiNode list, int width) {
        list.add(
            UiNode.label("preset-share-title", tr("preset.share"))
                .size(width, 14));
        list.add(button("preset-export", tr("preset.export"), 0, 0, width, 23, () -> {
            try {
                GuiScreen.setClipboardString(DesktopPresetCode.export(layout));
                notice = tr("preset.copied");
            } catch (IllegalArgumentException invalid) {
                notice = tr("preset.export.failed");
            }
        }));
        if (!importingPreset) {
            list.add(button("preset-import", tr("preset.import"), 0, 0, width, 23, () -> {
                importingPreset = true;
                pendingPreset = null;
                dirty = true;
            }));
            return;
        }
        list.add(
            UiNode.label("preset-code-label", tr("preset.code"))
                .size(width, 14));
        list.add(UiNode.textInput("preset-code", presetDraft, value -> {
            presetDraft = value;
            if (pendingImport != null) {
                pendingImport = null;
                dirty = true;
            }
        })
            .size(width, 23)
            .maxLength(DesktopPresetCode.MAX_CODE_LENGTH)
            .style(BUTTON));
        if (pendingImport == null) {
            list.add(
                button(
                    "preset-validate",
                    tr("preset.validate"),
                    0,
                    0,
                    width,
                    23,
                    FactoryDesktop::validatePresetImport));
        } else {
            list.add(presetActions("preset-import", width, () -> pendingImport));
        }
        list.add(button("preset-import-cancel", tr("preset.cancel"), 0, 0, width, 23, () -> {
            clearPresetImport();
            notice = "";
            dirty = true;
        }));
    }

    private static void validatePresetImport() {
        try {
            pendingImport = DesktopPresetCode.decode(presetDraft);
            pendingPreset = null;
            notice = tr("preset.valid");
        } catch (IllegalArgumentException invalid) {
            pendingImport = null;
            notice = tr("preset.invalid");
        }
        dirty = true;
    }

    private static void addEffectsProperties(UiNode body, WidgetSpec widget, int width) {
        body.add(
            UiNode.label("effects-title", tr("effects"))
                .size(width, 14));
        body.add(
            button(
                "effects-style",
                tr("effects.style") + ": "
                    + tr(
                        "effects.style." + widget.effects.style.name()
                            .toLowerCase(Locale.ROOT)),
                0,
                0,
                width,
                23,
                () -> {
                    WidgetSpec current = layout.find(widget.id);
                    if (current == null) return;
                    WidgetEffects.Style[] styles = WidgetEffects.Style.values();
                    style(
                        current,
                        current.effects.withStyle(styles[(current.effects.style.ordinal() + 1) % styles.length]));
                }));
        body.add(
            button(
                "effects-animation",
                tr("effects.animation") + ": " + tr(widget.effects.animated ? "effects.on" : "effects.off"),
                0,
                0,
                width,
                23,
                () -> {
                    WidgetSpec current = layout.find(widget.id);
                    if (current != null) style(current, current.effects.withAnimated(!current.effects.animated));
                }));
        body.add(
            UiNode.label("effects-speed-label", tr("effects.speed"))
                .size(width, 13));
        UiNode speed = UiNode.textInput("effects-speed", Double.toString(widget.effects.speed), value -> {})
            .size(width, 23)
            .maxLength(16)
            .style(BUTTON);
        body.add(speed);
        body.add(
            UiNode.label("effects-intensity-label", tr("effects.intensity"))
                .size(width, 13));
        UiNode intensity = UiNode.textInput("effects-intensity", Double.toString(widget.effects.intensity), value -> {})
            .size(width, 23)
            .maxLength(16)
            .style(BUTTON);
        body.add(intensity);
        body.add(
            UiNode.label("effects-accent-label", tr("effects.accent"))
                .size(width, 13));
        UiNode accent = UiNode
            .textInput(
                "effects-accent",
                String.format(Locale.ROOT, "%06X", widget.effects.accentColor & 0xFFFFFF),
                value -> {})
            .size(width, 23)
            .maxLength(7)
            .style(BUTTON);
        body.add(accent);
        body.add(button("effects-apply", tr("effects.apply"), 0, 0, width, 23, () -> {
            WidgetSpec current = layout.find(widget.id);
            if (current == null) return;
            try {
                String color = accent.text()
                    .trim();
                if (color.startsWith("#")) color = color.substring(1);
                if (!color.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException();
                style(
                    current,
                    new WidgetEffects(
                        current.effects.style,
                        current.effects.animated,
                        Integer.parseInt(color, 16),
                        Double.parseDouble(
                            speed.text()
                                .trim()),
                        Double.parseDouble(
                            intensity.text()
                                .trim())));
            } catch (IllegalArgumentException invalid) {
                notice = tr("effects.invalid");
            }
        }));
    }

    private static void style(WidgetSpec widget, WidgetEffects effects) {
        if (layout.replace(widget.styled(effects))) {
            notice = "";
            changed(true);
        }
    }

    private static UiNode properties(int x, int width) {
        UiNode panel = UiNode.panel("properties")
            .bounds(x, 36, width, Math.max(1, canvas.h - 4))
            .style(PANEL)
            .overflowClip(true);
        panel.add(button("close-properties", "x", width - 26, 5, 20, 20, () -> {
            propertiesOpen = false;
            dirty = true;
        }));
        panel.add(
            UiNode.label("properties-title", tr("properties"))
                .bounds(10, 12, width - 40, 12));
        WidgetSpec widget = layout.find(selected);
        if (widget == null) {
            panel.add(
                UiNode.label("select-hint", tr("select"))
                    .bounds(10, 40, width - 20, 18));
            return panel;
        }
        UiNode body = UiNode.panel("properties-body")
            .bounds(8, 34, width - 16, Math.max(1, canvas.h - 72))
            .layout(UiNode.Layout.COLUMN)
            .gap(5)
            .scrollable(true)
            .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)));
        body.add(
            UiNode.label("geometry", kindLabel(widget.kind) + " · " + widget.width() + " × " + widget.height())
                .size(width - 16, 14));
        if (widget.kind.supportsDeviceBinding()) addDeviceBinding(body, widget, width - 16);
        if (widget.kind == WidgetKind.RESOURCES) {
            addScreenBinding(body, widget, width - 16);
            addResourceBinding(body, widget, width - 16);
        }
        if (widget.kind == WidgetKind.WIRELESS) addRateProperties(body, widget, width - 16);
        body.add(UiNode.textInput("custom-title", widget.title, value -> {
            WidgetSpec current = layout.find(selected);
            if (current != null && layout.replace(current.titled(value))) changed(false);
        })
            .size(width - 16, 23)
            .maxLength(64)
            .style(BUTTON));
        body.add(
            UiNode.label("border-label", tr("border.color"))
                .size(width - 16, 13));
        UiNode borderInput = UiNode
            .textInput("border-color", String.format(Locale.ROOT, "%06X", widget.borderColor & 0xFFFFFF), value -> {})
            .size(width - 16, 23)
            .maxLength(7)
            .style(BUTTON);
        body.add(borderInput);
        body.add(button("apply-border", tr("border.apply"), 0, 0, width - 16, 22, () -> {
            String value = borderInput.text()
                .trim();
            if (value.startsWith("#")) value = value.substring(1);
            if (!value.matches("[0-9a-fA-F]{6}")) {
                notice = tr("border.invalid");
                return;
            }
            WidgetSpec current = layout.find(widget.id);
            if (current != null && layout.replace(current.bordered(Integer.parseInt(value, 16)))) {
                notice = "";
                changed(true);
            }
        }));
        addEffectsProperties(body, widget, width - 16);
        body.add(
            button(
                "presentation",
                tr("presentation") + ": "
                    + tr(
                        "presentation." + widget.presentation.name()
                            .toLowerCase(Locale.ROOT)),
                0,
                0,
                width - 16,
                23,
                () -> {
                    WidgetSpec current = layout.find(widget.id);
                    if (current == null) return;
                    List<WidgetPresentation> forms = WidgetSpec.availablePresentations(current.kind);
                    WidgetPresentation next = forms.get((forms.indexOf(current.presentation) + 1) % forms.size());
                    present(current, next, current.metricField, current.userTarget);
                }));
        List<String> metrics = WidgetSpec.availableMetrics(widget.kind);
        if (!metrics.isEmpty()) {
            body.add(
                button("metric", tr("metric") + ": " + tr("metric." + widget.metricField), 0, 0, width - 16, 23, () -> {
                    WidgetSpec current = layout.find(widget.id);
                    if (current == null) return;
                    String metric = metrics.get((metrics.indexOf(current.metricField) + 1) % metrics.size());
                    present(current, current.presentation, metric, null);
                }));
            body.add(
                UiNode.label("target-label", tr("target.user"))
                    .size(width - 16, 13));
            UiNode targetInput = UiNode
                .textInput(
                    "user-target",
                    widget.userTarget == null ? "" : Double.toString(widget.userTarget),
                    value -> {})
                .size(width - 16, 23)
                .maxLength(32)
                .style(BUTTON);
            body.add(targetInput);
            body.add(button("apply-target", tr("target.apply"), 0, 0, width - 16, 22, () -> {
                WidgetSpec current = layout.find(widget.id);
                if (current == null) return;
                try {
                    String value = targetInput.text()
                        .trim();
                    Double target = value.isEmpty() ? null : Double.valueOf(value);
                    if (target != null && (!Double.isFinite(target) || target <= 0)) throw new NumberFormatException();
                    present(current, current.presentation, current.metricField, target);
                } catch (IllegalArgumentException invalid) {
                    notice = tr("target.invalid");
                }
            }));
        }
        body.add(button("columns", tr("columns") + ": " + widget.displayColumns, 0, 0, width - 16, 22, () -> {
            WidgetSpec current = layout.find(widget.id);
            if (current != null) configure(current, DesktopFieldPolicy.fields(current), current.displayColumns % 4 + 1);
        }));
        body.add(
            UiNode.label("fields-title", tr("fields"))
                .size(width - 16, 12));
        UiNode fields = UiNode.panel("fields")
            .size(width - 16, Math.max(22, fields(widget.kind).size() * 26 - 4))
            .layout(UiNode.Layout.COLUMN)
            .gap(4)
            .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)));
        for (String field : fields(widget.kind)) fields.add(
            UiNode.button(
                "field-" + field,
                (DesktopFieldPolicy.show(widget, field) ? "[x] " : "[ ] ") + tr("field." + field),
                () -> {
                    List<String> next = new ArrayList<>(DesktopFieldPolicy.fields(widget));
                    if (!next.remove(field)) next.add(field);
                    configure(widget, next, widget.displayColumns);
                })
                .size(width - 16, 22)
                .style(BUTTON));
        body.add(fields);
        panel.add(body);
        panel.add(button("remove", tr("remove"), 8, canvas.h - 31, width - 16, 23, () -> {
            layout.remove(selected);
            selected = "";
            changed(true);
        }));
        return panel;
    }

    private static void addResourceBinding(UiNode body, WidgetSpec widget, int width) {
        body.add(
            button(
                "resource-binding",
                tr("resource.target") + ": " + (widget.resource == null ? tr("resource.all") : widget.resource.name),
                0,
                0,
                width,
                25,
                () -> {
                    bindingOpen = !bindingOpen;
                    screenBindingOpen = false;
                    bindingPage = 0;
                    resourceSignature = "";
                    dirty = true;
                }));
        if (!bindingOpen) return;
        body.add(button("resource-list", tr("resource.all"), 0, 0, width, 23, () -> {
            WidgetSpec current = layout.find(widget.id);
            if (current != null && layout.replace(
                current.boundResource(null)
                    .withSingleResource(false))) {
                bindingOpen = false;
                changed(true);
            }
        }));
        addResourceChoices(body, width, widget.screen, widget.id);
    }

    private static void addRateProperties(UiNode body, WidgetSpec widget, int width) {
        body.add(
            button(
                "rate-mode",
                tr("rate.mode") + ": " + tr(widget.averageRate ? "rate.average" : "rate.instant"),
                0,
                0,
                width,
                23,
                () -> {
                    WidgetSpec current = layout.find(widget.id);
                    if (current != null
                        && layout.replace(current.withRate(!current.averageRate, current.averageWindowSeconds)))
                        changed(true);
                }));
        body.add(
            UiNode.label("rate-window-label", tr("rate.window"))
                .size(width, 13));
        UiNode seconds = UiNode.textInput("rate-window", Integer.toString(widget.averageWindowSeconds), value -> {})
            .size(width, 23)
            .maxLength(4)
            .style(BUTTON);
        body.add(seconds);
        body.add(button("rate-window-apply", tr("rate.window.apply"), 0, 0, width, 22, () -> {
            WidgetSpec current = layout.find(widget.id);
            if (current == null) return;
            try {
                int window = Integer.parseInt(
                    seconds.text()
                        .trim());
                if (window < 1 || window > 3600) throw new NumberFormatException();
                if (layout.replace(current.withRate(current.averageRate, window))) {
                    notice = "";
                    changed(true);
                }
            } catch (IllegalArgumentException invalid) {
                notice = tr("rate.window.invalid");
            }
        }));
    }

    /** Both entry points bind an exact AE row identity, never a machine or screen coordinate. */
    private static void addResourceChoices(UiNode body, int width, ScreenBinding screen, String widgetId) {
        addSearch(body, width, "resource-search", resourceSearch);
        UiNode categories = UiNode.panel("resource-categories")
            .size(width, 23)
            .layout(UiNode.Layout.ROW)
            .gap(4)
            .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)));
        for (int kind = 1; kind <= 2; kind++) {
            final int category = kind;
            categories.add(
                button(
                    "resource-kind-" + kind,
                    (resourceCategory == kind ? "[x] " : "") + tr(kind == 1 ? "resource.items" : "resource.fluids"),
                    0,
                    0,
                    (width - 4) / 2,
                    23,
                    () -> {
                        resourceCategory = category;
                        resetBindingPage();
                    }));
        }
        body.add(categories);
        List<ResourceBinding> choices = new ArrayList<>();
        for (ResourceBinding choice : WidgetResources.choices()) if (choice.kind == resourceCategory
            && (screen == null || choice.source.equals(screen.source) && choice.screen.equals(screen.screen))
            && resourceSearch.matches(
                choice.name + " "
                    + choice.key
                    + " "
                    + screenSearchText(new ScreenBinding(choice.source, choice.screen))))
            choices.add(choice);
        if (choices.isEmpty()) body.add(
            UiNode
                .label(
                    "resource-empty",
                    tr(resourceSearch.value.isEmpty() ? "binding.resource.empty" : "binding.search.empty"))
                .size(width, 30));
        int start = bindingPageStart(choices.size());
        addBindingPager(body, width, choices.size());
        ScreenBinding previous = null;
        for (int index = start; index < Math.min(choices.size(), start + BINDING_PAGE_SIZE); index++) {
            ResourceBinding choice = choices.get(index);
            ScreenBinding source = new ScreenBinding(choice.source, choice.screen);
            if (!source.equals(previous))
                body.add(
                    UiNode
                        .label(
                            "resource-source-" + index,
                            DesktopSourcePreview.screenTitle(source) + " · "
                                + DesktopSourcePreview.screenSummary(source))
                        .size(width, 16));
            previous = source;
            body.add(button("resource-choice-" + index, "", 0, 0, width, 32, () -> {
                if (WidgetResources.read(choice).row == null) {
                    notice = tr("binding.resource.empty");
                    dirty = true;
                    return;
                }
                if (widgetId == null) {
                    if (mountResource(choice)) {
                        libraryOpen = resourceAdding = false;
                        propertiesOpen = true;
                        bindingOpen = screenBindingOpen = false;
                        changed(true);
                    } else notice = tr("no.space");
                } else {
                    WidgetSpec current = layout.find(widgetId);
                    if (current != null && layout.replace(current.boundResource(choice))) {
                        bindingOpen = false;
                        notice = "";
                        changed(true);
                    } else notice = tr("no.space");
                }
            }).onDraw((node, renderer) -> DesktopSourcePreview.resource(font, renderer, node.bounds(), choice)));
        }
    }

    private static void addScreenBinding(UiNode body, WidgetSpec widget, int width) {
        ScreenBinding effective = widget.screen == null ? WidgetResources.defaultScreen() : widget.screen;
        if (widget.screen == null) body.add(
            UiNode
                .label(
                    "screen-default",
                    tr(
                        effective != null ? "binding.screen.automatic"
                            : WidgetResources.screenChoices()
                                .isEmpty() ? "binding.screen.empty" : "binding.screen.multiple"))
                .size(width, 25));
        body.add(
            button(
                "screen-binding",
                tr(effective == null ? "binding.screen.choose" : "binding.screen.change"),
                0,
                0,
                width,
                25,
                () -> {
                    screenBindingOpen = !screenBindingOpen;
                    bindingOpen = false;
                    bindingPage = 0;
                    pickerScreens = WidgetResources.screenChoices();
                    screenSignature = "";
                    dirty = true;
                }));
        if (effective != null) {
            body.add(
                UiNode.panel("bound-screen")
                    .size(width, 44)
                    .pointerTransparent(true)
                    .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)))
                    .onDraw((node, renderer) -> DesktopSourcePreview.screen(font, renderer, node.bounds(), effective)));
        }
        if (widget.screen != null) {
            body.add(button("screen-unbind", tr("binding.screen.clear"), 0, 0, width, 22, () -> {
                WidgetSpec current = layout.find(widget.id);
                if (current != null && layout.replace(current.boundScreen(null))) {
                    screenBindingOpen = bindingOpen = false;
                    changed(true);
                }
            }));
        }
        if (!screenBindingOpen) return;
        addSearch(body, width, "screen-search", screenSearch);
        UiNode categories = UiNode.panel("screen-categories")
            .size(width, 23)
            .layout(UiNode.Layout.ROW)
            .gap(3)
            .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)));
        String[] categoryKeys = { "all", "ae", "eu" };
        for (int index = 0; index < categoryKeys.length; index++) {
            final int category = index;
            categories.add(
                button(
                    "screen-category-" + index,
                    (screenCategory == index ? "[x] " : "") + tr("binding.screen.category." + categoryKeys[index]),
                    0,
                    0,
                    (width - 6) / 3,
                    23,
                    () -> {
                        screenCategory = category;
                        resetBindingPage();
                    }));
        }
        body.add(categories);
        List<ScreenBinding> screens = new ArrayList<>();
        for (ScreenBinding candidate : pickerScreens) if (DesktopSourcePreview.category(candidate, screenCategory)
            && screenSearch.matches(screenSearchText(candidate))) screens.add(candidate);
        if (screens.isEmpty()) body.add(
            UiNode
                .label(
                    "binding-no-screen",
                    tr(screenSearch.value.isEmpty() ? "binding.screen.empty" : "binding.search.empty"))
                .size(width, 30));
        int start = bindingPageStart(screens.size());
        addBindingPager(body, width, screens.size());
        for (int index = start; index < Math.min(screens.size(), start + BINDING_PAGE_SIZE); index++) {
            ScreenBinding binding = screens.get(index);
            body.add(button("binding-screen-" + index, "", 0, 0, width, 44, () -> {
                WidgetSpec current = layout.find(widget.id);
                if (current != null && WidgetResources.screenChoices()
                    .contains(binding) && layout.replace(current.boundScreen(binding))) {
                    screenBindingOpen = bindingOpen = false;
                    changed(true);
                } else {
                    notice = tr(
                        WidgetResources.screenChoices()
                            .contains(binding) ? "no.space" : "binding.screen.empty");
                    dirty = true;
                }
            }).onDraw((node, renderer) -> DesktopSourcePreview.screen(font, renderer, node.bounds(), binding)));
        }
    }

    private static void addDeviceBinding(UiNode body, WidgetSpec widget, int width) {
        body.add(
            button(
                "device-binding",
                tr(widget.device == null ? "binding.choose" : "binding.change"),
                0,
                0,
                width,
                25,
                () -> {
                    bindingOpen = !bindingOpen;
                    screenBindingOpen = false;
                    bindingPage = 0;
                    pickerSource = widget.device == null ? null : widget.device.source;
                    choosingDeviceSource = false;
                    pickerEntries = Collections.emptyList();
                    pickerSources = DesktopDeviceSources.sources();
                    sourceSignature = "";
                    refreshBindingSources();
                    dirty = true;
                }));
        if (widget.device != null) {
            body.add(
                UiNode.label("bound-source", tr("binding.current") + " " + shortId(widget.device.source))
                    .size(width, 15));
            body.add(button("device-unbind", tr("binding.clear"), 0, 0, width, 22, () -> {
                WidgetSpec current = layout.find(widget.id);
                if (current != null && layout.replace(current.boundDevice(null))) {
                    bindingOpen = false;
                    changed(true);
                }
            }));
        }
        if (!bindingOpen) return;
        addSearch(body, width, "device-search", deviceSearch);
        if (pickerSource == null) {
            body.add(
                UiNode.label("binding-source-title", tr("binding.source"))
                    .size(width, 15));
            if (pickerSources.isEmpty()) body.add(
                UiNode.label("binding-no-source", tr("binding.source.empty"))
                    .size(width, 30));
            List<DesktopDeviceSources.Source> sources = new ArrayList<>();
            for (DesktopDeviceSources.Source source : pickerSources)
                if (deviceSearch.matches(source.label + " " + source.id)) sources.add(source);
            if (!pickerSources.isEmpty() && sources.isEmpty()) body.add(
                UiNode.label("source-search-empty", tr("binding.search.empty"))
                    .size(width, 30));
            int sourceStart = bindingPageStart(sources.size());
            addBindingPager(body, width, sources.size());
            for (int index = sourceStart; index < Math.min(sources.size(), sourceStart + BINDING_PAGE_SIZE); index++) {
                DesktopDeviceSources.Source source = sources.get(index);
                body.add(
                    button(
                        "binding-source-" + source.id,
                        source.label + " · " + shortId(source.id),
                        0,
                        0,
                        width,
                        25,
                        () -> {
                            pickerSource = source.id;
                            choosingDeviceSource = false;
                            bindingPage = 0;
                            pickerEntries = Collections.emptyList();
                            pickerReason = "waiting";
                            deviceSearch.value = "";
                            dirty = true;
                        }));
            }
            return;
        }
        body.add(
            button(
                "binding-other-source",
                tr("binding.source") + " · " + shortId(pickerSource),
                0,
                0,
                width,
                23,
                () -> {
                    pickerSource = null;
                    choosingDeviceSource = true;
                    bindingPage = 0;
                    deviceSearch.value = "";
                    pickerEntries = Collections.emptyList();
                    dirty = true;
                }));
        if (pickerEntries.isEmpty()) body.add(
            UiNode.label("binding-no-device", tr(pickerReason.isEmpty() ? "binding.device.empty" : "binding.waiting"))
                .size(width, 30));
        body.add(
            button(
                "device-state-filter",
                tr("binding.filter.state") + ": "
                    + StatCollector.translateToLocal("gtswn.factory.filter." + pickerFilter.key()),
                0,
                0,
                width,
                23,
                () -> {
                    pickerFilter = pickerFilter.next();
                    resetBindingPage();
                }));
        body.add(
            button(
                "device-power-filter",
                StatCollector.translateToLocal("gtswn.factory.direction." + pickerPower),
                0,
                0,
                width,
                23,
                () -> {
                    pickerPower = (pickerPower + 1) % DevicePowerFilter.MODE_COUNT;
                    resetBindingPage();
                }));
        List<Entry> entries = new ArrayList<>();
        String sourceText = pickerSource.toString();
        for (DesktopDeviceSources.Source source : pickerSources)
            if (source.id.equals(pickerSource)) sourceText += " " + source.label;
        for (Entry entry : pickerEntries) if (pickerFilter.matches(entry, pickerReason.isEmpty(), pickerPower)
            && deviceSearch.matchesDevice(entry, sourceText, pickerReason.isEmpty())) entries.add(entry);
        if (!pickerEntries.isEmpty() && entries.isEmpty()) body.add(
            UiNode.label("device-search-empty", tr("binding.search.empty"))
                .size(width, 30));
        int start = bindingPageStart(entries.size());
        addBindingPager(body, width, entries.size());
        for (int index = start; index < Math.min(entries.size(), start + BINDING_PAGE_SIZE); index++) {
            Entry entry = entries.get(index);
            String name = entry.displayName == null || entry.displayName.isEmpty() ? entry.name : entry.displayName;
            UUID source = pickerSource;
            body.add(button("binding-device-" + index, "", 0, 0, width, 44, () -> {
                WidgetSpec current = layout.find(widget.id);
                DeviceBinding binding = DesktopDeviceSources.selectionReason(source)
                    .isEmpty() ? DesktopDeviceSources.binding(source, entry) : null;
                if (current != null && binding != null && layout.replace(current.boundDevice(binding))) {
                    bindingOpen = false;
                    notice = "";
                    changed(true);
                } else {
                    notice = tr("binding.waiting");
                    dirty = true;
                }
            }).onDraw((node, renderer) -> {
                DesktopSourcePreview.line(font, renderer, name, node.bounds(), 5, 4, TEXT);
                String state = StatCollector.translateToLocal(
                    "gtswn.factory.status." + FactoryMachineFilter.status(entry, pickerReason.isEmpty()));
                DesktopSourcePreview.line(
                    font,
                    renderer,
                    state + " · " + entry.instIn + " / " + entry.instOut + " EU/t",
                    node.bounds(),
                    5,
                    17,
                    ACCENT);
                DesktopSourcePreview.line(
                    font,
                    renderer,
                    entry.dim + ": " + entry.x + ", " + entry.y + ", " + entry.z,
                    node.bounds(),
                    5,
                    30,
                    DashboardVisuals.MUTED);
            }));
        }
    }

    private static String shortId(UUID id) {
        return id.toString()
            .substring(0, 8);
    }

    private static String screenSearchText(ScreenBinding binding) {
        return WidgetResources.screenLabel(binding) + " "
            + DesktopSourcePreview.screenTitle(binding)
            + " "
            + DesktopSourcePreview.screenSummary(binding)
            + " "
            + binding.source
            + " "
            + binding.screen;
    }

    private static void addSearch(UiNode body, int width, String id, FactoryTextSearch search) {
        body.add(
            UiNode.label(id + "-label", tr("binding.search"))
                .size(width, 13));
        body.add(UiNode.textInput(id, search.value, value -> {
            search.value = value;
            resetBindingPage();
        })
            .size(width, 23)
            .maxLength(96)
            .style(BUTTON));
        if (!search.value.isEmpty()) body.add(button(id + "-clear", tr("binding.search.clear"), 0, 0, width, 20, () -> {
            search.value = "";
            resetBindingPage();
        }));
    }

    private static void resetBindingPage() {
        bindingPage = 0;
        if (scene != null) {
            UiNode library = scene.find("library-list"), properties = scene.find("properties-body");
            if (library != null) library.scrollTo(0);
            if (properties != null) properties.scrollTo(0);
        }
        dirty = true;
    }

    private static int bindingPageStart(int count) {
        bindingPage = Math.max(0, Math.min(bindingPage, Math.max(0, (count - 1) / BINDING_PAGE_SIZE)));
        return bindingPage * BINDING_PAGE_SIZE;
    }

    private static void addBindingPager(UiNode body, int width, int count) {
        if (count <= BINDING_PAGE_SIZE) return;
        int pages = (count + BINDING_PAGE_SIZE - 1) / BINDING_PAGE_SIZE;
        UiNode row = UiNode.panel("binding-pages")
            .size(width, 23)
            .layout(UiNode.Layout.ROW)
            .gap(4)
            .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)));
        row.add(button("binding-previous", "<", 0, 0, 30, 23, () -> {
            bindingPage = Math.max(0, bindingPage - 1);
            dirty = true;
        }));
        row.add(
            UiNode.label("binding-page", (bindingPage + 1) + " / " + pages)
                .size(width - 68, 23));
        row.add(button("binding-next", ">", 0, 0, 30, 23, () -> {
            bindingPage = Math.min(pages - 1, bindingPage + 1);
            dirty = true;
        }));
        body.add(row);
    }

    private static void present(WidgetSpec current, WidgetPresentation form, String metric, Double target) {
        if (layout.replace(current.presented(form, metric, target))) {
            notice = "";
            changed(true);
        } else notice = tr("no.space");
    }

    private static void configure(WidgetSpec widget, List<String> fields, int columns) {
        WidgetSpec current = layout.find(widget.id);
        if (current == null) return;
        if (layout.replace(current.configured(current.title, fields, columns))) {
            notice = "";
            changed(true);
        } else notice = tr("no.space");
    }

    private static void drawGrid(UiNode node, UiRenderer renderer) {
        double step = DesktopLayout.CELL * view.scale;
        while (step < 20) step *= 2;
        double firstX = canvas.x + DesktopCardGeometry.gridOffset(view.scale) - view.panX * view.scale % step,
            firstY = canvas.y + DesktopCardGeometry.gridOffset(view.scale) - view.panY * view.scale % step;
        for (double x = firstX; x < canvas.x + canvas.w; x += step)
            renderer.fill(new UiRect(x, canvas.y, 1, canvas.h), GRID);
        for (double y = firstY; y < canvas.y + canvas.h; y += step)
            renderer.fill(new UiRect(canvas.x, y, canvas.w, 1), GRID);
        CanvasTransform transform = view.transform(canvas.x, canvas.y);
        if (view.scale < DesktopCardGeometry.BORDER_LOD_SCALE) return;
        for (WidgetSpec widget : layout.widgets()) {
            Rect b = bounds(widget, transform);
            if (intersects(b, canvas)) renderer.fill(new UiRect(b.x + 2, b.y + 3, b.w, b.h), 0x50000000);
        }
    }

    private static void drawWidget(WidgetSpec widget, UiNode node, UiRenderer renderer) {
        if (widget == null) return;
        if (view.scale < DesktopCardGeometry.BORDER_LOD_SCALE) {
            UiRect b = node.bounds();
            renderer.fill(b, DesktopCardGeometry.overviewFill(widget));
            return;
        }
        CanvasTransform transform = DesktopCardGeometry.contentTransform(widget, view.transform(canvas.x, canvas.y));
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(transform.toScreenX(0), transform.toScreenY(0), 0);
            GL11.glScaled(transform.scale(), transform.scale(), 1);
            FactoryDashboard.paintWidget(
                font,
                widget,
                DesktopCardGeometry.canonical(widget),
                (int) Math.floor(transform.toLocalX(mouseX)),
                (int) Math.floor(transform.toLocalY(mouseY)),
                transform);
        } finally {
            GL11.glPopMatrix();
        }
    }

    private static Rect bounds(WidgetSpec w, CanvasTransform t) {
        return DesktopCardGeometry.bounds(w, t);
    }

    private static boolean intersects(Rect a, Rect b) {
        return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
    }

    private static void selection(UiRenderer r, Rect b) {
        r.fill(new UiRect(b.x, b.y, b.w, 2), ACCENT);
        r.fill(new UiRect(b.x, b.y + b.h - 2, b.w, 2), ACCENT);
        r.fill(new UiRect(b.x, b.y, 2, b.h), ACCENT);
        r.fill(new UiRect(b.x + b.w - 2, b.y, 2, b.h), ACCENT);
        if (!DesktopCardGeometry.showResizeHandles(view.scale)) return;
        for (int x : new int[] { b.x + 3, b.x + b.w / 2, b.x + b.w - 3 })
            for (int y : new int[] { b.y + 3, b.y + b.h / 2, b.y + b.h - 3 })
                if (x != b.x + b.w / 2 || y != b.y + b.h / 2) r.fill(new UiRect(x - 3, y - 3, 6, 6), ACCENT);
    }
}
