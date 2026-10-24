package com.miaokatze.gtswn.client.engineering;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.DevicePowerFilter;
import com.miaokatze.gtswn.client.TerminalBaubleController;
import com.miaokatze.gtswn.client.TerminalKeyNames;
import com.miaokatze.gtswn.client.engineering.FactoryControlSettings.Preset;
import com.miaokatze.gtswn.client.engineering.FactoryControlSettings.Setting;
import com.miaokatze.gtswn.client.engineering.FactoryShortcuts.Action;
import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalActions;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.client.gui.TerminalPresentation;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Node;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;
import com.miaokatze.gtswn.common.engineering.NodePresentation;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Factory workbench: Outpost's monitor rail, power card and dashboard stay inside the world view. */
final class WorkbenchHud extends Gui {

    private static final int BACK = 0xFF1C2A35, HEADER = 0xFF344A59, TEXT = 0xFFE7ECEA;
    private static final int CHROME = 0xFF1C2A35, CHROME_SELECTED = 0xFF344A59;
    private static final int MUTED = 0xFF91A6B2, ACCENT = 0xFF69D8CA, WARN = 0xFFF0BB70, STOPPED = 0xFFEE827E;
    private static final ResourceLocation ATLAS = new ResourceLocation("gtswn", "textures/gui/factory_atlas.png");
    private static final WorkbenchHud PAINTER = new WorkbenchHud();
    private static final int[] MODE_ICONS = { 48, 0, 24, 12, 84 }, TOOL_ICONS = { 12, 36, 60, 24, 72 };
    private static boolean sidebarOpen, railOpen, powerOpen, dashboardMode = true, alertsOpen = true;
    private static int task;
    private static SelectedMachineRecord selectedRecord;
    private static final ScrollModel sidebarModel = new ScrollModel(), detailModel = new ScrollModel(),
        railModel = new ScrollModel();
    private static Rect detailRect;
    private static boolean inspectorFullscreen;
    private static int inspectorHitStart;
    private static final FactoryTextSearch search = new FactoryTextSearch();
    private static Rect searchRect;
    private static String builtSearch = "";
    private static int focusRegion;
    private static int keyRow, railVisible;
    private static final List<ScrollHit> scrollBars = new ArrayList<>();
    private static ScrollHit scrollDrag;
    private static long rowsRevision = Long.MIN_VALUE;
    private static java.util.UUID rowsSource;
    private static Object rowsSnapshot;
    private static boolean rowsFresh;
    private static int rowsFilter = -1, rowsPower = -1, ownersRevision;
    private static int builtOwners = -1;
    private static long countsRevision = Long.MIN_VALUE;
    private static java.util.UUID countsSource;
    private static boolean countsFresh;
    private static int countsFilter = -1, countsPower, cachedFiltered, cachedWarnings;
    private static EngineeringSnapshot countedNodes;
    private static int countedOwners = -1, cachedNodeRows;
    private static int selectedSlot = -1, page, sidebarScroll, railScroll, detailScroll;
    private static boolean settingsOpen;
    private static int settingsPage, settingsScroll;
    private static final List<Row> rows = new ArrayList<>();
    private static final List<Hit> controls = new ArrayList<>();
    private static final Set<String> collapsedOwners = new HashSet<>();
    private static List<String> tooltip;
    private static FactoryMachineFilter machineFilter = FactoryMachineFilter.ALL;
    private static int powerFilter;
    private static boolean mouseDispatch;
    private static final Action[] MODES = { Action.DASHBOARD, Action.CAMERA, Action.RETURN, Action.FOLLOW,
        Action.TOGGLE };
    private static final Action[] TOOLS = { Action.MONITOR, Action.POWER, Action.MAP, Action.REFRESH, Action.ALERTS };

    private WorkbenchHud() {}

    static void reset() {
        sidebarOpen = false;
        dashboardMode = true;
        task = 0;
        selectedRecord = null;
        search.focused = false;
        search.value = "";
        detailRect = null;
        rowsRevision = Long.MIN_VALUE;
        FactoryDashboard.clear();
        settingsOpen = false;
        railOpen = powerOpen = false;
        alertsOpen = true;
        selectedSlot = -1;
        machineFilter = FactoryMachineFilter.ALL;
        powerFilter = DevicePowerFilter.ALL;
        page = sidebarScroll = railScroll = detailScroll = 0;
        settingsPage = settingsScroll = 0;
        rows.clear();
        controls.clear();
        scrollBars.clear();
        scrollDrag = null;
        collapsedOwners.clear();
    }

