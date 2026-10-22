package com.miaokatze.gtswn.client.engineering;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.client.gui.TerminalPresentation;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Node;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Factory workbench: Outpost's monitor rail, power card and dashboard stay inside the world view. */
final class WorkbenchHud extends Gui {

    private static final int BACK = 0xE8172029, HEADER = 0xEE2B3E4B, TEXT = 0xFFE4EEF3;
    private static final int MUTED = 0xFF91A6B2, ACCENT = 0xFF69D8CA, WARN = 0xFFF0BB70;
    private static final ResourceLocation ATLAS = new ResourceLocation("gtswn", "textures/gui/factory_atlas.png");
    private static final WorkbenchHud PAINTER = new WorkbenchHud();
    private static final int[] MODE_ICONS = { 48, 0, 24, 12, 84 }, TOOL_ICONS = { 12, 36, 60, 24, 72 };
    private static boolean sidebarOpen, railOpen = true, powerOpen = true, dashboardMode, alertsOpen = true;
    private static int selectedSlot = -1, page, sidebarScroll, railScroll, detailScroll;
    private static final List<Row> rows = new ArrayList<>();
    private static final List<Hit> controls = new ArrayList<>();
    private static final Set<String> collapsedOwners = new HashSet<>();
    private static List<String> tooltip;

    private WorkbenchHud() {}

    static void reset() {
        sidebarOpen = dashboardMode = false;
        railOpen = powerOpen = alertsOpen = true;
        selectedSlot = -1;
        page = sidebarScroll = railScroll = detailScroll = 0;
        rows.clear();
        controls.clear();
        collapsedOwners.clear();
    }

    static void select(int slot) {
        selectedSlot = slot;
        sidebarOpen = true;
        page = sidebarScroll = 0;
    }

    static WorkbenchLayout layout(int width, int height) {
        EngineeringSnapshot snapshot = EngineeringDashboardCache.snapshot();
        EngineeringTerminalHudSnapshot data = EngineeringTerminalHudSnapshot
            .capture(Minecraft.getMinecraft().thePlayer);
        boolean nodes = EngineeringHud.activeSlot() == 1 || EngineeringHud.activeSlot() >= 3;
        int count = nodes ? visibleNodeRows(snapshot) : data.device.devices.size();
        int railHeight = Math.max(106, 69 + Math.min(8, count) * 19) + (EngineeringHud.targetLines()
            .isEmpty() ? 0 : 94);
        int powerHeight = Math.min(108, 42 + Math.max(3, data.portable.basicLines.size() + 1) * 13);
        boolean camera = EngineeringModeController.INSTANCE.cameraPanel();
        return new WorkbenchLayout(
            width,
            height,
            sidebarOpen,
            railOpen && !camera,
            powerOpen && !camera,
            dashboardMode,
            railHeight,
            powerHeight,
            alertsOpen && !camera && warningCount(data.device) > 0);
    }

    static boolean escape() {
        if (dashboardMode) {
            dashboardMode = false;
            PortableSuperDashboard.close();
            return true;
        }
        if (sidebarOpen) {
            sidebarOpen = false;
            return true;
        }
        return false;
    }

    static void inspect() {
        railOpen = true;
        detailScroll = 0;
    }

