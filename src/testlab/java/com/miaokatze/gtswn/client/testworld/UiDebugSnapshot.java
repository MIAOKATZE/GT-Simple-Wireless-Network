package com.miaokatze.gtswn.client.testworld;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.client.PortableMonitorClientCache;
import com.miaokatze.gtswn.client.gui.GuiQuantumTerminal;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;

/** Bounded, read-only observations of production screen objects. No synthetic screens or response data. */
final class UiDebugSnapshot {

    static final int MAX_WIDGETS = 512;

    private UiDebugSnapshot() {}

    static Map<String, Object> capture(Minecraft mc, GuiScreen screen) {
        Map<String, Object> result = map(
            "schema",
            1,
            "screenClass",
            screen.getClass()
                .getName(),
            "evidence",
            "LIVE_CLIENT_OBJECTS",
            "functionalStatus",
            "MANUAL_SERVER_ASSERTION",
            "width",
            screen.width,
            "height",
            screen.height,
            "displayWidth",
            mc.displayWidth,
            "displayHeight",
            mc.displayHeight,
            "language",
            mc.gameSettings.language,
            "guiScale",
            mc.gameSettings.guiScale);
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        result.put("effectiveGuiScale", resolution.getScaleFactor());
        result.put("logicalToScreenScaleX", resolution.getScaledWidth() / (double) Math.max(1, screen.width));
        result.put("logicalToScreenScaleY", resolution.getScaledHeight() / (double) Math.max(1, screen.height));
        List<Map<String, Object>> widgets = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, Object> state = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        widgets.add(
            map(
                "path",
                "screen",
                "parent",
                null,
                "class",
                screen.getClass()
                    .getName(),
                "kind",
                "root",
                "x",
                0,
                "y",
                0,
                "w",
                screen.width,
                "h",
                screen.height,
                "visible",
                true));
        Integer left = integer(screen, "guiLeft", "field_147003_i", "left");
        Integer top = integer(screen, "guiTop", "field_147009_r", "top");
        Integer panelW = integer(screen, "xSize", "field_146999_f", "panelWidth");
        Integer panelH = integer(screen, "ySize", "field_147000_g", "panelHeight");
        if (left != null && top != null && panelW != null && panelH != null) widgets.add(
            map(
                "path",
                "panel",
                "parent",
                "screen",
                "class",
                "layout",
                "kind",
                "panel",
                "x",
                left,
                "y",
                top,
                "w",
                panelW,
                "h",
                panelH,
                "visible",
                true));
        for (Field field : fields(screen.getClass())) {
            if (widgets.size() >= MAX_WIDGETS) break;
            Object value = get(field, screen);
            String name = field.getName();
            if (name.matches(
                "tab|page|selected|settings|settingsPage|pinManager|effectsPage|palettePage|lastKnownTab|pendingTab|showRecipe|powerFilterOpen|stateFilter|powerFilter|uiScale|pollTimer"))
                state.put(
                    name,
                    value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                        ? value
                        : value instanceof Enum<?> ? ((Enum<?>) value).name()
                            : "UNKNOWN:" + value.getClass()
                                .getName());
            if (value instanceof List<?>) {
                int index = 0;
                boolean authoritative = name.equals("buttonList") || name.equals("field_146292_n")
                    || name.equals("textFields")
                    || name.equals("fields");
                for (Object child : (List<?>) value) {
                    if (widgets.size() >= MAX_WIDGETS || index >= MAX_WIDGETS) break;
                    if (!name.equals("powerFilterOptions") || Boolean.TRUE.equals(read(screen, "powerFilterOpen")))
                        add(mc, widgets, seen, child, name + "[" + index + "]", authoritative);
                    index++;
                }
            } else if (value instanceof GuiTextField || value instanceof GuiButton) {
                add(mc, widgets, seen, value, name, false);
            } else if (value != null && value.getClass()
                .getName()
                .matches(".*Gui(AEMonitor|DeviceEntry)List")) {
                    Integer x = integer(value, "listLeft"), y = integer(value, "listTop");
                    Integer w = integer(value, "listWidth"), h = integer(value, "listHeight");
                    widgets.add(
                        map(
                            "path",
                            name,
                            "parent",
                            "screen",
                            "class",
                            value.getClass()
                                .getName(),
                            "kind",
                            "clipped-list",
                            "x",
                            x,
                            "y",
                            y,
                            "w",
                            w,
                            "h",
                            h,
                            "visible",
                            true,
                            "scrollOffset",
                            read(value, "scrollOffset"),
                            "customHitAreas",
                            "MANUAL: row actions and scrollbar are custom painted; viewport only"));
                }
        }
        if (screen instanceof GuiContainer) {
            GuiContainer container = (GuiContainer) screen;
            result.put(
                "container",
                map(
                    "class",
                    container.inventorySlots.getClass()
                        .getName(),
                    "windowId",
                    container.inventorySlots.windowId,
                    "serverAuthorization",
                    container.inventorySlots.windowId > 0 ? "SERVER_OPEN_WINDOW_OBSERVED" : "NOT_CONFIRMED"));
            if (left != null && top != null) for (Object item : container.inventorySlots.inventorySlots) {
                if (widgets.size() >= MAX_WIDGETS) break;
                Slot slot = (Slot) item;
                // Slots belong to the screen viewport; vanilla containers may legally place them outside their panel.
                widgets.add(
                    map(
                        "path",
                        "slot[" + slot.slotNumber + "]",
                        "parent",
                        "screen",
                        "class",
                        slot.getClass()
                            .getName(),
                        "kind",
                        "slot",
                        "id",
                        slot.slotNumber,
                        "x",
                        left + slot.xDisplayPosition,
                        "y",
                        top + slot.yDisplayPosition,
                        "w",
                        16,
                        "h",
                        16,
                        "visible",
                        true));
            }
        }
        List<Map<String, Object>> active = new ArrayList<>();
        for (Map<String, Object> widget : widgets) {
            if (!Boolean.TRUE.equals(widget.get("visible"))) continue;
            if (!(widget.get("x") instanceof Number) || !(widget.get("y") instanceof Number)
                || !(widget.get("w") instanceof Number)
                || !(widget.get("h") instanceof Number)) {
                findings.add(finding("WARNING", "BOUNDS_UNKNOWN", widget, null));
                continue;
            }
            int x = number(widget, "x"), y = number(widget, "y"), w = number(widget, "w"), h = number(widget, "h");
            if (w <= 0 || h <= 0) findings.add(finding("FAIL", "NON_POSITIVE_BOUNDS", widget, null));
            if (x < 0 || y < 0 || x + w > screen.width || y + h > screen.height)
                findings.add(finding("FAIL", "OUT_OF_VIEWPORT", widget, null));
            if ("button".equals(widget.get("kind"))) {
                active.add(widget);
                if (number(widget, "textWidth") > w - 4)
                    findings.add(finding("WARNING", "LABEL_EXCEEDS_CONTROL: custom renderer may trim", widget, null));
                if (w < 12 || h < 12) findings.add(finding("WARNING", "SMALL_HIT_AREA", widget, null));
            }
        }
        for (int a = 0; a < active.size(); a++) for (int b = a + 1; b < active.size(); b++) {
            Map<String, Object> first = active.get(a), second = active.get(b);
            if (intersects(first, second))
                findings.add(finding("WARNING", "OVERLAP: inspect intentional overlay", first, second));
        }
        result.put("state", state);
        result.put("dataObservation", dataObservation(mc, screen));
        result.put("widgets", widgets);
        result.put("findings", findings);
        result.put("truncated", widgets.size() >= MAX_WIDGETS);
        result.put(
            "limitations",
            "Button labels use actual font widths. Custom painted text, row hit areas, clipping pixels, tooltips and server effects require PNG/manual review. Auxiliary fields have UNKNOWN render visibility and are excluded from failures. Overlays are warnings, not failures. Window resizing is NOT_RUN; current framebuffer is preserved.");
        return result;
    }

