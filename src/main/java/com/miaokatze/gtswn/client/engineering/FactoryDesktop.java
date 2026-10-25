package com.miaokatze.gtswn.client.engineering;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.DevicePowerFilter;
import com.miaokatze.gtswn.client.engineering.DashboardIcons.Icon;
import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.DashboardTheme;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopAutoBind;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDeviceSources;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDrag;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopGesture;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopInformationTemplate;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopLayout;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopPreset;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopPresetCode;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopPresetPlacement;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopSaveSession;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopSelection;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopStore;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopViewport;
import com.miaokatze.gtswn.client.engineering.desktop.DeviceBinding;
import com.miaokatze.gtswn.client.engineering.desktop.ResourceBinding;
import com.miaokatze.gtswn.client.engineering.desktop.ScreenBinding;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetNumberFormat;
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

    private static int BACK = DashboardVisuals.BACK, GRID = 0x503B647D, TEXT = DashboardVisuals.TEXT,
        ACCENT = DashboardVisuals.CYAN, MUTED = DashboardVisuals.MUTED;
    private static UiStyle PANEL = UiStyle.DEFAULT, BUTTON = UiStyle.DEFAULT;
    private static final Map<String, String> tooltips = new HashMap<>();

    private static void palette() {
        DashboardTheme theme = layout.theme();
        DesktopSourcePreview.theme(theme);
        BACK = theme.background;
        GRID = (theme.grid & 0x00FFFFFF) | ((theme.grid >>> 24) / 4 << 24);
        TEXT = theme.text;
        ACCENT = theme.accent;
        MUTED = theme.muted;
        PANEL = new UiStyle(new UiStyle.Paint(theme.cardTop, theme.edge, TEXT));
        BUTTON = new UiStyle(
            new UiStyle.Paint(theme.header, theme.edge, TEXT),
            new UiStyle.Paint(theme.track, ACCENT, TEXT),
            new UiStyle.Paint(theme.cardBottom, ACCENT, TEXT),
            new UiStyle.Paint(theme.track, ACCENT, ACCENT),
            new UiStyle.Paint(theme.cardBottom, theme.edge, MUTED));
    }

    private static DesktopStore store;
    private static DesktopLayout layout;
    private static DesktopSaveSession saves;
    private static final DesktopSelection selection = new DesktopSelection();
    private static Runnable pendingExit;
    private static boolean shareOpen;
    private static DesktopPreset pendingPreset;
    private static DesktopLayout pendingImport;
    private static DesktopPresetPlacement placement;
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
        saves = new DesktopSaveSession(layout);
        pendingExit = null;
        shareOpen = false;
        selection.only("");
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
                if (DesktopAutoBind.initializeAvailable(layout)) {
                    // Automatic binding is system initialization, independent of a user's draft.
                    if (saves != null) {
                        DesktopLayout baseline = saves.saved();
                        DesktopAutoBind.initializeAvailable(baseline);
                        saves.accept(baseline);
                        store.put(profile, baseline);
                    }
                    dirty = true;
                }
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
            store.put(profile, saves == null ? layout : saves.saved());
            store.putView(profile, view);
            store.flush();
        }
    }

    private static void changed(boolean structure) {
        if (gesture.active() || drag.active() || panning) return;
        if (saves == null) saves = new DesktopSaveSession(store.load(profile));
        store.putView(profile, view);
        dirty |= structure;
    }

    static boolean hasUnsavedChanges() {
        return saves != null && layout != null && saves.unsaved(layout);
    }

    /** All voluntary host exits run their original operation only after the user's decision. */
    static boolean requestExit(Runnable accepted) {
        if (pendingExit != null) return true;
        blur();
        if (!hasUnsavedChanges()) {
            accepted.run();
            return true;
        }
        EngineeringModeController.revealExitPrompt();
        pendingExit = accepted;
        dirty = true;
        return true;
    }

    private static boolean saveLayout() {
        blur();
        if (saves == null) saves = new DesktopSaveSession(store.load(profile));
        boolean saved = saves.save(store, profile, layout, view);
        notice = tr(saved ? "save.success" : "save.failed");
        dirty = true;
        return saved;
    }

    private static void resolveExit(boolean save) {
        if (pendingExit == null) return;
        if (save && !saveLayout()) return;
        if (!save) {
            layout = saves.saved();
            selected = "";
            selection.only("");
            bindingOpen = screenBindingOpen = false;
        }
        Runnable accepted = pendingExit;
        pendingExit = null;
        dirty = true;
        accepted.run();
    }

    private static void finishEditing() {
        edit = libraryOpen = propertiesOpen = shareOpen = canvasFocus = false;
        clearPresetImport();
        dirty = true;
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
        selection.only(selected);
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
        selection.only(selected);
        DesktopCardGeometry.focusAtOne(view, widget, canvas);
        changed(true);
        return true;
    }

    /** Existing click selection gating calls this only when the user activates this exact empty component. */
    static void requestBinding(String id) {
        WidgetSpec widget = layout == null ? null : layout.find(id);
        if (widget == null || !widget.kind.supportsDeviceBinding() && widget.kind != WidgetKind.RESOURCES) return;
        selected = widget.id;
        selection.only(selected);
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
        if (placement != null && button == 1) {
            if (pressed) blur();
            return true;
        }
        if (pendingExit != null && scene.find("save-modal") == null && scene.captured() == null) build();
        if (pressed && button >= 0 && !canvas.contains(x, y)) canvasFocus = false;
        if (wheel != 0 && scene.captured() != null) return true;
        if (button >= 0 && pressed) FactoryDashboard.blurResourceSearch(-1, -1);
        UiNode library = scene.find("library"), properties = scene.find("properties"),
            share = scene.find("share-panel");
        boolean tools = library != null && library.bounds()
            .contains(x, y) || properties != null
                && properties.bounds()
                    .contains(x, y)
            || share != null && share.bounds()
                .contains(x, y);
        boolean used;
        if (wheel != 0) used = scene.scroll(x, y, !tools && canvas.contains(x, y) ? wheel : -Math.signum(wheel) * 24);
        else if (button >= 0) used = pressed ? scene.pointerDown(x, y, button) : scene.pointerUp(x, y, button);
        else used = scene.pointerMove(x, y) || pointerCaptured();
        return used || pendingExit != null || area.contains(x, y);
    }

    static boolean key(int code, char character, boolean pressed) {
        if (scene == null || code == Keyboard.KEY_ESCAPE) return false;
        if (placement != null && code == Keyboard.KEY_DELETE) return true;
        if (pendingExit != null) {
            if (scene.find("save-modal") == null && scene.captured() == null) build();
            if (pressed && (code == Keyboard.KEY_TAB || code == Keyboard.KEY_RETURN))
                scene.keyTyped(character, code, shiftDown(), controlDown());
            return true;
        }
        if ((gesture.active() || drag.active() || panning || selection.active())
            && (code == Keyboard.KEY_DELETE || code == Keyboard.KEY_TAB)) return true;
        boolean control = pressed
            && (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
        if (pressed && control && code == Keyboard.KEY_S && !typing()) {
            saveLayout();
            return true;
        }
        if (placement != null) return true;
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
                syncSelection();
                for (String id : new ArrayList<>(selection.ids())) layout.remove(id);
                selected = "";
                selection.only("");
                changed(true);
            }
            return true;
        }
        return typing();
    }

    static boolean escape() {
        if (placement != null) {
            blur();
            return true;
        }
        if (pendingExit != null) {
            pendingExit = null;
            dirty = true;
            return true;
        }
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
        if (libraryOpen || propertiesOpen || shareOpen) {
            libraryOpen = propertiesOpen = shareOpen = false;
            dirty = true;
            return true;
        }
        if (edit) {
            requestExit(FactoryDesktop::finishEditing);
            return true;
        }
        return false;
    }

    static void blur() {
        cancelPlacement();
        if (scene != null) scene.blur();
        cancelGesture();
        canvasFocus = false;
    }

    static void focusOutside(int x, int y) {
        if (area != null && !area.contains(x, y)) blur();
    }

    static void suspend() {
        pendingExit = null;
        dirty = true;
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
        if (current != null && DesktopCardGeometry.hit(
            bounds(current, transform),
            sx,
            sy,
            selection.ids()
                .size() < 2 && DesktopCardGeometry.showResizeHandles(view.scale)))
            return current;
        for (WidgetSpec widget : layout.widgets())
            if (DesktopCardGeometry.hit(bounds(widget, transform), sx, sy, false)) return widget;
        return null;
    }

    private static boolean controlDown() {
        return Keyboard.isCreated()
            && (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL));
    }

    private static boolean shiftDown() {
        return Keyboard.isCreated()
            && (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT));
    }

    private static void syncSelection() {
        if (selected.isEmpty() || !selection.ids()
            .contains(selected)) selection.only(selected);
        java.util.Set<String> valid = new java.util.HashSet<>();
        for (WidgetSpec widget : layout.widgets()) valid.add(widget.id);
        selection.retain(valid);
    }

    private static void down(UiEvent e) {
        down(e, controlDown());
    }

    private static void down(UiEvent e, boolean control) {
        if (pendingExit != null) {
            e.consume();
            return;
        }
        if (placement != null) {
            if (e.button == 1) {
                blur();
            } else if (e.button == 0) {
                pointPlacement(e.x, e.y);
                placement.press(e.x, e.y);
                e.capturePointer();
            } else if (e.button == 2) {
                panning = true;
                lastX = e.x;
                lastY = e.y;
                panBeforeX = view.panX;
                panBeforeY = view.panY;
                e.capturePointer();
            }
            e.consume();
            return;
        }
        syncSelection();
        FactoryDashboard.blurResourceSearch((int) e.x, (int) e.y);
        canvasFocus = true;
        double x = view.toLocalX(e.x, canvas.x), y = view.toLocalY(e.y, canvas.y);
        WidgetSpec target = hit(x, y);
        if (edit && control && e.button == 0) {
            if (target == null) {
                selection.begin(x, y);
            } else {
                selection.toggle(target.id);
                selected = selection.ids()
                    .isEmpty() ? ""
                        : selection.ids()
                            .iterator()
                            .next();
                dirty = true;
            }
            e.capturePointer();
            e.consume();
            return;
        }
        if (e.button == 2 || e.button == 0 && target == null) {
            if (target == null) {
                selected = "";
                selection.only("");
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
            boolean was = selection.ids()
                .contains(target.id);
            if (!was) selection.only(target.id);
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
            pressedEdge = was && selection.ids()
                .size() < 2 && DesktopCardGeometry.showResizeHandles(view.scale) ? edge(r, e.x, e.y)
                    : DesktopDrag.Edge.MOVE;
            pressLocalX = x;
            pressLocalY = y;
            boolean direct = was && (edit && selection.ids()
                .size() > 1 || pressedEdge != DesktopDrag.Edge.MOVE
                || edit && DesktopCardGeometry.titleHandle(r, view.scale, e.x, e.y));
            gesture.begin(was, direct, e.x, e.y, System.nanoTime());
            e.capturePointer();
            e.consume();
            return;
        }
        e.consume();
    }

    private static void move(UiEvent e) {
        if (placement != null) {
            if (panning) {
                view.pan(e.x - lastX, e.y - lastY);
                lastX = e.x;
                lastY = e.y;
            }
            pointPlacement(e.x, e.y);
            placement.move(e.x, e.y);
            e.consume();
            return;
        }
        if (selection.active()) {
            selection.update(layout, view.toLocalX(e.x, canvas.x), view.toLocalY(e.y, canvas.y));
            e.consume();
            return;
        }
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
            if (edit && selection.ids()
                .size() > 1 && pressedEdge == DesktopDrag.Edge.MOVE)
                drag.beginGroup(layout, selection.ids(), pressedWidget, pressLocalX, pressLocalY);
            else drag.begin(layout, pressedWidget, pressedEdge, pressLocalX, pressLocalY);
        }
        if (drag.active() && gesture.moveAllowed(x, y))
            drag.update(view.toLocalX(x, canvas.x), view.toLocalY(y, canvas.y));
    }

    private static void up(UiEvent e) {
        if (placement != null) {
            move(e);
            if (panning && e.button == 2) {
                panning = false;
                changed(false);
            } else if (e.button == 0 && !panning) {
                UiNode hovered = scene.hitTest(e.x, e.y);
                List<WidgetSpec> added = placement.release(
                    layout,
                    e.x,
                    e.y,
                    canvas.contains((int) e.x, (int) e.y) && hovered != null && hovered.id.equals("preset-placement"));
                if (!added.isEmpty()) {
                    placement = null;
                    selected = added.get(0).id;
                    selection.only(selected);
                    DesktopAutoBind.initializeAvailable(layout);
                    nextDefaultBindingCheck = System.nanoTime() + 1000000000L;
                    notice = tr("preset.appended");
                    changed(true);
                }
            }
            e.consume();
            return;
        }
        move(e);
        if (selection.active()) {
            selection.finish();
            selected = selection.ids()
                .isEmpty() ? ""
                    : selection.ids()
                        .iterator()
                        .next();
            dirty = true;
            e.consume();
            return;
        }
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
        if (selection.active()) selection.cancel();
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

    private static void cancelPlacement() {
        if (placement == null) return;
        placement = null;
        notice = "";
        dirty = true;
    }

    private static void pointPlacement(double x, double y) {
        if (placement != null) placement.point(view.toLocalX(x, canvas.x), view.toLocalY(y, canvas.y));
    }

    private static DesktopDrag.Edge edge(Rect r, double x, double y) {
        return DesktopCardGeometry.edge(r, x, y);
    }

    static void render(FontRenderer f, Rect r, int mx, int my, int width, int height) {
        ensure();
        font = f;
        FactoryDashboard.theme(layout.theme());
        mouseX = mx;
        mouseY = my;
        screenWidth = width;
        screenHeight = height;
        boolean resized = area == null || area.x != r.x || area.y != r.y || area.w != r.w || area.h != r.h;
        if (resized) blur();
        advanceGesture(mx, my);
        if (!selection.active()) syncSelection();
        area = r;
        if (scene == null || (dirty || resized) && scene.captured() == null) build();
        zoomLabel.text(Math.round(view.scale * 100) + "%");
        if (placement != null) pointPlacement(mx, my);
        statusLabel.text(
            placement != null ? tr(placement.valid(layout) ? "placement.hint" : "placement.blocked")
                : notice.isEmpty() ? tr(edit ? "hint.edit" : "hint.browse") : notice);
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
            UiNode format = scene.find("number-format-" + widget.id);
            if (format != null) {
                double scale = view.scale;
                int reserve = widget.kind == WidgetKind.HISTORY ? 84 : 30;
                format.bounds(b.w - reserve * scale, 3 * scale, 24 * scale, 16 * scale)
                    .visible(scale >= DesktopCardGeometry.BORDER_LOD_SCALE);
            }
            scene.find("selection-" + widget.id)
                .size(b.w, b.h)
                .visible(
                    selection.ids()
                        .contains(widget.id));
        }
        scene.layout(width, height);
        Minecraft mc = Minecraft.getMinecraft();
        MinecraftUiRenderer renderer = new MinecraftUiRenderer(f, width, height, mc.displayWidth, mc.displayHeight);
        scene.render(renderer);
        UiNode hovered = scene.hitTest(mx, my);
        if (!edit && !panning && !gesture.active() && hovered != null && hovered.id.startsWith("card-"))
            FactoryDashboard.drawHover(f, mx, my, width, height);
        renderer.begin(CanvasTransform.identity());
        try {
            drawTooltip(renderer, hovered, mx, my, width, height);
        } finally {
            renderer.end();
        }
    }

    private static void build() {
        palette();
        tooltips.clear();
        DesktopToolbarGeometry toolbar = new DesktopToolbarGeometry(area.w, edit);
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
            .style(new UiStyle(new UiStyle.Paint(BACK, layout.theme().edge, TEXT)));
        root.add(shell);
        canvas = new Rect(area.x, area.y + toolbar.height, area.w, Math.max(1, area.h - toolbar.height - 18));
        if (initialView) {
            view.reset(layout, canvas.w, canvas.h);
            initialView = false;
        }
        UiNode canvasNode = UiNode.panel("canvas")
            .bounds(0, toolbar.height, canvas.w, canvas.h)
            .overflowClip(true)
            .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)))
            .onDraw(FactoryDesktop::drawGrid)
            .on(UiEvent.Type.DOWN, FactoryDesktop::down)
            .on(UiEvent.Type.MOVE, FactoryDesktop::move)
            .on(UiEvent.Type.UP, FactoryDesktop::up)
            .on(UiEvent.Type.CANCEL, e -> {
                cancelPlacement();
                cancelGesture();
            })
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
            if (supportsNumberFormat(widget)) card.add(numberFormatButton(widget));
            canvasNode.add(card);
        }
        canvasNode.add(
            UiNode.panel("marquee")
                .bounds(0, 0, canvas.w, canvas.h)
                .pointerTransparent(true)
                .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)))
                .onDraw((node, renderer) -> {
                    if (!selection.active()) return;
                    CanvasTransform transform = view.transform(canvas.x, canvas.y);
                    double left = transform.toScreenX(selection.left()), top = transform.toScreenY(selection.top());
                    double width = (selection.right() - selection.left()) * view.scale;
                    double height = (selection.bottom() - selection.top()) * view.scale;
                    selectionOutline(renderer, new Rect((int) left, (int) top, (int) width, (int) height));
                }));
        if (placement != null) canvasNode.add(
            UiNode.panel("preset-placement")
                .bounds(0, 0, canvas.w, canvas.h)
                .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)))
                .onDraw((node, renderer) -> drawPlacement(renderer)));
        shell.add(canvasNode);
        // Tools float above the full-size canvas; editing never squeezes the dashboard.
        UiRect title = toolbar.title;
        shell.add(
            UiNode.label("desktop-title", tr("title"))
                .bounds(title.x, title.y, title.width, title.height));
        shell.add(
            toolbarButton(
                toolbar,
                "back-world",
                tr(EngineeringModeController.isActive() ? "back.world" : "back.view"),
                Icon.BACK,
                () -> WorkbenchHud.switchTask(1)));
        shell.add(toolbarButton(toolbar, "edit", tr(edit ? "done" : "edit"), edit ? Icon.CHECK : Icon.EDIT, () -> {
            FactoryDashboard.blur();
            if (edit) requestExit(FactoryDesktop::finishEditing);
            else {
                edit = true;
                libraryOpen = propertiesOpen = canvasFocus = false;
                dirty = true;
            }
        }).selected(edit));
        if (edit) shell.add(toolbarButton(toolbar, "library-toggle", tr("library"), Icon.LIBRARY, () -> {
            FactoryDashboard.blur();
            libraryOpen = !libraryOpen;
            propertiesOpen = shareOpen = false;
            dirty = true;
        }).selected(libraryOpen));
        shell.add(
            toolbarButton(
                toolbar,
                "save",
                tr(hasUnsavedChanges() ? "save.unsaved" : "save"),
                Icon.SAVE,
                FactoryDesktop::saveLayout));
        shell.add(toolbarButton(toolbar, "share", tr("preset.share"), Icon.SHARE, () -> {
            FactoryDashboard.blur();
            shareOpen = !shareOpen;
            libraryOpen = propertiesOpen = false;
            dirty = true;
        }).selected(shareOpen));
        UiRect zoom = toolbar.controls.get("zoom");
        zoomLabel = button("zoom", "60%", zoom.x, zoom.y, zoom.width, zoom.height, () -> {
            WidgetSpec target = layout.find(selected);
            nextDefaultBindingCheck = 0;
            view.reset(layout, canvas.w, canvas.h);
            if (target != null) {
                view.panX = target.x() + (target.width() - canvas.w / view.scale) / 2;
                view.panY = target.y() + (target.height() - canvas.h / view.scale) / 2;
            }
            changed(false);
        });
        tooltips.put("zoom", tr("zoom.reset"));
        shell.add(zoomLabel);
        shell.add(toolbarButton(toolbar, "fit", tr("fit"), Icon.FIT, () -> {
            view.fit(layout, canvas.w, canvas.h);
            changed(false);
        }));
        shell.add(
            toolbarButton(
                toolbar,
                "theme",
                tr("theme") + ": "
                    + tr(
                        "theme." + layout.theme()
                            .name()
                            .toLowerCase(Locale.ROOT)),
                Icon.THEME,
                () -> {
                    DashboardTheme[] themes = DashboardTheme.values();
                    layout.setTheme(
                        themes[(layout.theme()
                            .ordinal() + 1) % themes.length]);
                    changed(true);
                }));
        if (edit && libraryOpen) shell.add(library(Math.min(resourceAdding ? 290 : 230, area.w - 12)));
        if (edit && propertiesOpen && layout.find(selected) != null) {
            int width = Math.min(230, Math.max(170, area.w / 3));
            shell.add(properties(Math.max(0, area.w - width - 6), width));
        }
        if (shareOpen) shell.add(sharePanel(Math.min(300, area.w - 12)));
        statusLabel = UiNode.label("desktop-hint", tr(edit ? "hint.edit" : "hint.browse"))
            .bounds(12, area.h - 15, area.w - 24, 12);
        shell.add(statusLabel);
        if (pendingExit != null) {
            shell.enabled(false);
            root.add(exitDialog());
            focusedInput = "";
        }
        themeLabels(root);
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

    static boolean supportsNumberFormat(WidgetSpec widget) {
        return widget.kind == WidgetKind.WIRELESS || widget.kind == WidgetKind.HISTORY;
    }

    /** Own capture bypasses card selection, while a moved/cancelled press cannot change the notation. */
    private static UiNode numberFormatButton(WidgetSpec widget) {
        String id = "number-format-" + widget.id;
        String mode = widget.numberFormat == WidgetNumberFormat.NORMAL ? "N"
            : widget.numberFormat == WidgetNumberFormat.SCIENTIFIC ? "E" : "K";
        tooltips.put(
            id,
            tr("number.format") + ": "
                + tr("number.format." + widget.numberFormat.name())
                + "\n"
                + tr("number.format.next")
                + ": "
                + tr(
                    "number.format." + widget.numberFormat.next()
                        .name()));
        Runnable change = () -> {
            WidgetSpec current = layout.find(widget.id);
            if (pendingExit == null && current != null
                && layout.replace(current.withNumberFormat(current.numberFormat.next()))) changed(true);
        };
        double[] press = new double[2];
        boolean[] active = new boolean[1];
        int reserve = widget.kind == WidgetKind.HISTORY ? 84 : 30;
        return UiNode.button(id, mode, change)
            .bounds(
                (widget.width() - DesktopCardGeometry.GAP - reserve) * view.scale,
                3 * view.scale,
                24 * view.scale,
                16 * view.scale)
            .style(BUTTON)
            .on(UiEvent.Type.DOWN, e -> {
                active[0] = e.button == 0 && pendingExit == null;
                press[0] = e.x;
                press[1] = e.y;
                e.capturePointer();
                e.consume();
            })
            .on(UiEvent.Type.MOVE, e -> {
                if (Math.hypot(e.x - press[0], e.y - press[1]) > 4) active[0] = false;
                e.consume();
            })
            .on(UiEvent.Type.UP, e -> {
                if (active[0] && e.button == 0
                    && e.currentTarget.bounds()
                        .contains(e.x, e.y)
                    && Math.hypot(e.x - press[0], e.y - press[1]) <= 4) change.run();
                active[0] = false;
                e.consume();
            })
            .on(UiEvent.Type.CANCEL, e -> {
                active[0] = false;
                e.consume();
            });
    }

    private static UiNode toolbarButton(DesktopToolbarGeometry toolbar, String id, String tooltip, Icon icon,
        Runnable action) {
        UiRect rect = toolbar.controls.get(id);
        return iconButton(id, tooltip, icon, rect.x, rect.y, rect.width, rect.height, action);
    }

    private static UiNode iconButton(String id, String tooltip, Icon icon, double x, double y, double w, double h,
        Runnable action) {
        tooltips.put(id, tooltip);
        return UiNode.button(id, "", action)
            .bounds(x, y, w, h)
            .style(BUTTON)
            .onDraw((node, renderer) -> {
                UiRect rect = node.bounds();
                double size = Math.min(16, Math.min(rect.width - 6, rect.height - 6));
                DashboardIcons.draw(
                    renderer,
                    icon,
                    rect.x + (rect.width - size) / 2,
                    rect.y + (rect.height - size) / 2,
                    size,
                    node.enabled() ? TEXT : MUTED);
            });
    }

    private static UiNode button(String id, String label, double x, double y, double w, double h, Runnable action) {
        Icon icon = actionIcon(id);
        if (icon != null) return iconButton(id, label.isEmpty() ? tr(id) : label, icon, x, y, w, h, action)
            .selected(id.equals("device-binding") && bindingOpen || id.equals("screen-binding") && screenBindingOpen);
        if (id.equals("presentation") || id.equals("metric")
            || id.equals("columns")
            || id.equals("effects-style")
            || id.equals("resource-binding"))
            return iconTextButton(
                id,
                label,
                id.equals("resource-binding") ? Icon.RESOURCE : Icon.SETTINGS,
                x,
                y,
                w,
                h,
                action);
        if (!label.isEmpty()) tooltips.put(id, label);
        return UiNode.button(id, label, action)
            .bounds(x, y, w, h)
            .style(BUTTON);
    }

    private static Icon kindIcon(WidgetKind kind) {
        switch (kind) {
            case WIRELESS:
                return Icon.WIRELESS;
            case HISTORY:
                return Icon.HISTORY;
            case RESOURCES:
                return Icon.RESOURCE;
            case DEVICE:
                return Icon.MACHINE;
            default:
                return Icon.GAUGE;
        }
    }

    private static UiNode iconTextButton(String id, String label, Icon icon, double x, double y, double w, double h,
        Runnable action) {
        tooltips.put(id, label);
        return UiNode.button(id, "", action)
            .bounds(x, y, w, h)
            .style(BUTTON)
            .onDraw((node, renderer) -> {
                UiRect rect = node.bounds();
                int color = node.enabled() ? TEXT : MUTED;
                DashboardIcons.draw(renderer, icon, rect.x + 5, rect.y + (rect.height - 16) / 2, 16, color);
                String visible = label;
                double available = Math.max(0, rect.width - 30);
                while (!visible.isEmpty() && renderer.textWidth(visible) > available)
                    visible = visible.substring(0, visible.length() - 1);
                renderer.text(visible, rect.x + 26, rect.y + (rect.height - renderer.textHeight()) / 2, color);
            });
    }

    private static Icon actionIcon(String id) {
        if (id.startsWith("close-")) return Icon.CLOSE;
        if (id.equals("remove")) return Icon.DELETE;
        if (id.equals("binding-previous")) return Icon.PREVIOUS;
        if (id.equals("binding-next")) return Icon.NEXT;
        if (id.endsWith("-unbind")) return Icon.UNLINK;
        if (id.equals("device-binding") || id.equals("screen-binding")) return Icon.LINK;
        if (id.equals("apply-border") || id.equals("apply-target") || id.equals("effects-apply")) return Icon.CHECK;
        return null;
    }

    private static void themeLabels(UiNode node) {
        if (node.kind == UiNode.Kind.LABEL) node.style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)));
        for (UiNode child : node.children()) themeLabels(child);
    }

    /** Final screen-space pass: toolbar/property tooltips must escape canvas and panel clips. */
    private static void drawTooltip(UiRenderer renderer, UiNode hovered, int x, int y, int width, int height) {
        String text = hovered == null ? null : tooltips.get(hovered.id);
        if (text == null || text.isEmpty() || pointerCaptured()) return;
        int maxWidth = Math.max(1, Math.min(280, width - 16));
        List<String> lines = font.listFormattedStringToWidth(text, maxWidth - 12);
        double boxWidth = 16;
        for (String line : lines) boxWidth = Math.max(boxWidth, renderer.textWidth(line) + 12);
        double boxHeight = lines.size() * 11 + 10;
        double left = Math.max(2, Math.min(x + 12, width - boxWidth - 2));
        double top = Math.max(2, Math.min(y + 16, height - boxHeight - 2));
        renderer.fill(new UiRect(left - 1, top - 1, boxWidth + 2, boxHeight + 2), layout.theme().edge);
        renderer.fill(new UiRect(left, top, boxWidth, boxHeight), BACK);
        for (int i = 0; i < lines.size(); i++) renderer.text(lines.get(i), left + 6, top + 5 + i * 11, TEXT);
    }

    private static UiNode library(int width) {
        UiNode panel = UiNode.panel("library")
            .bounds(6, canvas.y - area.y + 2, width, Math.max(1, canvas.h - 4))
            .style(PANEL)
            .overflowClip(true);
        panel.add(button("close-library", tr("close"), width - 26, 5, 20, 20, () -> {
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
        list.add(
            UiNode.label("add-title", tr("library"))
                .size(width - 12, 14));
        for (DesktopInformationTemplate template : DesktopInformationTemplate.values()) {
            list.add(
                iconTextButton(
                    "add-" + template.name(),
                    StatCollector.translateToLocal(template.titleKey()),
                    Icon.GAUGE,
                    0,
                    0,
                    width - 12,
                    25,
                    () -> {
                        WidgetSpec added = layout.add(WidgetKind.CLOCK);
                        if (added == null) return;
                        layout.replace(
                            added
                                .configured(StatCollector.translateToLocal(template.titleKey()), template.fields(), 1));
                        selectAdded(added);
                    }));
        }
        for (WidgetKind kind : WidgetKind.values()) {
            if (kind == WidgetKind.CLOCK) continue;
            list.add(iconTextButton("add-" + kind, kindLabel(kind), kindIcon(kind), 0, 0, width - 12, 25, () -> {
                WidgetSpec added = layout.add(kind);
                if (added == null) return;
                selectAdded(added);
            }));
        }
        panel.add(list);
        return panel;
    }

    private static void selectAdded(WidgetSpec added) {
        selected = added.id;
        selection.only(selected);
        bindingOpen = screenBindingOpen = choosingDeviceSource = false;
        propertiesOpen = true;
        libraryOpen = shareOpen = false;
        view.panX = added.x() - 18 / view.scale;
        view.panY = added.y() - 18 / view.scale;
        changed(true);
    }

    private static UiNode sharePanel(int width) {
        UiNode panel = UiNode.panel("share-panel")
            .bounds(6, canvas.y - area.y + 2, width, Math.max(1, canvas.h - 4))
            .style(PANEL)
            .overflowClip(true);
        panel.add(button("close-share", tr("close"), width - 26, 5, 20, 20, () -> {
            shareOpen = false;
            clearPresetImport();
            dirty = true;
        }));
        UiNode list = UiNode.panel("share-list")
            .bounds(6, 34, width - 12, Math.max(1, canvas.h - 42))
            .layout(UiNode.Layout.COLUMN)
            .gap(6)
            .scrollable(true)
            .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)));
        addPresetSharing(list, width - 12);
        panel.add(list);
        return panel;
    }

    private static UiNode exitDialog() {
        UiNode overlay = UiNode.panel("save-modal")
            .bounds(0, 0, screenWidth, screenHeight)
            .style(new UiStyle(new UiStyle.Paint(0xA0000000, 0, TEXT)))
            .on(UiEvent.Type.DOWN, e -> { if (e.target.id.equals("save-modal")) e.consume(); })
            .on(UiEvent.Type.UP, e -> { if (e.target.id.equals("save-modal")) e.consume(); })
            .on(UiEvent.Type.SCROLL, UiEvent::consume);
        int width = Math.min(310, screenWidth - 12);
        UiNode dialog = UiNode.panel("save-dialog")
            .bounds((screenWidth - width) / 2, Math.max(6, (screenHeight - 182) / 2), width, 182)
            .style(PANEL);
        dialog.add(
            UiNode.label("save-question", tr("save.question"))
                .bounds(10, 12, width - 20, 30));
        dialog.add(button("save-exit", tr("save.exit"), 10, 49, width - 20, 26, () -> resolveExit(true)));
        dialog.add(button("discard-exit", tr("save.discard"), 10, 82, width - 20, 26, () -> resolveExit(false)));
        dialog.add(button("cancel-exit", tr("preset.cancel"), 10, 115, width - 20, 26, () -> {
            pendingExit = null;
            dirty = true;
        }));
        if (!notice.isEmpty()) dialog.add(
            UiNode.label("save-feedback", notice)
                .bounds(10, 153, width - 20, 22));
        overlay.add(dialog);
        return overlay;
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
        if (append) {
            if (template.widgets()
                .isEmpty()) {
                notice = tr("no.space");
                return;
            }
            placement = new DesktopPresetPlacement(template);
            pointPlacement(mouseX, mouseY);
            pendingPreset = null;
            clearPresetImport();
            libraryOpen = propertiesOpen = shareOpen = false;
            bindingOpen = screenBindingOpen = resourceAdding = choosingDeviceSource = false;
            notice = "";
            dirty = true;
            return;
        } else {
            cancelPlacement();
            layout = DesktopAutoBind.initialize(template);
            selected = "";
            selection.only("");
            view.reset(layout, canvas.w, canvas.h);
        }
        DesktopAutoBind.initializeAvailable(layout);
        nextDefaultBindingCheck = System.nanoTime() + 1000000000L;
        pendingPreset = null;
        clearPresetImport();
        libraryOpen = propertiesOpen = shareOpen = false;
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
                edit = true;
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
            UiNode.label("effects-glow-label", tr("effects.glow"))
                .size(width, 13));
        UiNode glow = UiNode.textInput("effects-glow", Double.toString(widget.effects.glow), value -> {})
            .size(width, 23)
            .maxLength(16)
            .style(BUTTON);
        body.add(glow);
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
                                .trim()),
                        Double.parseDouble(
                            glow.text()
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
            .bounds(x, canvas.y - area.y + 2, width, Math.max(1, canvas.h - 4))
            .style(PANEL)
            .overflowClip(true);
        panel.add(button("close-properties", tr("close"), width - 26, 5, 20, 20, () -> {
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
            syncSelection();
            for (String id : new ArrayList<>(selection.ids())) layout.remove(id);
            selected = "";
            selection.only("");
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
                    MUTED);
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
        row.add(button("binding-previous", tr("page.previous"), 0, 0, 30, 23, () -> {
            bindingPage = Math.max(0, bindingPage - 1);
            dirty = true;
        }).enabled(bindingPage > 0));
        row.add(
            UiNode.label("binding-page", (bindingPage + 1) + " / " + pages)
                .size(width - 68, 23));
        row.add(button("binding-next", tr("page.next"), 0, 0, 30, 23, () -> {
            bindingPage = Math.min(pages - 1, bindingPage + 1);
            dirty = true;
        }).enabled(bindingPage < pages - 1));
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
                transform,
                renderer);
        } finally {
            GL11.glPopMatrix();
        }
    }

    /** Geometry-only ghost: no card painter, readings, subscriptions or actionable children. */
    private static void drawPlacement(UiRenderer renderer) {
        if (placement == null) return;
        DashboardTheme theme = layout.theme();
        boolean valid = placement.valid(layout);
        int outline = valid ? theme.positive : theme.warning;
        CanvasTransform transform = view.transform(canvas.x, canvas.y);
        for (WidgetSpec widget : placement.widgets()) {
            Rect b = bounds(widget, transform);
            if (!intersects(b, canvas)) continue;
            renderer.fill(new UiRect(b.x, b.y, b.w, b.h), (theme.cardBottom & 0xFFFFFF) | 0x90000000);
            double header = Math.min(b.h, Math.max(3, 22 * view.scale));
            renderer.fill(new UiRect(b.x, b.y, b.w, header), (theme.header & 0xFFFFFF) | 0xB0000000);
            renderer.fill(new UiRect(b.x, b.y, b.w, 2), outline);
            renderer.fill(new UiRect(b.x, b.y + b.h - 2, b.w, 2), outline);
            renderer.fill(new UiRect(b.x, b.y, 2, b.h), outline);
            renderer.fill(new UiRect(b.x + b.w - 2, b.y, 2, b.h), outline);
            double inset = Math.max(4, 12 * view.scale), x = b.x + inset, y = b.y + header + inset;
            double width = Math.max(1, b.w - inset * 2), height = Math.max(1, b.h - header - inset * 2);
            int ink = (theme.accent & 0xFFFFFF) | 0x80000000;
            if (widget.kind == WidgetKind.HISTORY || widget.presentation == WidgetPresentation.BARS
                || widget.presentation == WidgetPresentation.TREND
                || widget.presentation == WidgetPresentation.COMBO) {
                for (int i = 0; i < 7; i++) {
                    double h = height * (.25 + (i * 3 % 7) / 10.0);
                    renderer.fill(new UiRect(x + width * i / 7, y + height - h, width / 10, h), ink);
                }
            } else if (widget.presentation == WidgetPresentation.GAUGE) {
                double radius = Math.max(1, Math.min(width, height) * .35);
                for (int i = 0; i < 20; i++) {
                    double angle = i * Math.PI / 10;
                    renderer.fill(
                        new UiRect(
                            x + width / 2 + Math.cos(angle) * radius,
                            y + height / 2 + Math.sin(angle) * radius,
                            Math.max(2, radius / 5),
                            Math.max(2, radius / 5)),
                        ink);
                }
            } else {
                int lines = widget.presentation == WidgetPresentation.KPI
                    || widget.presentation == WidgetPresentation.BAR ? 2 : 4;
                for (int i = 0; i < lines; i++) renderer.fill(
                    new UiRect(
                        x,
                        y + height * (i + .5) / lines,
                        width * (i == 0 ? .72 : .45),
                        Math.max(2, 4 * view.scale)),
                    ink);
            }
            if (!valid) {
                renderer.fill(new UiRect(b.x + b.w - 9, b.y + 5, 3, 8), outline);
                renderer.fill(new UiRect(b.x + b.w - 9, b.y + 15, 3, 3), outline);
            }
            if (b.w > 64 && b.h > 40 && header >= 12) {
                String kind = kindLabel(widget.kind);
                String title = !widget.title.isEmpty() ? widget.title
                    : widget.kind == WidgetKind.WIRELESS ? tr("metric." + widget.metricField) : kind;
                if (!title.equals(kind)) title += " / " + kind;
                if (font != null) title = font.trimStringToWidth(title, Math.max(1, b.w - 24));
                renderer.text(title, b.x + 6, b.y + 4, theme.text);
            }
        }
    }

    private static Rect bounds(WidgetSpec w, CanvasTransform t) {
        return DesktopCardGeometry.bounds(w, t);
    }

    private static boolean intersects(Rect a, Rect b) {
        return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
    }

    private static void selection(UiRenderer r, Rect b) {
        selectionOutline(r, b);
        if (selection.ids()
            .size() > 1 || !DesktopCardGeometry.showResizeHandles(view.scale)) return;
        for (int x : new int[] { b.x + 3, b.x + b.w / 2, b.x + b.w - 3 })
            for (int y : new int[] { b.y + 3, b.y + b.h / 2, b.y + b.h - 3 })
                if (x != b.x + b.w / 2 || y != b.y + b.h / 2) r.fill(new UiRect(x - 3, y - 3, 6, 6), ACCENT);
    }

    private static void selectionOutline(UiRenderer r, Rect b) {
        r.fill(new UiRect(b.x, b.y, b.w, 2), ACCENT);
        r.fill(new UiRect(b.x, b.y + b.h - 2, b.w, 2), ACCENT);
        r.fill(new UiRect(b.x, b.y, 2, b.h), ACCENT);
        r.fill(new UiRect(b.x + b.w - 2, b.y, 2, b.h), ACCENT);
    }
}