    static void render(int width, int height, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer f = mc.fontRenderer;
        if (EngineeringModeController.INSTANCE.cameraPanel() && dashboardMode) {
            dashboardMode = false;
            PortableSuperDashboard.close();
        }
        WorkbenchLayout l = layout(width, height);
        tooltip = null;
        controls.clear();
        ItemStack[] equipped = TerminalBaubles.terminals(mc.thePlayer);
        for (int i = 0; i < 8; i++) {
            Rect r = l.terminals[i];
            boolean present = equipped[i] != null;
            box(r, selectedSlot == i && sidebarOpen ? HEADER : BACK);
            if (selectedSlot == i) drawRect(r.x, r.y, r.x + 2, r.y + r.h, ACCENT);
            ItemStack stack = present ? equipped[i] : TerminalPresentation.placeholder(i);
            TerminalPresentation.drawIcon(mc, f, stack, r.x + 5, r.y + 5, present, 18);
            if (r.contains(mouseX, mouseY)) {
                tooltip = new ArrayList<>();
                tooltip.add(stack.getDisplayName());
                tooltip.add(tr("slot") + " " + (i + 1));
                tooltip.add(TerminalPresentation.identity(equipped[i], i));
                if (!present) tooltip.add(tr("empty.slot"));
            }
        }
        String[] modeKeys = { dashboardMode ? "factory" : "dashboard", "camera", "return", "following", "exit" };
        for (int i = 0; i < l.modes.length; i++) icon(
            l.modes[i],
            MODE_ICONS[i],
            tr(modeKeys[i]),
            mouseX,
            mouseY,
            i == 0 && dashboardMode || i == 1 && EngineeringModeController.INSTANCE.cameraPanel()
                || i == 3 && EngineeringModeController.INSTANCE.following());
        text(f, tr(dashboardMode ? "dashboard" : "factory"), 169, 17, ACCENT, Math.max(50, width - 420));
        String[] toolKeys = { "monitor", "power", "map", "refresh", "alerts" };
        for (int i = 0; i < l.tools.length; i++) icon(
            l.tools[i],
            TOOL_ICONS[i],
            tr(toolKeys[i]),
            mouseX,
            mouseY,
            i == 0 && railOpen || i == 1 && powerOpen || i == 2 && EngineeringHud.mapVisible());
        if (l.sidebar != null) drawSidebar(f, l.sidebar, mouseX, mouseY);
        if (l.rail != null) drawRail(f, l.rail, mouseX, mouseY);
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
            if (PortableSuperDashboard.isOpen()) {
                PortableSuperDashboard.renderEmbedded(r.x, r.y, r.w, r.h, mouseX, mouseY, width, height);
            } else drawDashboard(f, r);
        }
        for (Hit hit : controls) if (hit.rect.contains(mouseX, mouseY) && hit.tip != null) {
            tooltip = new ArrayList<>();
            tooltip.add(hit.tip);
        }
        if (tooltip != null) drawTooltip(f, tooltip, mouseX, mouseY, width, height);
    }

    private static void drawSidebar(FontRenderer f, Rect r, int mx, int my) {
        box(r, BACK);
        drawRect(r.x, r.y, r.x + r.w, r.y + 23, HEADER);
        Source source = EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, selectedSlot);
        String name = source.stack != null ? source.stack.getDisplayName() : tr("source.none");
        text(f, name, r.x + 8, r.y + 7, ACCENT, r.w - 32);
        control(new Rect(r.x + r.w - 23, r.y + 2, 21, 19), "<", tr("collapse"), () -> sidebarOpen = false);
        String[] tabs = { "data", "map", "project" };
        for (int i = 0; i < 3; i++) {
            final int tab = i;
            Rect tabRect = new Rect(r.x + 5 + i * 62, r.y + 28, 60, 19);
            box(tabRect, page == i ? HEADER : 0xFF22313C);
            control(tabRect, tr(tabs[i]), null, () -> {
                page = tab;
                sidebarScroll = 0;
            });
        }
        text(f, sourceLabel(source), r.x + 8, r.y + 53, MUTED, r.w - 16);
        int y = r.y + 69;
        if (source.present()) {
            if (page == 0) {
                action(f, r, y, tr("inspect"), () -> {
                    railOpen = true;
                    EngineeringHud.activateData(selectedSlot);
                });
                y += 24;
                if (selectedSlot == 0) {
                    action(f, r, y, tr("dashboard"), WorkbenchHud::toggleDashboard);
                    y += 24;
                }
            } else if (page == 1) {
                if (selectedSlot == 2) {
                    String[] names = { "grid.mc", "grid.gt", "select", "scope" };
                    for (int i = 0; i < names.length; i++) {
                        final int action = i + 1;
                        action(f, r, y, tr(names[i]), () -> EngineeringHud.toolAction(2, action));
                        y += 24;
                    }
                } else if (selectedSlot > 0) {
                    action(f, r, y, tr("map.toggle"), () -> EngineeringHud.toolAction(selectedSlot, 0));
                    y += 24;
                } else {
                    text(f, tr("portable.map"), r.x + 8, y, MUTED, r.w - 16);
                    y += 22;
                }
            } else {
                if (selectedSlot == 2) action(f, r, y, tr("baseline"), EngineeringDashboardCache::baseline);
                else action(f, r, y, tr("refresh"), EngineeringDashboardCache::refresh);
                y += 24;
                action(f, r, y, tr("clear.selection"), EngineeringHud::clearTarget);
                y += 24;
                action(f, r, y, tr("stop"), EngineeringHud::stopData);
                y += 24;
                action(f, r, y, tr("overview"), EngineeringHud::activateOverview);
                y += 24;
            }
        }
        List<String> lines = new ArrayList<>();
        if (source.stack != null) {
            lines.add(TerminalPresentation.identity(source.stack, selectedSlot));
            if (selectedSlot == EngineeringHud.activeSlot()) lines.addAll(EngineeringHud.dataLines());
            else lines.add(tr("source.idle"));
        } else {
            lines.add(tr("source.none"));
            lines.add(tr("source.hint"));
        }
        drawLines(f, lines, r.x + 8, y + 4, r.w - 16, r.y + r.h - 9, sidebarScroll, MUTED);
    }

    private static void action(FontRenderer f, Rect r, int y, String label, Runnable action) {
        Rect button = new Rect(r.x + 6, y, r.w - 12, 20);
        box(button, HEADER);
        control(button, label, null, action);
    }

    private static void drawRail(FontRenderer f, Rect r, int mx, int my) {
        box(r, BACK);
        drawRect(r.x, r.y, r.x + r.w, r.y + 23, HEADER);
        text(f, tr("monitor"), r.x + 8, r.y + 7, ACCENT, r.w - 36);
        control(new Rect(r.x + r.w - 23, r.y + 2, 21, 19), ">", tr("collapse"), () -> railOpen = false);
        rows.clear();
        EngineeringSnapshot snapshot = EngineeringDashboardCache.snapshot();
        EngineeringTerminalHudSnapshot.Source device = EngineeringTerminalHudSnapshot
            .capture(Minecraft.getMinecraft().thePlayer).device;
        boolean nodes = EngineeringHud.activeSlot() == 1 || EngineeringHud.activeSlot() >= 3;
        if (nodes && snapshot != null) {
            Map<String, List<Node>> owners = new LinkedHashMap<>();
            for (Node node : snapshot.nodes)
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
                        () -> { if (!collapsedOwners.add(key)) collapsedOwners.remove(key); },
                        true));
                if (!collapsed) for (Node node : group.getValue()) rows.add(
                    new Row(
                        EngineeringHud.nodeRole(node) + " · " + node.x + "/" + node.y + "/" + node.z,
                        node.online ? 1 : 2,
                        () -> EngineeringHud.selectNode(node),
                        false));
            }
        }
        if (!nodes) for (Entry e : device.devices) rows.add(
            new Row(
                e.displayName.isEmpty() ? e.name : e.displayName,
                e.state,
                () -> EngineeringModeController.INSTANCE.select(e),
                false));
        boolean fresh = nodes ? EngineeringDashboardCache.fresh() : device.fresh;
        String status = fresh ? tr("live") : tr("waiting");
        if (nodes && snapshot != null) status += " · " + snapshot.nodes.size();
        else status += " · " + device.devices.size();
        text(f, status, r.x + 8, r.y + 29, fresh ? ACCENT : WARN, r.w - 16);
        if (!nodes) {
            text(f, originLabel(device.origin), r.x + 8, r.y + 42, MUTED, r.w - 16);
            if (device.terminalId != null) controls.add(
                new Hit(
                    new Rect(r.x + 5, r.y + 39, r.w - 10, 16),
                    () -> EngineeringHud.activateTerminal(2),
                    tr("inspect")));
        }
        int top = r.y + 59;
        List<String> selected = EngineeringHud.targetLines();
        int detailHeight = selected.isEmpty() ? 0 : Math.min(100, r.h / 2);
        int bottom = r.y + r.h - 8 - detailHeight;
        int count = Math.max(0, (bottom - top) / 19);
        railScroll = Math.max(0, Math.min(railScroll, Math.max(0, rows.size() - count)));
        if (rows.isEmpty()) drawLines(
            f,
            java.util.Arrays.asList(tr("list.empty"), tr("list.hint")),
            r.x + 8,
            top,
            r.w - 16,
            bottom,
            0,
            MUTED);
        for (int i = 0; i < count && i + railScroll < rows.size(); i++) {
            Row row = rows.get(i + railScroll);
            Rect hit = new Rect(r.x + 5, top + i * 19, r.w - 10, 18);
            if (hit.contains(mx, my)) box(hit, HEADER);
            if (!row.group) drawRect(
                hit.x + 4,
                hit.y + 6,
                hit.x + 8,
                hit.y + 10,
                !fresh || row.state == 0 ? MUTED : row.state == 1 ? ACCENT : WARN);
            text(f, row.name, hit.x + (row.group ? 4 : 13), hit.y + 5, row.group ? ACCENT : TEXT, hit.w - 18);
            controls.add(new Hit(hit, row.action, row.name));
        }
        if (!selected.isEmpty()) {
            int dy = r.y + r.h - detailHeight;
            drawRect(r.x + 5, dy, r.x + r.w - 5, dy + 1, HEADER);
            drawLines(f, selected, r.x + 8, dy + 7, r.w - 16, r.y + r.h - 5, detailScroll, TEXT);
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
                    () -> EngineeringHud.activateTerminal(0),
                    tr("inspect")));
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
        if (!source.fresh) return 0;
        int count = 0;
        for (Entry entry : source.devices) if (entry.state >= 2) count++;
        return count;
    }

    private static int visibleNodeRows(EngineeringSnapshot snapshot) {
        if (snapshot == null) return 0;
        Set<String> owners = new HashSet<>();
        int count = 0;
        for (Node node : snapshot.nodes) {
            String key = node.owner + "|" + node.dim;
            if (owners.add(key)) count++;
            if (!collapsedOwners.contains(key)) count++;
        }
        return count;
    }

    private static String originLabel(SourceOrigin origin) {
        return tr(
            origin == SourceOrigin.BAUBLE ? "source.bauble"
                : origin == SourceOrigin.INVENTORY ? "source.inventory" : "source.none");
    }

    private static void toggleDashboard() {
        if (EngineeringModeController.INSTANCE.cameraPanel()) EngineeringModeController.INSTANCE.toggleCameraPanel();
        dashboardMode = !dashboardMode;
        PortableSuperDashboard.close();
        if (dashboardMode) {
            Source portable = EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, 0);
            if (portable.present()) PortableSuperDashboard.open(portable.stack);
        }
    }

    static void openDashboard() {
        if (EngineeringModeController.INSTANCE.cameraPanel()) EngineeringModeController.INSTANCE.toggleCameraPanel();
        dashboardMode = true;
        PortableSuperDashboard.close();
        Source portable = EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, 0);
        if (portable.present()) PortableSuperDashboard.open(portable.stack);
    }

    static boolean mouse(int x, int y, int width, int height, int button, boolean pressed, int wheel) {
        WorkbenchLayout l = layout(width, height);
        if (!l.contains(x, y)) return false;
        if (wheel != 0) {
            int delta = wheel > 0 ? -2 : 2;
            if (WorkbenchLayout.inside(l.sidebar, x, y)) sidebarScroll = Math.max(0, sidebarScroll + delta);
            else if (WorkbenchLayout.inside(l.rail, x, y)) {
                if (y >= l.rail.y + l.rail.h - 100 && !EngineeringHud.targetLines()
                    .isEmpty()) detailScroll = Math.max(0, detailScroll + delta);
                else railScroll = Math.max(0, railScroll + delta);
            } else if (WorkbenchLayout.inside(l.dashboard, x, y)) {
                if (PortableSuperDashboard.isOpen())
                    PortableSuperDashboard.wheel(x - l.dashboard.x, y - l.dashboard.y + 50, wheel);
                else sidebarScroll = Math.max(0, sidebarScroll + delta);
            }
        }
        if (button != 0 || !pressed) return true;
        for (int i = 0; i < 8; i++) if (l.terminals[i].contains(x, y)) {
            if (TerminalBaubles.terminals(Minecraft.getMinecraft().thePlayer)[i] != null) {
                if (selectedSlot == i && sidebarOpen) sidebarOpen = false;
                else EngineeringHud.activateTerminal(i);
            }
            return true;
        }
        for (int i = 0; i < 5; i++) if (l.modes[i].contains(x, y)) {
            switch (i) {
                case 0:
                    toggleDashboard();
                    break;
                case 1:
                    dashboardMode = false;
                    PortableSuperDashboard.close();
                    EngineeringModeController.INSTANCE.toggleCameraPanel();
                    break;
                case 2:
                    EngineeringModeController.INSTANCE.returnToPlayer();
                    break;
                case 3:
                    EngineeringModeController.INSTANCE.toggleFollowing();
                    break;
                case 4:
                    EngineeringModeController.close();
                    break;
                default:
                    break;
            }
            return true;
        }
        for (int i = 0; i < 5; i++) if (l.tools[i].contains(x, y)) {
            switch (i) {
                case 0:
                    if (EngineeringModeController.INSTANCE.cameraPanel()) {
                        EngineeringModeController.INSTANCE.toggleCameraPanel();
                        railOpen = true;
                    } else railOpen = !railOpen;
                    break;
                case 1:
                    if (EngineeringModeController.INSTANCE.cameraPanel()) {
                        EngineeringModeController.INSTANCE.toggleCameraPanel();
                        powerOpen = true;
                    } else powerOpen = !powerOpen;
                    break;
                case 2:
                    EngineeringHud.toggleMap();
                    break;
                case 3:
                    EngineeringDashboardCache.refresh();
                    break;
                case 4:
                    alertsOpen = !alertsOpen;
                    railOpen = true;
                    detailScroll = 0;
                    break;
                default:
                    break;
            }
            return true;
        }
        if (WorkbenchLayout.inside(l.dashboard, x, y) && PortableSuperDashboard.isOpen()) {
            PortableSuperDashboard.click(x - l.dashboard.x, y - l.dashboard.y + 50, button);
            return true;
        }
        if (WorkbenchLayout.inside(l.alerts, x, y)) {
            railOpen = true;
            for (Entry entry : EngineeringTerminalHudSnapshot
                .capture(Minecraft.getMinecraft().thePlayer).device.devices) if (entry.state >= 2) {
                    EngineeringModeController.INSTANCE.select(entry);
                    break;
                }
            return true;
        }
        for (int i = controls.size() - 1; i >= 0; i--) if (controls.get(i).rect.contains(x, y)) {
            controls.get(i).action.run();
            break;
        }
        return true;
    }

    private static void control(Rect rect, String label, String tip, Runnable action) {
        FontRenderer f = Minecraft.getMinecraft().fontRenderer;
        text(f, label, rect.x + 5, rect.y + (rect.h - 8) / 2, TEXT, rect.w - 10);
        controls.add(new Hit(rect, action, tip));
    }

    private static void icon(Rect r, int u, String tip, int mx, int my, boolean active) {
        box(r, active || r.contains(mx, my) ? HEADER : BACK);
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(ATLAS);
        GL11.glColor4f(1, 1, 1, 1);
        PAINTER.drawTexturedModalRect(r.x + (r.w - 10) / 2, r.y + (r.h - 10) / 2, u, 40, 10, 10);
        if (r.contains(mx, my)) {
            tooltip = new ArrayList<>();
            tooltip.add(tip);
        }
    }

    private static void drawLines(FontRenderer f, List<String> source, int x, int y, int width, int bottom, int scroll,
        int color) {
        List<String> lines = new ArrayList<>();
        for (String line : source)
            lines.addAll(f.listFormattedStringToWidth(line == null ? "" : line, Math.max(1, width)));
        int visible = Math.max(0, (bottom - y) / 13);
        int offset = Math.min(scroll, Math.max(0, lines.size() - visible));
        for (int i = 0; i < visible && offset + i < lines.size(); i++)
            text(f, lines.get(offset + i), x, y + i * 13, color, width);
    }

    private static void drawTooltip(FontRenderer f, List<String> lines, int x, int y, int width, int height) {
        int w = 0;
        for (String line : lines) w = Math.max(w, f.getStringWidth(line));
        w = Math.min(290, w);
        int h = lines.size() * 13 + 10;
        x = Math.max(4, Math.min(x + 12, width - w - 16));
        y = Math.max(4, Math.min(y + 12, height - h - 4));
        drawRect(x, y, x + w + 12, y + h, 0xFA101923);
        for (int i = 0; i < lines.size(); i++) text(f, lines.get(i), x + 6, y + 5 + i * 13, i == 0 ? ACCENT : TEXT, w);
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

        Hit(Rect rect, Runnable action, String tip) {
            this.rect = rect;
            this.action = action;
            this.tip = tip;
        }
    }

    private static final class Row {

        final String name;
        final int state;
        final boolean group;
        final Runnable action;

        Row(String name, int state, Runnable action, boolean group) {
            this.name = name;
            this.state = state;
            this.action = action;
            this.group = group;
        }
    }
}