    /** Null means an actual response is available; empty data sets remain valid responses. */
    static String pendingDataReason(Minecraft mc, GuiScreen screen, String preset) {
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (preset.startsWith("panel")) {
            Object value = read(screen, "panel");
            if (!(value instanceof TileEntityNetworkInfoPanel)) return "Information-panel tile not attached";
            TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) value;
            int expected = preset.equals("panel-ae-chart") ? 1 : preset.equals("panel-ae-list") ? 2 : 0;
            int expectedX = expected + 2;
            if (panel.getWorldObj() != mc.theWorld || panel.xCoord != expectedX
                || panel.yCoord != 65
                || panel.zCoord != 1
                || mc.theWorld.getTileEntity(expectedX, 65, 1) != panel)
                return "Waiting for production GUI attached to the expected live fixture tile";
            return "GTSWN isolated UI display".equals(panel.getOwnerName()) && panel.getCurrentTab() == expected ? null
                : "Waiting for real tile description sync (fixture owner display name and expected tab; name is not authorization); samples may legitimately be empty";
        }
        if (preset.equals("monitor") || preset.startsWith("cover-") || preset.equals("link")) {
            return screen instanceof GuiContainer && ((GuiContainer) screen).inventorySlots.windowId > 0
                && mc.thePlayer.openContainer == ((GuiContainer) screen).inventorySlots ? null
                    : "Waiting for authoritative native MUI2 container window";
        }
        if (held == null) return "Expected held terminal is not synchronized";
        if (held.getItem() instanceof PortableWirelessNetworkMonitor) {
            PacketSyncPortableMonitor packet = PortableMonitorClientCache.snapshot(held);
            if (packet == null || !packet.valid || PortableMonitorClientCache.stale(held))
                return "Waiting for actual portable server snapshot";
            Object selectedValue = read(screen, "selected");
            int selected = selectedValue instanceof Number ? ((Number) selectedValue).intValue() : -1;
            if (selected >= 0 && selected < packet.panels.size() && !packet.panels.get(selected).detail)
                return "Waiting for selected portable panel detail response (normal production polling throttle applies)";
            return null;
        }
        if (held.getItem() instanceof ItemDeviceInfoTerminal) {
            java.util.UUID id = ItemDeviceInfoTerminal.readTerminalId(held);
            Object anchor = read(screen, "anchor");
            if (anchor instanceof java.util.UUID) id = (java.util.UUID) anchor;
            return id != null && DeviceTerminalClientCache.getSnapshot(id) != null
                && DeviceTerminalClientCache.isFresh(id, System.currentTimeMillis()) ? null
                    : "Waiting for actual device server version/received snapshot; zero entries is valid";
        }
        if (held.getItem() instanceof ItemNetworkQuantumTerminal
            && (UiModularSnapshot.supports(screen) || screen.getClass()
                .getSimpleName()
                .equals("GuiQuantumTerminal"))) {
            if (screen instanceof GuiContainer && (((GuiContainer) screen).inventorySlots.windowId <= 0
                || mc.thePlayer.openContainer != ((GuiContainer) screen).inventorySlots))
                return "Waiting for server-authorized quantum container window context";
            QuantumNetworkData data = quantumData();
            int[] anchor = ItemNetworkQuantumTerminal.getAnchor(held);
            return anchor != null && data != null
                && data.anchorDim == anchor[0]
                && data.anchorX == anchor[1]
                && data.anchorY == anchor[2]
                && data.anchorZ == anchor[3] ? null
                    : "Waiting for actual quantum response matching current held terminal anchor";
        }
        return "No server-response readiness adapter for this GUI";
    }

    private static QuantumNetworkData quantumData() {
        try {
            Field field = GuiQuantumTerminal.class.getDeclaredField("latestData");
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof QuantumNetworkData ? (QuantumNetworkData) value : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    static Map<String, Object> dataObservation(Minecraft mc, GuiScreen screen) {
        Object tile = read(screen, "panel");
        if (tile instanceof TileEntityNetworkInfoPanel) {
            TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
            return map(
                "status",
                panel.getOwnerName()
                    .isEmpty() ? "WAITING_FOR_TILE_DESCRIPTION" : "TILE_DESCRIPTION_SYNC_OBSERVED",
                "tab",
                panel.getCurrentTab(),
                "ownerNameReceived",
                !panel.getOwnerName()
                    .isEmpty(),
                "sampleStatus",
                "NOT_ASSERTED: configured network may legitimately be empty or disconnected");
        }
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (held == null) return map("status", "NO_HELD_TERMINAL", "serverEffects", "NOT_VERIFIED");
        if (held.getItem() instanceof PortableWirelessNetworkMonitor) {
            PacketSyncPortableMonitor packet = PortableMonitorClientCache.snapshot(held);
            List<Map<String, Object>> panels = new ArrayList<>();
            if (packet != null) for (PacketSyncPortableMonitor.Panel panel : packet.panels) panels.add(
                map(
                    "tab",
                    panel.tab,
                    "state",
                    panel.state,
                    "rows",
                    panel.rows.size(),
                    "aeConnected",
                    panel.aeConnected));
            return map(
                "status",
                packet == null ? "NO_SERVER_SNAPSHOT" : "SERVER_SNAPSHOT_OBSERVED",
                "valid",
                packet != null && packet.valid,
                "stale",
                PortableMonitorClientCache.stale(held),
                "panelCount",
                panels.size(),
                "panels",
                panels,
                "source",
                "CURRENT_OPERATOR_READ_ONLY");
        }
        if (held.getItem() instanceof ItemDeviceInfoTerminal) {
            DeviceTerminalClientCache.Snapshot snapshot = DeviceTerminalClientCache
                .getSnapshot(ItemDeviceInfoTerminal.readTerminalId(held));
            return map(
                "status",
                snapshot == null ? "NO_SERVER_SNAPSHOT" : "SERVER_SNAPSHOT_OBSERVED",
                "entries",
                snapshot == null ? 0 : snapshot.entries.size(),
                "version",
                snapshot == null ? null : snapshot.version);
        }
        if (held.getItem() instanceof ItemNetworkQuantumTerminal
            && (UiModularSnapshot.supports(screen) || screen.getClass()
                .getSimpleName()
                .equals("GuiQuantumTerminal"))) {
            QuantumNetworkData snapshot = quantumData();
            return map(
                "status",
                snapshot == null ? "NO_SERVER_SNAPSHOT" : "SERVER_SNAPSHOT_OBSERVED",
                "bound",
                ItemNetworkQuantumTerminal.isBound(held),
                "online",
                snapshot != null && snapshot.online,
                "nodeCount",
                snapshot == null ? null : snapshot.quantumNodeCount,
                "displayNameLength",
                held.getDisplayName()
                    .length());
        }
        return map("status", "NATIVE_GUI_STATE_ONLY", "serverEffects", "NOT_VERIFIED");
    }

    private static void add(Minecraft mc, List<Map<String, Object>> widgets, Set<Object> seen, Object value,
        String path, boolean authoritative) {
        if (!(value instanceof GuiButton) && !(value instanceof GuiTextField) || !seen.add(value)) return;
        Map<String, Object> widget;
        if (value instanceof GuiButton) {
            GuiButton button = (GuiButton) value;
            widget = map(
                "kind",
                "button",
                "id",
                button.id,
                "x",
                button.xPosition,
                "y",
                button.yPosition,
                "w",
                button.width,
                "h",
                button.height,
                "label",
                button.displayString,
                "enabled",
                button.enabled,
                "visible",
                authoritative ? button.visible : "UNKNOWN",
                "textWidth",
                mc.fontRenderer.getStringWidth(button.displayString));
        } else {
            GuiTextField field = (GuiTextField) value;
            widget = map(
                "kind",
                "text-field",
                "x",
                field.xPosition,
                "y",
                field.yPosition,
                "w",
                integer(field, "width", "field_146218_h"),
                "h",
                integer(field, "height", "field_146219_i"),
                "label",
                field.getText(),
                "focused",
                field.isFocused(),
                "cursor",
                read(field, "cursorPosition", "field_146224_r"),
                "selectionEnd",
                read(field, "selectionEnd", "field_146223_s"),
                "maxLength",
                read(field, "maxStringLength", "field_146217_k"),
                "enabled",
                read(field, "isEnabled", "field_146226_p"),
                "visible",
                authoritative ? field.getVisible() : "UNKNOWN",
                "textWidth",
                mc.fontRenderer.getStringWidth(field.getText()));
        }
        widget.put(
            "class",
            value.getClass()
                .getName());
        widget.put("path", path);
        widget.put("parent", "screen");
        widgets.add(widget);
    }

    static List<GuiButton> buttons(GuiScreen screen) {
        List<GuiButton> result = new ArrayList<>();
        Object value = read(screen, "buttonList", "field_146292_n");
        if (value instanceof List<?>) for (Object item : (List<?>) value)
            if (item instanceof GuiButton && result.size() < MAX_WIDGETS) result.add((GuiButton) item);
        return result;
    }

    static List<Field> fields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass())
            for (Field field : current.getDeclaredFields())
                if (!Modifier.isStatic(field.getModifiers())) result.add(field);
        return result;
    }

    private static Object get(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    static Object read(Object owner, String... names) {
        if (owner instanceof GuiScreen && UiModularSnapshot.supports((GuiScreen) owner))
            owner = UiModularSnapshot.wrappedScreen((GuiScreen) owner);
        if (owner == null) return null;
        for (String name : names) for (Field field : fields(owner.getClass())) if (field.getName()
            .equals(name)) return get(field, owner);
        return null;
    }

    private static Integer integer(Object owner, String... names) {
        Object value = read(owner, names);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }

    private static int number(Map<String, Object> value, String key) {
        return ((Number) value.get(key)).intValue();
    }

    private static Map<String, Object> finding(String severity, String code, Map<String, Object> first,
        Map<String, Object> second) {
        return map(
            "severity",
            severity,
            "code",
            code,
            "widget",
            first.get("path"),
            "other",
            second == null ? null : second.get("path"));
    }

    private static boolean intersects(Map<String, Object> a, Map<String, Object> b) {
        return number(a, "x") < number(b, "x") + number(b, "w") && number(b, "x") < number(a, "x") + number(a, "w")
            && number(a, "y") < number(b, "y") + number(b, "h")
            && number(b, "y") < number(a, "y") + number(a, "h");
    }
}
