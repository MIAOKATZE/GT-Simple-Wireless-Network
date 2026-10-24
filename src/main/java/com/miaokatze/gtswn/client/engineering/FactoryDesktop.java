package com.miaokatze.gtswn.client.engineering;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDeviceSources;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDrag;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopLayout;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopStore;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopViewport;
import com.miaokatze.gtswn.client.engineering.desktop.DeviceBinding;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
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

    private static final int BACK = 0xFF15202A, GRID = 0xFF24333E, TEXT = 0xFFE8ECE8, ACCENT = 0xFF75C3B7;
    private static final UiStyle PANEL = new UiStyle(new UiStyle.Paint(0xFF1D2D38, 0xFF344955, TEXT));
    private static final UiStyle BUTTON = new UiStyle(
        new UiStyle.Paint(0xFF2B414E, 0xFF3C5563, TEXT),
        new UiStyle.Paint(0xFF3B5866, ACCENT, TEXT),
        new UiStyle.Paint(0xFF1B353F, ACCENT, TEXT),
        new UiStyle.Paint(0xFF376359, ACCENT, TEXT),
        new UiStyle.Paint(0xFF26343C, 0xFF344955, 0xFF9AAEB8));
    private static DesktopStore store;
    private static DesktopLayout layout;
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
    private static final DesktopEditorScroll editorScroll = new DesktopEditorScroll();
    private static boolean edit, canvasFocus, panning, dirty = true;
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
        selected = "";
        edit = canvasFocus = panning = false;
        drag.cancel();
        dirty = true;
    }

    static void tick(boolean visible) {
        ensure();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || !mc.inGameHasFocus) {
            FactoryDashboard.blur();
            FactoryChrome.blur();
        }
        if (visible) {
            DesktopDeviceSources.setVisibleBindings(visibleDevices);
            DesktopDeviceSources.tick();
        } else {
            DesktopDeviceSources.release();
            visibleDevices.clear();
        }
        store.saveIfDue(System.nanoTime());
    }

    static void flush() {
        if (drag.active() || panning) return;
        if (store != null && layout != null) {
            store.put(profile, layout);
            store.putView(profile, view);
            store.flush();
        }
    }

    private static void changed(boolean structure) {
        if (drag.active() || panning) return;
        store.put(profile, layout);
        store.putView(profile, view);
        dirty |= structure;
    }

    static boolean mount(UUID source, Entry entry) {
        DeviceBinding binding = DesktopDeviceSources.binding(source, entry);
        if (binding == null) return false;
        EngineeringModeController.open();
        if (!EngineeringModeController.isActive()) return false;
        ensure();
        WidgetSpec widget = layout.addDevice(binding);
        if (widget == null) return false;
        selected = widget.id;
        view.panX = widget.x() - 18 / view.scale;
        view.panY = widget.y() - 18 / view.scale;
        edit = true;
        changed(true);
        WorkbenchHud.openDashboard();
        return true;
    }

    static String label(WidgetSpec widget) {
        if (!widget.title.isEmpty()) return widget.title;
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
        boolean used;
        if (wheel != 0) used = scene.scroll(x, y, canvas.contains(x, y) ? wheel : -Math.signum(wheel) * 24);
        else if (button >= 0) used = pressed ? scene.pointerDown(x, y, button) : scene.pointerUp(x, y, button);
        else used = scene.pointerMove(x, y) || pointerCaptured();
        return used || area.contains(x, y);
    }

    static boolean key(int code, char character, boolean pressed) {
        if (scene == null || code == Keyboard.KEY_ESCAPE) return false;
        if ((drag.active() || panning) && (code == Keyboard.KEY_DELETE || code == Keyboard.KEY_TAB)) return true;
        if (pressed && scene.keyTyped(
            character,
            code,
            Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT),
            Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))) return true;
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

    static boolean pointerCaptured() {
        return scene != null && scene.captured() != null;
    }

    private static WidgetSpec hit(double x, double y) {
        CanvasTransform transform = view.transform(canvas.x, canvas.y);
        double sx = transform.toScreenX(x), sy = transform.toScreenY(y);
        WidgetSpec current = layout.find(selected);
        if (edit && current != null
            && DesktopCardGeometry
                .hit(bounds(current, transform), sx, sy, DesktopCardGeometry.showResizeHandles(view.scale)))
            return current;
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
        if (e.button == 0 && edit && target != null) {
            boolean was = selected.equals(target.id);
            selected = target.id;
            dirty = true;
            if (!DesktopCardGeometry.showResizeHandles(view.scale)) {
                e.consume();
                return;
            }
            Rect r = bounds(target, view.transform(canvas.x, canvas.y));
            DesktopDrag.Edge edge = was ? edge(r, e.x, e.y) : DesktopDrag.Edge.MOVE;
            if (edge != DesktopDrag.Edge.MOVE || e.y < r.y + 28
                || e.x < r.x + 10
                || e.x >= r.x + r.w - 10
                || e.y >= r.y + r.h - 10) {
                drag.begin(layout, selected, edge, x, y);
                e.capturePointer();
            }
            e.consume();
            return;
        }
        if (e.button == 0 && target != null && view.scale >= .65) FactoryDashboard.widgetClick((int) e.x, (int) e.y);
        e.consume();
    }

    private static void move(UiEvent e) {
        if (panning) {
            view.pan(e.x - lastX, e.y - lastY);
            lastX = e.x;
            lastY = e.y;
        }
        if (drag.active()) drag.update(view.toLocalX(e.x, canvas.x), view.toLocalY(e.y, canvas.y));
        if (panning || drag.active()) e.consume();
    }

    private static void up(UiEvent e) {
        move(e);
        if (panning || drag.active()) {
            panning = false;
            drag.finish();
            changed(true);
            e.consume();
        }
    }

    private static void cancelGesture() {
        if (!drag.active() && !panning) return;
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
            UiNode title = scene.find("title-" + widget.id);
            int reserved = DesktopCardGeometry.headerReserved(widget);
            title.text(label(widget))
                .size(Math.max(1, b.w - reserved), 27)
                .visible(view.scale >= .65);
            scene.find("selection-" + widget.id)
                .size(b.w, b.h)
                .visible(edit && selected.equals(widget.id));
        }
        scene.layout(width, height);
        Minecraft mc = Minecraft.getMinecraft();
        scene.render(new MinecraftUiRenderer(f, width, height, mc.displayWidth, mc.displayHeight));
    }

    private static void build() {
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
        shell.add(
            UiNode.label("desktop-title", tr("title"))
                .bounds(12, 11, Math.max(40, area.w - 310), 12));
        shell.add(button("edit", tr(edit ? "done" : "edit"), area.w - 242, 5, 66, 22, () -> {
            edit = !edit;
            canvasFocus = false;
            changed(true);
        }));
        zoomLabel = button("zoom", "100%", area.w - 171, 5, 50, 22, () -> {
            WidgetSpec target = layout.find(selected);
            if (target != null) DesktopCardGeometry.focusAtOne(view, target, canvas);
            else view.zoomAt(1 / view.scale, canvas.x + canvas.w / 2.0, canvas.y + canvas.h / 2.0, canvas.x, canvas.y);
            changed(false);
        });
        shell.add(zoomLabel);
        shell.add(button("origin", tr("home"), area.w - 116, 5, 50, 22, () -> {
            view.scale = 1;
            view.panX = view.panY = -16;
            changed(false);
        }));
        shell.add(button("fit", tr("fit"), area.w - 61, 5, 50, 22, () -> {
            view.fit(layout, canvas.w, canvas.h);
            changed(false);
        }));
        int left = edit ? Math.min(126, Math.max(94, area.w / 6)) : 0,
            right = edit ? Math.min(204, Math.max(150, area.w / 4)) : 0;
        canvas = new Rect(area.x + left, area.y + 33, Math.max(1, area.w - left - right), Math.max(1, area.h - 53));
        UiNode canvasNode = UiNode.panel("canvas")
            .bounds(left, 33, canvas.w, canvas.h)
            .overflowClip(true)
            .style(new UiStyle(new UiStyle.Paint(BACK, 0, TEXT)))
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
                .style(new UiStyle(new UiStyle.Paint(0xFFF0F0E8, 0xFFD0DAD8, 0xFF253C48)))
                .onDraw((node, renderer) -> drawWidget(layout.find(widget.id), node, renderer));
            card.add(
                UiNode.label("title-" + widget.id, label(widget))
                    .bounds(0, 0, widget.width(), 27)
                    .style(new UiStyle(new UiStyle.Paint(0xFFE0E7E5, 0xFFD1D9D9, 0xFF253C48))));
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
        if (edit) {
            shell.add(library(left));
            shell.add(properties(left + canvas.w, right));
        }
        statusLabel = UiNode.label("desktop-hint", tr(edit ? "hint.edit" : "hint.browse"))
            .bounds(12, area.h - 15, area.w - 24, 12);
        shell.add(statusLabel);
        scene = new UiScene(root);
        scene.layout(screenWidth, screenHeight);
        editorScroll.restore(scene);
        dirty = false;
    }

    private static UiNode button(String id, String label, double x, double y, double w, double h, Runnable action) {
        return UiNode.button(id, label, action)
            .bounds(x, y, w, h)
            .style(BUTTON);
    }

    private static UiNode library(int width) {
        UiNode panel = UiNode.panel("library")
            .bounds(0, 33, width, canvas.h)
            .style(PANEL)
            .overflowClip(true);
        panel.add(
            UiNode.label("library-title", tr("library"))
                .bounds(10, 12, width - 20, 12));
        UiNode list = UiNode.panel("library-list")
            .bounds(6, 34, width - 12, Math.max(1, canvas.h - 42))
            .layout(UiNode.Layout.COLUMN)
            .gap(6)
            .scrollable(true)
            .style(new UiStyle(new UiStyle.Paint(0, 0, TEXT)));
        for (WidgetKind kind : WidgetKind.values())
            if (kind != WidgetKind.DEVICE) list.add(UiNode.button("add-" + kind, "+ " + kindLabel(kind), () -> {
                WidgetSpec added = layout.add(kind);
                if (added == null) return;
                selected = added.id;
                view.panX = added.x() - 18 / view.scale;
                view.panY = added.y() - 18 / view.scale;
                changed(true);
            })
                .size(width - 12, 25)
                .style(BUTTON));
        list.add(
            UiNode.button("add-device", tr("device.add"), WorkbenchHud::openDevices)
                .size(width - 12, 28)
                .style(BUTTON));
        panel.add(list);
        return panel;
    }

    private static UiNode properties(int x, int width) {
        UiNode panel = UiNode.panel("properties")
            .bounds(x, 33, width, canvas.h)
            .style(PANEL)
            .overflowClip(true);
        panel.add(
            UiNode.label("properties-title", tr("properties"))
                .bounds(10, 12, width - 20, 12));
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
        body.add(UiNode.textInput("custom-title", widget.title, value -> {
            WidgetSpec current = layout.find(selected);
            if (current != null && layout.replace(current.titled(value))) changed(false);
        })
            .size(width - 16, 23)
            .maxLength(64)
            .style(BUTTON));
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
        double firstX = canvas.x - view.panX * view.scale % step, firstY = canvas.y - view.panY * view.scale % step;
        for (double x = firstX; x < canvas.x + canvas.w; x += step)
            renderer.fill(new UiRect(x, canvas.y, 1, canvas.h), GRID);
        for (double y = firstY; y < canvas.y + canvas.h; y += step)
            renderer.fill(new UiRect(canvas.x, y, canvas.w, 1), GRID);
        CanvasTransform transform = view.transform(canvas.x, canvas.y);
        for (WidgetSpec widget : layout.widgets()) {
            Rect b = bounds(widget, transform);
            if (intersects(b, canvas)) renderer.fill(new UiRect(b.x + 2, b.y + 3, b.w, b.h), 0x50000000);
        }
    }

    private static void drawWidget(WidgetSpec widget, UiNode node, UiRenderer renderer) {
        if (widget == null) return;
        UiRect b = node.bounds();
        Rect r = new Rect((int) b.x, (int) b.y, (int) b.width, (int) b.height);
        if (view.scale < .65) {
            renderer.fill(b, 0xFF304753);
            if (r.w > 45 && r.h > 16) renderer.text(label(widget), r.x + 6, r.y + 6, TEXT);
        } else FactoryDashboard.paintWidget(font, widget, r, mouseX, mouseY);
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