    static void rememberPreferences() {
        FactoryDashboard.remember();
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.TASK, task);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.SIDEBAR, sidebarOpen);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.RAIL, railOpen);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.POWER, powerOpen);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.DASHBOARD, dashboardMode);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.ALERTS, alertsOpen);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.SETTINGS, settingsOpen);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.SELECTED_SLOT, selectedSlot);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.PAGE, page);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.SIDEBAR_SCROLL, sidebarScroll);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.RAIL_SCROLL, railScroll);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.DETAIL_SCROLL, detailScroll);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.SETTINGS_PAGE, settingsPage);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.SETTINGS_SCROLL, settingsScroll);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.MACHINE_FILTER, machineFilter.ordinal());
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.POWER_FILTER, powerFilter);
        String profile = EngineeringModeController.preferenceProfile();
        if (!profile.isEmpty()) FactoryDisplayPreferences
            .text("owners_" + profile, String.join(";", new java.util.TreeSet<>(collapsedOwners)));
    }

    static void restorePreferences() {
        FactoryDashboard.restore();
        task = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.TASK);
        sidebarOpen = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.SIDEBAR);
        railOpen = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.RAIL);
        powerOpen = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.POWER);
        dashboardMode = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.DASHBOARD);
        if (task == 0) dashboardMode = true;
        alertsOpen = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.ALERTS);
        settingsOpen = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.SETTINGS);
        selectedSlot = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.SELECTED_SLOT);
        page = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.PAGE);
        sidebarScroll = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.SIDEBAR_SCROLL);
        railScroll = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.RAIL_SCROLL);
        detailScroll = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.DETAIL_SCROLL);
        settingsPage = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.SETTINGS_PAGE);
        settingsScroll = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.SETTINGS_SCROLL);
        machineFilter = FactoryMachineFilter.values()[FactoryDisplayPreferences
            .integer(FactoryDisplayPreferences.Key.MACHINE_FILTER)];
        powerFilter = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.POWER_FILTER);
        collapsedOwners.clear();
        String profile = EngineeringModeController.preferenceProfile();
        if (!profile.isEmpty()) for (String owner : FactoryDisplayPreferences.text("owners_" + profile)
            .split(";")) if (!owner.isEmpty() && collapsedOwners.size() < 1024) collapsedOwners.add(owner);
    }

    static void select(int slot) {
        leaveCamera();
        closeSettings();
        selectedSlot = slot;
        sidebarOpen = true;
        page = sidebarScroll = 0;
    }

    static WorkbenchLayout layout(int width, int height) {
        EngineeringSnapshot snapshot = EngineeringDashboardCache.snapshot();
        EngineeringTerminalHudSnapshot data = EngineeringTerminalHudSnapshot
            .capture(Minecraft.getMinecraft().thePlayer);
        boolean nodes = task == 3 || EngineeringHud.activeSlot() == 1 || EngineeringHud.activeSlot() >= 3;
        int count = nodes ? visibleNodeRows(snapshot) : filteredCount(data.device);
        int railHeight = Math.max(126, (nodes ? 69 : 96) + Math.min(8, count) * 23) + (EngineeringHud.targetLines()
            .isEmpty() ? 0 : 94);
        int powerHeight = Math.min(108, 42 + Math.max(3, data.portable.basicLines.size() + 1) * 13);
        boolean camera = EngineeringModeController.INSTANCE.cameraPanel();
        return new WorkbenchLayout(
            width,
            height,
            sidebarOpen || settingsOpen,
            railOpen && !camera,
            powerOpen && !camera,
            dashboardMode,
            railHeight,
            powerHeight,
            alertsOpen && !camera && warningCount(data.device) > 0,
            true,
            task >= 2,
            selectedRecord != null || !EngineeringHud.targetLines()
                .isEmpty());
    }

    static boolean escape() {
        if (FactoryChrome.escape()) return true;
        if (inspectorFullscreen && (selectedRecord != null || !EngineeringHud.targetLines()
            .isEmpty())) {
            selectedRecord = null;
            EngineeringHud.clearTarget();
            return true;
        }
        if (settingsOpen) {
            closeSettings();
            return true;
        }
        if (dashboardMode) {
            if (FactoryDashboard.escape()) return true;
            switchTask(1);
            return true;
        }
        if (sidebarOpen) {
            sidebarOpen = false;
            return true;
        }
        if (EngineeringModeController.INSTANCE.cameraPanel()) {
            EngineeringModeController.INSTANCE.toggleCameraPanel();
            return true;
        }
        if (EngineeringModeController.INSTANCE.xrayEnabled()) {
            EngineeringModeController.INSTANCE.toggleXray();
            return true;
        }
        return false;
    }

    private static void closeSettings() {
        settingsOpen = false;
        FactoryShortcuts.cancelBinding();
        controls.clear();
    }

    private static void toggleSettings() {
        if (settingsOpen) closeSettings();
        else {
            leaveCamera();
            settingsOpen = true;
        }
        controls.clear();
    }

    static void inspect() {
        if (dashboardMode) switchTask(2);
        railOpen = true;
        detailScroll = 0;
    }

    static void render(int width, int height, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer f = mc.fontRenderer;
        WorkbenchLayout l = layout(width, height);
        tooltip = null;
        controls.clear();
        scrollBars.clear();
        ItemStack[] equipped = TerminalBaubles.terminals(mc.thePlayer);
        for (int i = 0; i < 8; i++) {
            Rect r = l.terminals[i];
            if (r.w == 0) continue;
            Source actual = EngineeringTerminalSources.resolve(mc.thePlayer, i);
            boolean present = actual.present();
            boolean active = selectedSlot == i && sidebarOpen && !settingsOpen;
            box(r, active ? CHROME_SELECTED : CHROME);
            drawRect(r.x, r.y, r.x + 2, r.y + r.h, active ? ACCENT : MUTED);
            ItemStack stack = present ? actual.stack : TerminalPresentation.placeholder(i);
            TerminalPresentation.drawIcon(mc, f, stack, r.x + 5, r.y + 5, present, 18);
            if (r.contains(mouseX, mouseY)) {
                tooltip = new ArrayList<>();
                tooltip.add(stack.getDisplayName());
                tooltip.add(tr("slot") + " " + (i + 1));
                tooltip.add(tr(active ? "state.on" : "state.off"));
                tooltip.add(TerminalPresentation.identity(actual.stack, i));
                tooltip.add(originLabel(actual.origin));
                if (!present) tooltip.add(tr("empty.slot"));
                bindingTooltip(tooltip, terminalAction(i));
            }
        }
        FactoryChrome.render(
            f,
            width,
            height,
            l,
            task,
            dashboardMode,
            sidebarOpen,
            settingsOpen,
            WorkbenchHud::shortcut,
            WorkbenchHud::switchTask,
            () -> {
                sidebarOpen = !sidebarOpen;
                if (sidebarOpen && selectedSlot < 0)
                    selectedSlot = EngineeringHud.activeSlot() < 0 ? 2 : EngineeringHud.activeSlot();
            },
            WorkbenchHud::toggleSettings);
        String[] toolKeys = { "monitor", "power", "map", "refresh", "alerts" };
        for (int i = 0; i < l.tools.length; i++) icon(
            l.tools[i],
            TOOL_ICONS[i],
            tr(toolKeys[i]),
            TOOLS[i],
            mouseX,
            mouseY,
            Boolean.TRUE.equals(actionState(TOOLS[i])));
        if (l.sidebar != null) {
            if (settingsOpen) drawSettings(f, l.sidebar);
            else drawSidebar(f, l.sidebar, mouseX, mouseY);
        }
        if (l.rail != null) drawRail(f, l.rail, mouseX, mouseY);
        inspectorFullscreen = l.inspector != null && l.rail != null
            && l.inspector.x == l.rail.x
            && l.inspector.y == l.rail.y;
        inspectorHitStart = controls.size();
        if (inspectorFullscreen) scrollBars.removeIf(bar -> bar.model == railModel);
        if (l.inspector != null) drawInspector(f, l.inspector);
        if (l.power != null) drawPower(f, l.power);
        if (l.alerts != null) {
            EngineeringTerminalHudSnapshot.Source source = EngineeringTerminalHudSnapshot.capture(mc.thePlayer).device;
            box(l.alerts, BACK);
            drawRect(l.alerts.x, l.alerts.y, l.alerts.x + 2, l.alerts.y + l.alerts.h, WARN);
            text(f, tr("alerts") + " · " + warningCount(source), l.alerts.x + 8, l.alerts.y + 5, WARN, l.alerts.w - 16);
            text(f, tr("alerts.hint"), l.alerts.x + 8, l.alerts.y + 17, MUTED, l.alerts.w - 16);
        }
        if (l.dashboard != null) {
            Rect r = l.dashboard;
            FactoryDashboard.render(f, r, mouseX, mouseY, width, height);
        }
        for (Hit hit : controls) if (hit.rect.contains(mouseX, mouseY) && hit.tip != null) {
            tooltip = new ArrayList<>();
            tooltip.add(hit.tip);
            if (hit.shortcut != null) bindingTooltip(tooltip, hit.shortcut);
        }
        if (FactoryShortcuts.isBinding()) {
            String prompt = FactoryShortcuts.bindingPrompt();
            int promptWidth = Math.min(width - 86, f.getStringWidth(prompt) + 16);
            Rect strip = new Rect((width - promptWidth) / 2, 36, promptWidth, 18);
            box(strip, BACK);
            text(f, prompt, strip.x + 8, strip.y + 5, WARN, strip.w - 16);
        }
        if (tooltip != null) drawTooltip(f, tooltip, mouseX, mouseY, width, height);
    }

    private static void drawCameraHints(FontRenderer f, Rect r) {
        Minecraft mc = Minecraft.getMinecraft();
        List<String> lines = new ArrayList<>();
        String movement = TerminalKeyNames.name(mc.gameSettings.keyBindForward.getKeyCode()) + "/"
            + TerminalKeyNames.name(mc.gameSettings.keyBindLeft.getKeyCode())
            + "/"
            + TerminalKeyNames.name(mc.gameSettings.keyBindBack.getKeyCode())
            + "/"
            + TerminalKeyNames.name(mc.gameSettings.keyBindRight.getKeyCode());
        lines.add(
            TerminalBaubleController.row(
                TerminalBaubleController.action(movement, "factory.pan"),
                TerminalBaubleController.action(
                    TerminalKeyNames.name(mc.gameSettings.keyBindJump.getKeyCode()) + "/"
                        + TerminalKeyNames.name(mc.gameSettings.keyBindSneak.getKeyCode()),
                    "factory.height")));
        lines.add(
            TerminalBaubleController.row(
                TerminalBaubleController.action(TerminalKeyNames.name(-98), "factory.pan"),
                TerminalBaubleController.action(TerminalKeyNames.name(-99), "factory.rotate"),
                TerminalBaubleController.action(tr("input.wheel"), "factory.zoom")));
        // Return/follow/exit keep their current bindings in the corresponding toolbar tooltips.
        // Draw and navigation exclusion share this two-line rectangle at the normal font size.
        box(r, 0xFF101010);
        for (int i = 0; i < lines.size(); i++) text(f, lines.get(i), r.x + 6, r.y + 2 + i * 11, TEXT, r.w - 12);
    }

    private static void drawControlSettings(FontRenderer f, Rect r, int y) {
        text(f, tr("controls.hint"), r.x + 8, y, MUTED, r.w - 16);
        y += 18;
        for (Setting setting : Setting.values()) {
            Rect line = new Rect(r.x + 6, y, r.w - 12, 24);
            box(line, HEADER);
            String key = setting.name()
                .toLowerCase(java.util.Locale.ROOT);
            text(f, tr("controls." + key), line.x + 5, y + 8, TEXT, line.w - 106);
            text(
                f,
                String.format(java.util.Locale.ROOT, "%.2fx", FactoryControlSettings.value(setting)),
                line.x + line.w - 91,
                y + 8,
                ACCENT,
                44);
            control(
                new Rect(line.x + line.w - 45, y + 2, 20, 20),
                "-",
                tr("controls.decrease"),
                () -> FactoryControlSettings.adjust(setting, -1));
            control(
                new Rect(line.x + line.w - 22, y + 2, 20, 20),
                "+",
                tr("controls.increase"),
                () -> FactoryControlSettings.adjust(setting, 1));
            y += 28;
        }
        int w = (r.w - 16) / 3;
        for (Preset preset : Preset.values()) {
            Rect button = new Rect(r.x + 6 + preset.ordinal() * w, y + 3, w - 3, 21);
            box(button, HEADER);
            control(
                button,
                tr(
                    "controls.preset." + preset.name()
                        .toLowerCase(java.util.Locale.ROOT)),
                tr("controls.preset.hint"),
                () -> FactoryControlSettings.applyPreset(preset));
        }
        Rect reset = new Rect(r.x + 6, y + 32, r.w - 12, 21);
        box(reset, HEADER);
        control(reset, tr("controls.reset"), tr("controls.reset.hint"), FactoryControlSettings::resetDefaults);
    }

    private static Boolean actionState(Action action) {
        boolean camera = EngineeringModeController.INSTANCE.cameraPanel();
        switch (action) {
            case DASHBOARD:
                return dashboardMode && !camera;
            case CAMERA:
                return camera;
            case XRAY:
                return EngineeringModeController.INSTANCE.xrayEnabled();
            case FOLLOW:
                return EngineeringModeController.INSTANCE.following();
            case PIN:
                return EngineeringModeController.INSTANCE.pinned();
            case MONITOR:
                return railOpen && !camera && !dashboardMode;
            case POWER:
                return powerOpen && !camera && !dashboardMode;
            case MAP:
                return EngineeringHud.mapVisible();
            case ALERTS:
                return alertsOpen;
            case GRID_MC:
                return EngineeringHud.gridMcActive();
            case GRID_GT:
                return EngineeringHud.gridGtActive();
            case SELECT_AREA:
                return EngineeringHud.customSelectionActive() || EngineeringHud.areaSelecting();
            case AUDIT:
                return EngineeringHud.auditActive();
            case SCOPE:
                return EngineeringHud.boundScopeActive();
            case VISUAL:
                return EngineeringTerminalActions.deviceVisualizationEnabled();
            case FILTER:
                return machineFilter != FactoryMachineFilter.ALL;
            default:
                return null;
        }
    }

    private static String actionTip(Action action, String label) {
        Boolean state = actionState(action);
        String tip = label == null ? actionLabel(action) : label;
        if (state != null && !tip.contains(tr("state.on")) && !tip.contains(tr("state.off")))
            tip += " - " + tr(state ? "state.on" : "state.off");
        if (action == Action.SELECT_AREA && !EngineeringHud.gridMcActive() && !EngineeringHud.gridGtActive())
            tip += " - " + StatCollector.translateToLocal("gtswn.engineering.dash.selection.need.grid");
        return tip;
    }

    private static void stateEdge(Rect r, boolean active) {
        drawRect(r.x, r.y + r.h - 2, r.x + r.w, r.y + r.h, active ? ACCENT : MUTED);
    }

    private static void clickSound() {
        Minecraft.getMinecraft()
            .getSoundHandler()
            .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
    }

    private static void acceptClick(Runnable action) {
        clickSound();
        mouseDispatch = true;
        try {
            action.run();
        } finally {
            mouseDispatch = false;
        }
    }

    private static void drawSidebar(FontRenderer f, Rect r, int mx, int my) {
        box(r, BACK);
        drawRect(r.x, r.y, r.x + r.w, r.y + 23, HEADER);
        Source source = EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, selectedSlot);
        String name = source.stack != null ? source.stack.getDisplayName() : tr("source.none");
        text(f, name, r.x + 8, r.y + 7, ACCENT, r.w - 32);
        control(new Rect(r.x + r.w - 23, r.y + 2, 21, 19), "<", tr("collapse"), () -> sidebarOpen = false);
        String[] tabs = { "functions", "data", "project", "bindings" };
        int tabWidth = (r.w - 12) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            final int tab = i;
            Rect tabRect = new Rect(r.x + 5 + i * tabWidth, r.y + 28, tabWidth - 2, 19);
            box(tabRect, page == i ? HEADER : 0xFF22313C);
            if (page == i) stateEdge(tabRect, true);
            control(tabRect, tr(tabs[i]), tr(tabs[i]) + " - " + tr(page == i ? "state.on" : "state.off"), () -> {
                page = tab;
                sidebarScroll = 0;
            });
        }
        text(f, sourceLabel(source), r.x + 8, r.y + 53, source.present() ? MUTED : WARN, r.w - 16);
        int y = r.y + 69;
        if (page == 3) {
            drawShortcuts(f, r, y, FactoryShortcutPages.bindings(selectedSlot), false);
            return;
        }
        if (!source.present()) {
            sidebarScroll = drawScrolledLines(
                f,
                java.util.Arrays.asList(tr("source.none"), tr("source.hint")),
                r.x + 8,
                y,
                r.w - 16,
                r.y + r.h - 8,
                sidebarScroll,
                sidebarModel,
                MUTED);
            return;
        }
        if (page == 0) {
            Action[] actions = FactoryShortcutPages.functions(selectedSlot);
            int visible = Math.max(1, (r.h - 82) / 23);
            sidebarScroll = Math.min(sidebarScroll, Math.max(0, actions.length - visible));
            sidebarModel.measure(actions.length, visible, sidebarScroll);
            for (int i = sidebarScroll; i < actions.length && i < sidebarScroll + visible; i++) {
                Action action = actions[i];
                String label = action == Action.MONITOR
                    ? tr(selectedSlot == 2 ? "machines" : selectedSlot == 0 ? "inspect" : "network.overview")
                    : action == Action.MAP ? tr("map.toggle") : actionLabel(action);
                action(r, y, label, action);
                y += 23;
            }
            if (y + 18 < r.y + r.h) text(f, tr("functions.hint"), r.x + 8, y + 5, MUTED, r.w - 16);
        } else if (page == 2) {
            Action[] actions = FactoryShortcutPages.review(selectedSlot);
            for (Action action : actions) {
                action(r, y, actionLabel(action), action);
                y += 23;
            }
        } else {
            List<String> lines = new ArrayList<>();
            lines.add(TerminalPresentation.identity(source.stack, selectedSlot));
            if (selectedSlot == EngineeringHud.activeSlot()) lines.addAll(EngineeringHud.dataLines());
            else lines.add(tr("source.idle"));
            sidebarScroll = drawScrolledLines(
                f,
                lines,
                r.x + 8,
                y,
                r.w - 16,
                r.y + r.h - 9,
                sidebarScroll,
                sidebarModel,
                TEXT);
        }
    }

    private static void drawSettings(FontRenderer f, Rect r) {
        box(r, BACK);
        drawRect(r.x, r.y, r.x + r.w, r.y + 23, HEADER);
        text(f, tr("settings"), r.x + 8, r.y + 7, ACCENT, r.w - 32);
        control(new Rect(r.x + r.w - 23, r.y + 2, 21, 19), "<", tr("collapse"), WorkbenchHud::closeSettings);
        String[] tabs = { "shortcuts", "controls" };
        int tabWidth = (r.w - 12) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            final int tab = i;
            Rect tabRect = new Rect(r.x + 5 + i * tabWidth, r.y + 28, tabWidth - 2, 19);
            box(tabRect, settingsPage == i ? HEADER : 0xFF22313C);
            if (settingsPage == i) stateEdge(tabRect, true);
            control(tabRect, tr(tabs[i]), tr(tabs[i]), () -> {
                FactoryShortcuts.cancelBinding();
                settingsPage = tab;
            });
        }
        if (settingsPage == 0) drawShortcuts(f, r, r.y + 56, FactoryShortcutPages.global(), true);
        else drawControlSettings(f, r, r.y + 56);
    }

    private static void drawShortcuts(FontRenderer f, Rect r, int y, Action[] actions, boolean global) {
        int visible = Math.max(1, (r.y + r.h - 5 - y) / 22);
        int scroll = Math.min(global ? settingsScroll : sidebarScroll, Math.max(0, actions.length - visible));
        if (global) settingsScroll = scroll;
        else sidebarScroll = scroll;
        if (!global) sidebarModel.measure(actions.length, visible, sidebarScroll);
        for (int i = scroll; i < actions.length && i < scroll + visible; i++) {
            Action action = actions[i];
            Rect row = new Rect(r.x + 5, y, r.w - 10, 20);
            box(row, HEADER);
            String name = actionLabel(action), key = FactoryShortcuts.hint(action);
            int keyWidth = Math.min(80, f.getStringWidth(key));
            text(f, name, row.x + 5, row.y + 6, TEXT, row.w - keyWidth - 16);
            text(f, key, row.x + row.w - keyWidth - 5, row.y + 6, ACCENT, keyWidth);
            controls.add(new Hit(row, () -> FactoryShortcuts.beginBinding(action), name, action));
            y += 22;
        }
    }

    private static void action(Rect r, int y, String label, Action action) {
        if (y + 20 > r.y + r.h - 5) return;
        Rect button = new Rect(r.x + 6, y, r.w - 12, 20);
        box(
            button,
            action == Action.SELECT_AREA && !EngineeringHud.gridMcActive() && !EngineeringHud.gridGtActive()
                ? 0xFF22313C
                : HEADER);
        control(button, label, label, () -> shortcut(action), action);
    }

    private static void drawRail(FontRenderer f, Rect r, int mx, int my) {
        box(r, BACK);
        drawRect(r.x, r.y, r.x + r.w, r.y + 23, HEADER);
        text(f, tr("monitor"), r.x + 8, r.y + 7, ACCENT, r.w - 36);
        control(new Rect(r.x + r.w - 23, r.y + 2, 21, 19), ">", tr("collapse"), () -> railOpen = false);
        EngineeringSnapshot snapshot = EngineeringDashboardCache.snapshot();
        EngineeringTerminalHudSnapshot.Source device = EngineeringTerminalHudSnapshot
            .capture(Minecraft.getMinecraft().thePlayer).device;
        boolean nodes = task == 3 || EngineeringHud.activeSlot() == 1 || EngineeringHud.activeSlot() >= 3;
        boolean rebuild = rowsRevision != device.revision || !java.util.Objects.equals(rowsSource, device.terminalId)
            || rowsSnapshot != (nodes ? snapshot : null)
            || rowsFresh != device.fresh
            || rowsFilter != machineFilter.ordinal() + (nodes ? 100 : 0)
            || rowsPower != powerFilter
            || builtOwners != ownersRevision;
        rebuild |= !builtSearch.equals(search.value);
        if (rebuild) {
            builtSearch = search.value;
            rows.clear();
            rowsRevision = device.revision;
            rowsSource = device.terminalId;
            rowsSnapshot = nodes ? snapshot : null;
            rowsFresh = device.fresh;
            rowsFilter = machineFilter.ordinal() + (nodes ? 100 : 0);
            rowsPower = powerFilter;
            builtOwners = ownersRevision;
            if (nodes && snapshot != null && "ok".equals(snapshot.status)) {
                Map<String, List<Node>> owners = new LinkedHashMap<>();
                for (Node node : NodePresentation.sorted(snapshot.nodes))
                    owners.computeIfAbsent(node.owner + "|" + node.dim, ignored -> new ArrayList<>())
                        .add(node);
                for (Map.Entry<String, List<Node>> group : owners.entrySet()) {
                    String key = group.getKey();
                    Node first = group.getValue()
                        .get(0);
                    boolean collapsed = collapsedOwners.contains(key);
                    String owner = first.ownerName.isEmpty() ? tr("owner.unknown") : first.ownerName;
                    rows.add(
                        new Row(
                            (collapsed ? "+ " : "- ") + owner
                                + " · DIM "
                                + first.dim
                                + " · "
                                + group.getValue()
                                    .size(),
                            0,
                            () -> {
                                if (!collapsedOwners.add(key)) collapsedOwners.remove(key);
                                ownersRevision++;
                            },
                            true,
                            tr(collapsed ? "state.off" : "state.on"),
                            null));
                    if (!collapsed) for (Node node : group.getValue()) rows.add(
                        new Row(
                            EngineeringHud.nodeRole(node) + " · " + node.x + "/" + node.y + "/" + node.z,
                            node.online ? 1 : 2,
                            node));
                }
            }
            if (!nodes) for (Entry e : device.devices) {
                if (!machineFilter.matches(e, device.fresh, powerFilter)) continue;
                if (!search.matches(e.name + " " + e.displayName + " " + e.dim + " " + e.x + "/" + e.y + "/" + e.z))
                    continue;
                String statusKey = FactoryMachineFilter.status(e, device.fresh);
                rows.add(new Row(e, tr("status." + statusKey), statusKey));
            }
        }
        boolean successful = !nodes || snapshot != null && "ok".equals(snapshot.status);
        boolean fresh = nodes ? EngineeringDashboardCache.fresh() && successful : device.fresh;
        String total = nodes ? Integer.toString(snapshot == null ? 0 : snapshot.nodes.size())
            : rows.size() + "/" + device.devices.size();
        String status = nodes && EngineeringHud.activeSlot() < 0 || !nodes && device.terminalId == null
            ? tr("source.none")
            : nodes && snapshot != null && !successful
                ? StatCollector.translateToLocal("gtswn.engineering.dash.status." + snapshot.status)
                : tr(fresh ? "received" : "waiting") + " \u00b7 "
                    + total
                    + (nodes && snapshot != null && snapshot.truncated || !nodes && device.partial
                        ? " · " + tr("partial")
                        : "")
                    + (nodes && EngineeringDashboardCache.ageMillis() >= 0
                        ? " · " + EngineeringDashboardCache.ageMillis() / 1000 + "s"
                        : "");
        text(f, status, r.x + 8, r.y + 29, fresh ? ACCENT : WARN, r.w - 16);
        if (!nodes) controls.add(
            new Hit(
                new Rect(r.x + 5, r.y + 25, r.w - 10, 15),
                () -> shortcut(Action.TERMINAL_2),
                originLabel(device.origin),
                Action.TERMINAL_2));
        if (!nodes) {
            FactoryMachineFilter[] filters = { FactoryMachineFilter.ALL, FactoryMachineFilter.RUNNING,
                FactoryMachineFilter.STOPPED, FactoryMachineFilter.ATTENTION };
            int chipWidth = (r.w - 10) / filters.length;
            for (int i = 0; i < filters.length; i++) {
                FactoryMachineFilter chosenFilter = filters[i];
                Rect filter = new Rect(r.x + 5 + i * chipWidth, r.y + 43, chipWidth - 2, 18);
                box(filter, machineFilter == chosenFilter ? HEADER : 0xFF18252F);
                if (machineFilter == chosenFilter) stateEdge(filter, true);
                control(filter, tr("filter." + chosenFilter.key()), tr("filter.hint"), () -> {
                    machineFilter = chosenFilter;
                    railScroll = keyRow = 0;
                });
            }
            Rect direction = new Rect(r.x + 5, r.y + 64, r.w - 10, 17);
            stateEdge(direction, powerFilter != DevicePowerFilter.ALL);
            control(
                direction,
                tr("direction." + powerFilter),
                tr("direction.hint") + " - " + tr(powerFilter != DevicePowerFilter.ALL ? "state.on" : "state.off"),
                () -> {
                    powerFilter = (powerFilter + 1) % DevicePowerFilter.MODE_COUNT;
                    railScroll = 0;
                });
        }
        searchRect = nodes ? null : new Rect(r.x + 5, r.y + 85, r.w - 10, 18);
        if (searchRect != null) {
            box(searchRect, search.focused ? HEADER : 0xFF121B23);
            control(
                searchRect,
                (search.focused ? "> " : "") + (search.value.isEmpty() ? tr("search.devices") : search.value),
                tr("search.hint"),
                () -> search.focused = true);
        }
        if (!nodes) control(
            new Rect(r.x + 5, r.y + 106, r.w - 10, 19),
            tr("desktop.mount"),
            tr("desktop.mount.hint"),
            () -> mountSelection(device));
        int top = r.y + (nodes ? 49 : 131);
        List<String> selected = nodes ? EngineeringHud.targetLines() : machineDetails(device);
        boolean separate = task >= 2 && !sidebarOpen;
        int detailHeight = separate || selected.isEmpty() ? 0
            : Math.max(0, Math.min(112, Math.min(r.h / 2, r.y + r.h - top - 31)));
        int bottom = r.y + r.h - 8 - detailHeight;
        int count = Math.max(0, (bottom - top) / 23);
        railVisible = count;
        railScroll = railModel.measure(rows.size(), count, railScroll);
        detailRect = detailHeight == 0 ? null
            : new Rect(r.x + 5, r.y + r.h - detailHeight + 23, r.w - 10, Math.max(0, detailHeight - 28));
        if (rows.isEmpty()) drawLines(
            f,
            java.util.Arrays.asList(
                nodes && snapshot != null && !successful ? status
                    : tr(!nodes && !device.devices.isEmpty() ? "filter.empty" : "list.empty"),
                tr(!nodes && !device.devices.isEmpty() ? "filter.empty.hint" : "list.hint")),
            r.x + 8,
            top,
            r.w - 16,
            bottom,
            0,
            MUTED);
        for (int i = 0; i < count && i + railScroll < rows.size(); i++) {
            Row row = rows.get(i + railScroll);
            Rect hit = new Rect(r.x + 5, top + i * 23, r.w - 10, 22);
            boolean chosen = row.entry != null && selectedRecord != null
                && selectedRecord.matches(device.terminalId, row.entry);
            if (hit.contains(mx, my) || chosen) box(hit, HEADER);
            if (chosen) stateEdge(hit, true);
            int color = row.statusKey == null ? !fresh ? MUTED : row.state == 1 ? ACCENT : STOPPED
                : statusColor(row.statusKey);
            if (!row.group) drawRect(hit.x + 2, hit.y + 2, hit.x + 4, hit.y + hit.h - 2, color);
            int statusWidth = row.status == null ? 0 : Math.min(67, f.getStringWidth(row.status) + 5);
            text(
                f,
                row.name,
                hit.x + (row.group ? 4 : 9),
                hit.y + (row.entry == null ? 7 : 3),
                row.group ? ACCENT : TEXT,
                hit.w - statusWidth - 14);
            if (row.status != null) text(f, row.status, hit.x + hit.w - statusWidth, hit.y + 7, color, statusWidth - 3);
            if (row.entry != null) text(
                f,
                "DIM " + row.entry.dim + " · " + row.entry.x + "/" + row.entry.y + "/" + row.entry.z,
                hit.x + 9,
                hit.y + 13,
                MUTED,
                hit.w - 18);
            controls.add(
                new Hit(
                    hit,
                    () -> row.choose(device.terminalId),
                    row.name + (row.status == null ? "" : " \u00b7 " + row.status)));
        }
        if (railModel.maximum() > 0 && bottom > top) {
            Rect bar = new Rect(r.x + r.w - 5, top, 4, bottom - top);
            box(bar, HEADER);
            int thumb = Math.max(10, bar.h * Math.max(1, count) / Math.max(1, rows.size()));
            int at = bar.y + (bar.h - thumb) * railScroll / railModel.maximum();
            drawRect(bar.x, at, bar.x + bar.w, at + thumb, ACCENT);
            scrollBars.add(new ScrollHit(new Rect(bar.x - 3, bar.y, 8, bar.h), railModel));
        }
        if (!selected.isEmpty() && !separate) {
            int dy = r.y + r.h - detailHeight;
            drawRect(r.x + 5, dy, r.x + r.w - 5, dy + 1, HEADER);
            control(new Rect(r.x + r.w - 84, dy + 3, 36, 16), tr("pin"), tr("pin"), () -> {
                if (selectedRecord == null
                    || selectedRecord.locatable(Minecraft.getMinecraft().theWorld.provider.dimensionId))
                    shortcut(Action.PIN);
            }, Action.PIN);
            control(
                new Rect(r.x + r.w - 46, dy + 3, 40, 16),
                tr("clear"),
                tr("clear.selection"),
                () -> shortcut(Action.CLEAR),
                Action.CLEAR);
            text(f, tr("selection"), r.x + 8, dy + 7, ACCENT, r.w - 98);
            detailScroll = drawScrolledLines(
                f,
                selected,
                r.x + 8,
                dy + 23,
                r.w - 16,
                r.y + r.h - 5,
                detailScroll,
                detailModel,
                TEXT);
        }
    }

    private static int statusColor(String status) {
        return "running".equals(status) ? ACCENT
            : "idle".equals(status) ? WARN : "stopped".equals(status) ? STOPPED : MUTED;
    }

    private static void drawInspector(FontRenderer f, Rect r) {
        box(r, BACK);
        drawRect(r.x, r.y, r.x + 2, r.y + r.h, ACCENT);
        text(f, tr("selection"), r.x + 8, r.y + 8, ACCENT, r.w - 16);
        EngineeringTerminalHudSnapshot.Source source = EngineeringTerminalHudSnapshot
            .capture(Minecraft.getMinecraft().thePlayer).device;
        boolean nodes = task == 3;
        List<String> lines = nodes ? EngineeringHud.targetLines() : machineDetails(source);
        if (lines.isEmpty()) lines = java.util.Collections.singletonList(tr("inspect.hint"));
        if (!nodes) control(
            new Rect(r.x + 7, r.y + 29, r.w - 14, 21),
            tr("desktop.mount"),
            tr("desktop.mount.hint"),
            () -> mountSelection(source));
        detailRect = new Rect(r.x + 7, r.y + (nodes ? 30 : 57), r.w - 14, r.h - (nodes ? 58 : 85));
        detailScroll = drawScrolledLines(
            f,
            lines,
            detailRect.x,
            detailRect.y,
            detailRect.w,
            detailRect.y + detailRect.h,
            detailScroll,
            detailModel,
            TEXT);
        boolean locate = selectedRecord == null
            || selectedRecord.locatable(Minecraft.getMinecraft().theWorld.provider.dimensionId);
        control(
            new Rect(r.x + 6, r.y + r.h - 23, r.w - 56, 19),
            tr(locate ? "pin" : "locate.disabled"),
            tr(locate ? "pin" : "locate.other.dimension"),
            () -> { if (locate) shortcut(Action.PIN); });
        control(
            new Rect(r.x + r.w - 47, r.y + r.h - 23, 40, 19),
            tr("clear"),
            tr("clear.selection"),
            () -> shortcut(Action.CLEAR));
    }

    private static List<String> machineDetails(EngineeringTerminalHudSnapshot.Source source) {
        EngineeringModeController.Target target = EngineeringModeController.INSTANCE.selected();
        if (selectedRecord != null || target != null) for (Entry e : source.devices) {
            if (selectedRecord != null ? !selectedRecord.matches(source.terminalId, e)
                : Minecraft.getMinecraft().theWorld == null
                    || e.dim != Minecraft.getMinecraft().theWorld.provider.dimensionId
                    || e.x != target.x
                    || e.y != target.y
                    || e.z != target.z)
                continue;
            List<String> lines = new ArrayList<>();
            String status = FactoryMachineFilter.status(e, source.fresh);
            lines.add("\u00a7b" + (e.displayName.isEmpty() ? e.name : e.displayName));
            lines.add(tr("status." + status) + " \u00b7 DIM " + e.dim + " \u00b7 " + e.x + "/" + e.y + "/" + e.z);
            if (Minecraft.getMinecraft().theWorld != null
                && e.dim != Minecraft.getMinecraft().theWorld.provider.dimensionId)
                lines.add("\u00a76" + tr("locate.other.dimension"));
            lines.add(
                "\u00a77" + tr("power.current")
                    + " \u00a7f"
                    + FormatUtil.formatMetricDouble(e.instIn)
                    + " \u2192 "
                    + FormatUtil.formatMetricDouble(e.instOut)
                    + " EU/t");
            lines.add(
                "\u00a77" + tr("power.average")
                    + " \u00a7f"
                    + FormatUtil.formatMetricDouble(e.avgIn)
                    + " \u2192 "
                    + FormatUtil.formatMetricDouble(e.avgOut)
                    + " EU/t");
            if (!source.fresh) lines.add("\u00a76" + tr("stale.hint"));
            else if (e.evidenceKind >= 2 && !FactoryMachineFilter.ignored(e))
                lines.add("\u00a77" + tr(e.evidenceKind == 2 ? "evidence.warming" : "evidence.unknown"));
            if (!e.recipeIn.isEmpty()) lines.add(e.recipeIn);
            if (!e.recipeOut.isEmpty()) lines.add(e.recipeOut);
            return lines;
        }
        return selectedRecord == null ? EngineeringHud.targetLines()
            : java.util.Collections.singletonList(tr("selection.unavailable"));
    }

    private static void mountSelection(EngineeringTerminalHudSnapshot.Source source) {
        if (selectedRecord == null || !source.fresh) return;
        for (Entry entry : source.devices) if (selectedRecord.matches(source.terminalId, entry)) {
            FactoryDashboard.mount(source.terminalId, entry);
            return;
        }
    }

    private static void drawPower(FontRenderer f, Rect r) {
        box(r, BACK);
        drawRect(r.x, r.y, r.x + r.w, r.y + 22, HEADER);
        text(f, tr("power"), r.x + 8, r.y + 7, ACCENT, r.w - 36);
        control(new Rect(r.x + r.w - 23, r.y + 2, 21, 18), ">", tr("collapse"), () -> powerOpen = false);
        EngineeringTerminalHudSnapshot.Source source = EngineeringTerminalHudSnapshot
            .capture(Minecraft.getMinecraft().thePlayer).portable;
        List<String> lines = new ArrayList<>();
        if (source.terminalId != null) {
            lines.add(originLabel(source.origin) + " · " + tr(source.fresh ? "live" : "waiting"));
            lines.addAll(source.basicLines);
            controls.add(
                new Hit(
                    new Rect(r.x + 5, r.y + 26, r.w - 10, 18),
                    () -> shortcut(Action.TERMINAL_0),
                    tr("inspect"),
                    Action.TERMINAL_0));
        }
        EngineeringSnapshot s = EngineeringDashboardCache.snapshot();
        if (lines.size() <= 1 && s != null && s.slot == 2) {
            lines.add(tr(EngineeringDashboardCache.fresh() ? "coverage.power" : "waiting"));
            lines.add(
                FormatUtil.formatMetricDouble(s.current.instIn) + " → "
                    + FormatUtil.formatMetricDouble(s.current.instOut)
                    + " EU/t");
            lines.add(tr("coverage") + " " + s.monitored + "/" + s.totalBound + " · ? " + s.unknown);
        }
        if (lines.isEmpty()) {
            lines.add(tr("power.empty"));
            lines.add(tr("power.hint"));
        } else if (lines.size() == 1) lines.add(tr("power.hud.off"));
        drawLines(f, lines, r.x + 8, r.y + 29, r.w - 16, r.y + r.h - 5, 0, TEXT);
    }

    private static void drawDashboard(FontRenderer f, Rect r) {
        List<String> lines = new ArrayList<>();
        EngineeringTerminalHudSnapshot s = EngineeringTerminalHudSnapshot.capture(Minecraft.getMinecraft().thePlayer);
        lines.add(tr("device.source") + " · " + originLabel(s.device.origin));
        lines.add(tr("monitor") + " · " + s.device.devices.size() + " · " + tr(s.device.fresh ? "live" : "waiting"));
        lines.add(tr("portable.source") + " · " + originLabel(s.portable.origin));
        lines.addAll(s.portable.basicLines);
        lines.addAll(EngineeringHud.dataLines());
        if (s.portable.terminalId == null) lines.add(tr("dashboard.empty"));
        box(new Rect(r.x, r.y, r.w, Math.min(r.h, 52 + lines.size() * 16)), BACK);
        text(f, tr("dashboard"), r.x + 10, r.y + 10, ACCENT, r.w - 20);
        drawLines(f, lines, r.x + 10, r.y + 32, r.w - 20, r.y + r.h - 10, sidebarScroll, TEXT);
    }

    private static String sourceLabel(Source source) {
        return originLabel(source.origin);
    }

    private static int warningCount(EngineeringTerminalHudSnapshot.Source source) {
        updateCounts(source);
        return cachedWarnings;
    }

    private static void updateCounts(EngineeringTerminalHudSnapshot.Source source) {
        if (countsRevision == source.revision && java.util.Objects.equals(countsSource, source.terminalId)
            && countsFresh == source.fresh
            && countsFilter == machineFilter.ordinal()
            && countsPower == powerFilter) return;
        countsRevision = source.revision;
        countsSource = source.terminalId;
        countsFresh = source.fresh;
        countsFilter = machineFilter.ordinal();
        countsPower = powerFilter;
        cachedFiltered = cachedWarnings = 0;
        for (Entry entry : source.devices) {
            if (machineFilter.matches(entry, source.fresh, powerFilter)) cachedFiltered++;
            if (FactoryMachineFilter.ATTENTION.matches(entry, source.fresh, DevicePowerFilter.ALL)) cachedWarnings++;
        }
    }

    private static int visibleNodeRows(EngineeringSnapshot snapshot) {
        if (snapshot == null) return 0;
        if (snapshot == countedNodes && countedOwners == ownersRevision) return cachedNodeRows;
        Set<String> owners = new HashSet<>();
        int count = 0;
        for (Node node : snapshot.nodes) {
            String key = node.owner + "|" + node.dim;
            if (owners.add(key)) count++;
            if (!collapsedOwners.contains(key)) count++;
        }
        countedNodes = snapshot;
        countedOwners = ownersRevision;
        cachedNodeRows = count;
        return count;
    }

    private static String originLabel(SourceOrigin origin) {
        return tr(
            origin == SourceOrigin.BAUBLE ? "source.bauble"
                : origin == SourceOrigin.INVENTORY ? "source.inventory" : "source.none");
    }

    /** Restored dashboards reopen only against a currently available real terminal, outside rendering. */
    static void tick() {
        if (EngineeringModeController.INSTANCE.cameraPanel() && dashboardMode) switchTask(1);
        if (task == 3 && (EngineeringHud.activeSlot() < 0 || EngineeringHud.activeSlot() == 2
            || EngineeringHud.activeSlot() == 0)) chooseNetworkSource();
        EngineeringTerminalHudSnapshot data = EngineeringTerminalHudSnapshot
            .capture(Minecraft.getMinecraft().thePlayer);
        if (selectedRecord != null && !java.util.Objects.equals(selectedRecord.source, data.device.terminalId)) {
            selectedRecord = null;
            EngineeringModeController.INSTANCE.clearSelection();
        }
        if (dashboardMode && !PortableSuperDashboard.isOpen()) {
            Source portable = EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, 0);
            if (portable.present()) {
                PortableSuperDashboard.open(portable.stack);
                PortableSuperDashboard.onUserClose(() -> switchTask(1));
            }
        }
        PortableSuperDashboard.tick();
        FactoryDashboard.tick(data);
    }

    static void switchTask(int destination) {
        search.focused = false;
        FactoryDashboard.blur();
        leaveCamera();
        closeSettings();
        task = Math.max(0, Math.min(3, destination));
        dashboardMode = task == 0;
        sidebarOpen = false;
        if (!dashboardMode) PortableSuperDashboard.close();
        railOpen = task >= 2;
        powerOpen = false;
        if (task == 0) EngineeringHud.useOverviewSource();
        if (task == 2) EngineeringHud.useTaskSource(2);
        if (task == 3) chooseNetworkSource();
    }

    private static void chooseNetworkSource() {
        int slot = EngineeringHud.activeSlot();
        if ((slot == 1 || slot >= 3) && EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, slot)
            .present()) {
            EngineeringHud.useTaskSource(slot);
            return;
        }
        for (int i : new int[] { 1, 3, 4, 5, 6, 7 })
            if (EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, i)
                .present()) {
                    EngineeringHud.useTaskSource(i);
                    return;
                }
        EngineeringHud.useTaskSource(-1);
    }

    static void openDevices() {
        switchTask(2);
    }

    static boolean worldView() {
        return !dashboardMode && task == 1;
    }

    static boolean dashboardVisible() {
        return dashboardMode;
    }

    static void worldSelectionChanged() {
        selectedRecord = null;
    }

    static boolean typing() {
        return search.focused || dashboardMode && FactoryDashboard.typing();
    }

    static boolean key(int code, char character, boolean pressed) {
        if (dashboardMode && FactoryDashboard.key(code, character, pressed)) return true;
        if (FactoryChrome.key(code, character, pressed)) return true;
        if (search.focused) {
            if (pressed) {
                if (code == org.lwjgl.input.Keyboard.KEY_ESCAPE || code == org.lwjgl.input.Keyboard.KEY_RETURN)
                    search.focused = false;
                else if (code == org.lwjgl.input.Keyboard.KEY_BACK) search.backspace();
                else if (code == org.lwjgl.input.Keyboard.KEY_DELETE) search.value = "";
                else search.character(character);
                railScroll = 0;
            }
            return true;
        }
        if (task < 2 && !sidebarOpen) return false;
        if (code == org.lwjgl.input.Keyboard.KEY_TAB) {
            if (pressed) focusRegion = (focusRegion + 1) % 3;
            return true;
        }
        if (focusRegion == 0 && task >= 2
            && (code == org.lwjgl.input.Keyboard.KEY_UP || code == org.lwjgl.input.Keyboard.KEY_DOWN
                || code == org.lwjgl.input.Keyboard.KEY_RETURN)) {
            if (pressed && !rows.isEmpty()) {
                keyRow = Math.max(
                    0,
                    Math.min(
                        rows.size() - 1,
                        keyRow + (code == org.lwjgl.input.Keyboard.KEY_UP ? -1
                            : code == org.lwjgl.input.Keyboard.KEY_DOWN ? 1 : 0)));
                if (keyRow < railScroll) railScroll = railModel.set(keyRow);
                if (keyRow >= railScroll + railVisible) railScroll = railModel.set(keyRow - railVisible + 1);
                if (code == org.lwjgl.input.Keyboard.KEY_RETURN) rows.get(keyRow)
                    .choose(
                        EngineeringTerminalHudSnapshot.capture(Minecraft.getMinecraft().thePlayer).device.terminalId);
            }
            return true;
        }
        ScrollModel model = focusRegion == 1 ? detailModel : focusRegion == 2 ? sidebarModel : railModel;
        if (!scrollKey(model, code, pressed)) return false;
        if (model == detailModel) detailScroll = model.offset();
        else if (model == sidebarModel) sidebarScroll = model.offset();
        else railScroll = model.offset();
        return true;
    }

    static boolean scrollKey(ScrollModel model, int code, boolean pressed) {
        if (code != org.lwjgl.input.Keyboard.KEY_HOME && code != org.lwjgl.input.Keyboard.KEY_END
            && code != org.lwjgl.input.Keyboard.KEY_PRIOR
            && code != org.lwjgl.input.Keyboard.KEY_NEXT) return false;
        if (pressed) {
            if (code == org.lwjgl.input.Keyboard.KEY_HOME) model.set(0);
            else if (code == org.lwjgl.input.Keyboard.KEY_END) model.set(model.maximum());
            else model.move((code == org.lwjgl.input.Keyboard.KEY_PRIOR ? -1 : 1) * Math.max(1, model.viewport() - 1));
        }
        return true;
    }

    static void openAttention() {
        machineFilter = FactoryMachineFilter.ATTENTION;
        powerFilter = DevicePowerFilter.ALL;
        showMachines();
    }

    static void openPowerReview() {
        switchTask(1);
        EngineeringHud.activateData(2);
        if (!EngineeringHud.gridMcActive() && !EngineeringHud.gridGtActive()) EngineeringHud.toolAction(2, 1);
        if (!EngineeringHud.auditActive()) EngineeringHud.toggleAudit();
        powerOpen = true;
    }

    private static void toggleDashboard() {
        switchTask(dashboardMode ? 1 : 0);
    }

    static void openDashboard() {
        switchTask(0);
    }

    /** Shared entry point for HUD buttons and configurable keyboard actions. */
    static void shortcut(Action action) {
        if (action == null) return;
        controls.clear();
        if (!mouseDispatch) clickSound();
        if (action.ordinal() >= Action.TERMINAL_0.ordinal() && action.ordinal() <= Action.TERMINAL_7.ordinal()) {
            int slot = action.ordinal() - Action.TERMINAL_0.ordinal();
            if (selectedSlot == slot && sidebarOpen && settingsOpen) closeSettings();
            else if (selectedSlot == slot && sidebarOpen) sidebarOpen = false;
            else EngineeringHud.activateTerminal(slot);
            return;
        }
        switch (action) {
            case TOGGLE:
                EngineeringModeController.toggle();
                break;
            case DASHBOARD:
                toggleDashboard();
                break;
            case CAMERA:
                closeSettings();
                sidebarOpen = false;
                dashboardMode = false;
                task = 1;
                PortableSuperDashboard.close();
                EngineeringModeController.INSTANCE.toggleCameraPanel();
                break;
            case XRAY:
                EngineeringModeController.INSTANCE.toggleXray();
                break;
            case RETURN:
                EngineeringModeController.INSTANCE.returnToPlayer();
                break;
            case FOLLOW:
                EngineeringModeController.INSTANCE.toggleFollowing();
                break;
            case PIN:
                EngineeringModeController.INSTANCE.togglePin();
                break;
            case CLEAR:
                selectedRecord = null;
                EngineeringHud.clearTarget();
                detailScroll = 0;
                break;
            case MONITOR:
                boolean cameraMonitor = EngineeringModeController.INSTANCE.cameraPanel();
                leaveCamera();
                dashboardMode = false;
                task = EngineeringHud.activeSlot() == 1 || EngineeringHud.activeSlot() >= 3 ? 3 : 2;
                PortableSuperDashboard.close();
                railOpen = cameraMonitor || sidebarOpen || !railOpen;
                if (sidebarOpen && selectedSlot >= 0 && EngineeringHud.activeSlot() != selectedSlot)
                    EngineeringHud.activateData(selectedSlot);
                break;
            case POWER:
                boolean cameraPower = EngineeringModeController.INSTANCE.cameraPanel();
                if (dashboardMode) switchTask(1);
                leaveCamera();
                powerOpen = cameraPower || !powerOpen;
                break;
            case MAP:
                if (selectedSlot > 0 && selectedSlot != 2) EngineeringHud.toolAction(selectedSlot, 0);
                else EngineeringHud.toggleMap();
                break;
            case REFRESH:
                EngineeringDashboardCache.refresh();
                break;
            case ALERTS:
                alertsOpen = !alertsOpen;
                if (alertsOpen) {
                    machineFilter = FactoryMachineFilter.ATTENTION;
                    powerFilter = DevicePowerFilter.ALL;
                    showMachines();
                }
                break;
            case AUDIT:
                leaveCamera();
                EngineeringHud.toggleAudit();
                break;
            case SCAN:
                EngineeringTerminalActions.scanDevice();
                break;
            case VISUAL:
                EngineeringTerminalActions.toggleDeviceVisualization();
                break;
            case FILTER:
                machineFilter = machineFilter.next();
                showMachines();
                break;
            case GRID_MC:
                leaveCamera();
                EngineeringHud.toolAction(2, 1);
                break;
            case GRID_GT:
                leaveCamera();
                EngineeringHud.toolAction(2, 2);
                break;
            case SELECT_AREA:
                leaveCamera();
                EngineeringHud.toolAction(2, 3);
                break;
            case SCOPE:
                EngineeringHud.toolAction(2, 4);
                break;
            case BASELINE:
                EngineeringHud.toolAction(2, 5);
                break;
            case STOP:
                EngineeringHud.stopData();
                break;
            case OVERVIEW:
                openDashboard();
                break;
            default:
                break;
        }
    }

    private static void leaveCamera() {
        if (EngineeringModeController.INSTANCE.cameraPanel()) EngineeringModeController.INSTANCE.toggleCameraPanel();
    }

    private static void showMachines() {
        leaveCamera();
        dashboardMode = false;
        PortableSuperDashboard.close();
        EngineeringHud.useTaskSource(2);
        railOpen = true;
        task = 2;
        railScroll = detailScroll = 0;
    }

    private static int filteredCount(EngineeringTerminalHudSnapshot.Source source) {
        updateCounts(source);
        return cachedFiltered;
    }

    private static Action terminalAction(int slot) {
        return FactoryShortcutPages.terminal(slot);
    }

    private static String actionLabel(Action action) {
        return StatCollector.translateToLocal(
            action == Action.TOGGLE ? "key.gtswn.engineering"
                : "key.gtswn.factory." + action.name()
                    .toLowerCase(java.util.Locale.ROOT));
    }

    private static void bindingTooltip(List<String> lines, Action action) {
        lines.add(tr("binding.current") + " " + FactoryShortcuts.hint(action));
        lines.add(tr("binding.hint"));
    }

    private static void clickAction(Action action, int button) {
        if (button == 1) acceptClick(() -> FactoryShortcuts.beginBinding(action));
        else if (button == 0) acceptClick(() -> shortcut(action));
    }

    static boolean mouse(int x, int y, int width, int height, int button, boolean pressed, int wheel) {
        WorkbenchLayout l = layout(width, height);
        if (FactoryDesktop.pointerCaptured() && dashboardMode)
            return FactoryDashboard.mouse(x, y, button, pressed, wheel);
        if (button >= 0 && pressed) FactoryDesktop.focusOutside(x, y);
        if (FactoryChrome.mouse(x, y, button, pressed, wheel)) return true;
        if (pressed && button == 0) {
            if (!WorkbenchLayout.inside(searchRect, x, y)) search.focused = false;
            focusRegion = WorkbenchLayout.inside(l.inspector, x, y) ? 1
                : WorkbenchLayout.inside(l.sidebar, x, y) ? 2 : 0;
        }
        if (scrollDrag != null) {
            if (button == 0 && !pressed) scrollDrag = null;
            else {
                scrollDrag.model
                    .set((y - scrollDrag.rect.y) * scrollDrag.model.maximum() / Math.max(1, scrollDrag.rect.h));
                if (scrollDrag.model == sidebarModel) sidebarScroll = sidebarModel.offset();
                if (scrollDrag.model == detailModel) detailScroll = detailModel.offset();
                if (scrollDrag.model == railModel) railScroll = railModel.offset();
            }
            return true;
        }
        if (button == 0 && pressed) for (ScrollHit bar : scrollBars) if (bar.rect.contains(x, y)) {
            scrollDrag = bar;
            return true;
        }
        if (dashboardMode && l.dashboard != null && FactoryDashboard.mouse(x, y, button, pressed, wheel)) return true;
        if (!l.contains(x, y)) return false;
        if (wheel != 0) {
            int delta = wheel > 0 ? -2 : 2;
            if (WorkbenchLayout.inside(l.sidebar, x, y)) {
                if (settingsOpen) settingsScroll = Math.max(0, settingsScroll + delta);
                else sidebarScroll = sidebarModel.move(delta);
            } else if (WorkbenchLayout.inside(l.inspector, x, y)) detailScroll = detailModel.move(delta);
            else if (WorkbenchLayout.inside(l.rail, x, y)) {
                if (WorkbenchLayout.inside(detailRect, x, y)) detailScroll = detailModel.move(delta);
                else railScroll = railModel.move(delta);
            } else if (WorkbenchLayout.inside(l.dashboard, x, y)) {
                if (PortableSuperDashboard.isOpen())
                    PortableSuperDashboard.wheel(x - l.dashboard.x, y - l.dashboard.y + 50, wheel);
                else sidebarScroll = Math.max(0, sidebarScroll + delta);
            }
        }
        if (!pressed || button < 0 || button > 1) return true;
        for (int i = 0; i < 8; i++) if (l.terminals[i].contains(x, y)) {
            clickAction(terminalAction(i), button);
            return true;
        }
        for (int i = 0; i < MODES.length; i++) if (l.modes[i].contains(x, y)) {
            clickAction(MODES[i], button);
            return true;
        }
        for (int i = 0; i < TOOLS.length; i++) if (l.tools[i].contains(x, y)) {
            clickAction(TOOLS[i], button);
            return true;
        }
        if (WorkbenchLayout.inside(l.alerts, x, y)) {
            if (button == 0) acceptClick(WorkbenchHud::openAttention);
            else clickAction(Action.ALERTS, button);
            return true;
        }
        int firstControl = inspectorFullscreen && WorkbenchLayout.inside(l.inspector, x, y) ? inspectorHitStart : 0;
        for (int i = controls.size() - 1; i >= firstControl; i--) if (controls.get(i).rect.contains(x, y)) {
            Hit hit = controls.get(i);
            if (button == 1 && hit.shortcut != null) acceptClick(() -> FactoryShortcuts.beginBinding(hit.shortcut));
            else if (button == 0) acceptClick(hit.action);
            break;
        }
        return true;
    }

    private static void control(Rect rect, String label, String tip, Runnable action) {
        control(rect, label, tip, action, null);
    }

    private static void control(Rect rect, String label, String tip, Runnable action, Action shortcut) {
        FontRenderer f = Minecraft.getMinecraft().fontRenderer;
        Boolean state = shortcut == null ? null : actionState(shortcut);
        if (state != null) {
            stateEdge(rect, state);
            tip = actionTip(shortcut, tip == null ? label : tip);
        }
        text(f, label, rect.x + 5, rect.y + (rect.h - 8) / 2, TEXT, rect.w - 10);
        controls.add(new Hit(rect, action, tip, shortcut));
    }

    private static void icon(Rect r, int u, String tip, Action action, int mx, int my, boolean active) {
        if (r.w == 0 || r.h == 0) return;
        box(r, active || r.contains(mx, my) ? CHROME_SELECTED : CHROME);
        if (actionState(action) != null) stateEdge(r, active);
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(ATLAS);
        GL11.glColor4f(1, 1, 1, 1);
        PAINTER.drawTexturedModalRect(r.x + (r.w - 10) / 2, r.y + (r.h - 10) / 2, u, 40, 10, 10);
        if (r.contains(mx, my)) {
            tooltip = new ArrayList<>();
            tooltip.add(actionTip(action, tip));
            bindingTooltip(tooltip, action);
        }
    }

    private static void drawLines(FontRenderer f, List<String> source, int x, int y, int width, int bottom, int scroll,
        int color) {
        drawScrolledLines(f, source, x, y, width, bottom, scroll, new ScrollModel(), color);
    }

    private static final Map<String, List<String>> wrappedText = new LinkedHashMap<>();

    private static int drawScrolledLines(FontRenderer f, List<String> source, int x, int y, int width, int bottom,
        int scroll, ScrollModel model, int color) {
        String key = System.identityHashCode(f) + ":" + width + ":" + source.toString();
        List<String> lines = wrappedText.get(key);
        if (lines == null) {
            lines = new ArrayList<>();
            for (String line : source)
                lines.addAll(f.listFormattedStringToWidth(line == null ? "" : line, Math.max(1, width)));
            if (wrappedText.size() >= 32) wrappedText.clear();
            wrappedText.put(key, lines);
        }
        int visible = Math.max(0, (bottom - y) / 13);
        int offset = model.measure(lines.size(), visible, scroll);
        for (int i = 0; i < visible && offset + i < lines.size(); i++)
            text(f, lines.get(offset + i), x, y + i * 13, color, width);
        if (model.maximum() > 0 && visible > 0) {
            int trackHeight = bottom - y, thumb = Math.max(8, trackHeight * visible / lines.size());
            int at = y + (trackHeight - thumb) * offset / model.maximum();
            drawRect(x + width - 3, y, x + width, bottom, HEADER);
            drawRect(x + width - 3, at, x + width, at + thumb, ACCENT);
            if (model == sidebarModel || model == detailModel)
                scrollBars.add(new ScrollHit(new Rect(x + width - 6, y, 8, trackHeight), model));
        }
        return offset;
    }

    private static void drawTooltip(FontRenderer f, List<String> lines, int x, int y, int width, int height) {
        List<String> wrapped = new ArrayList<>();
        int limit = Math.max(1, Math.min(290, width - 28));
        for (String line : lines) wrapped.addAll(f.listFormattedStringToWidth(line, limit));
        int w = 0;
        for (String line : wrapped) w = Math.max(w, f.getStringWidth(line));
        int h = wrapped.size() * 13 + 10;
        x = Math.max(4, Math.min(x + 12, width - w - 16));
        y = Math.max(4, Math.min(y + 12, height - h - 4));
        drawRect(x, y, x + w + 12, y + h, 0xFA101923);
        for (int i = 0; i < wrapped.size(); i++)
            text(f, wrapped.get(i), x + 6, y + 5 + i * 13, i == 0 ? ACCENT : TEXT, w);
    }

    private static void box(Rect r, int color) {
        drawRect(r.x, r.y, r.x + r.w, r.y + r.h, color);
    }

    private static void text(FontRenderer f, String text, int x, int y, int color, int width) {
        f.drawString(f.trimStringToWidth(text, Math.max(1, width)), x, y, color);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.factory." + key);
    }

    private static final class Hit {

        final Rect rect;
        final Runnable action;
        final String tip;
        final Action shortcut;

        Hit(Rect rect, Runnable action, String tip) {
            this(rect, action, tip, null);
        }

        Hit(Rect rect, Runnable action, String tip, Action shortcut) {
            this.shortcut = shortcut;
            this.rect = rect;
            this.action = action;
            this.tip = tip;
        }
    }

    private static final class ScrollHit {

        final Rect rect;
        final ScrollModel model;

        ScrollHit(Rect rect, ScrollModel model) {
            this.rect = rect;
            this.model = model;
        }
    }

    private static final class Row {

        final String name;
        final int state;
        final boolean group;
        final Runnable action;
        final String status, statusKey;
        Entry entry;
        Node node;

        Row(Entry entry, String status, String statusKey) {
            this(
                entry.displayName.isEmpty() ? entry.name : entry.displayName,
                entry.state,
                null,
                false,
                status,
                statusKey);
            this.entry = entry;
        }

        Row(String name, int state, Node node) {
            this(name, state, null, false);
            this.node = node;
        }

        void choose(java.util.UUID source) {
            if (entry != null) {
                selectedRecord = new SelectedMachineRecord(source, entry);
                EngineeringModeController.INSTANCE.select(entry);
                detailScroll = 0;
            } else if (node != null) {
                selectedRecord = null;
                EngineeringHud.selectNode(node);
            } else if (action != null) action.run();
        }

        Row(String name, int state, Runnable action, boolean group) {
            this(name, state, action, group, null, null);
        }

        Row(String name, int state, Runnable action, boolean group, String status, String statusKey) {
            this.status = status;
            this.statusKey = statusKey;
            this.name = name;
            this.state = state;
            this.action = action;
            this.group = group;
        }
    }
}
