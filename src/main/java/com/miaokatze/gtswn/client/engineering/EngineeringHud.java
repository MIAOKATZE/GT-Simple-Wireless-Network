package com.miaokatze.gtswn.client.engineering;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.TerminalBaubleController;
import com.miaokatze.gtswn.client.engineering.EngineeringModeController.Target;
import com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalActions;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.engineering.EngineeringGrid;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Cell;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Node;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Power;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.miaokatzecore.api.camera.ProjectedPoint;
import com.miaokatze.miaokatzecore.api.camera.RayInterval;
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;
import com.miaokatze.miaokatzecore.client.camera.CameraFrame;
import com.miaokatze.miaokatzecore.client.camera.WorldCameraHooks;
import com.miaokatze.miaokatzecore.client.render.GlScope;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Nonmodal terminal dock. Core owns the camera; the server owns all network and review values. */
public final class EngineeringHud extends Gui {

    private static final int BACK = 0xEC141E27, HEADER = 0xF0263946, CARD = 0xE5263540;
    private static final int ACCENT = 0xFF67D8CD, TEXT = 0xFFE2EEF4, MUTED = 0xFF92A7B4;
    private static final int LINK = 0xFFF1BD70, QUANTUM = 0xFF8EB2FF, INC = 0xFFDA97F1;
    private static int menu = -1, activeSlot = -1, detailScroll;
    private static boolean details = true, grid, gtGrid, selecting, dragging, boundOnly;
    private static int dragX, dragY, endX, endY, minCellX, minCellZ, maxCellX, maxCellZ;
    private static double dragWorldX, dragWorldZ, endWorldX, endWorldZ, planeY;
    private static String notice = "";
    private static Node selectedNode;
    private static final List<NodeHit> hits = new ArrayList<>();
    private static boolean showNodes, nodeListExpanded = true;
    private static int nodeScroll;
    private static final List<NodeRow> nodeRows = new ArrayList<>();
    private static EngineeringSnapshot listSnapshot;
    private static boolean pendingGt;

    static void reset() {
        menu = activeSlot = -1;
        detailScroll = 0;
        details = true;
        grid = gtGrid = selecting = dragging = boundOnly = false;
        selectedNode = null;
        notice = "";
        hits.clear();
        nodeRows.clear();
        listSnapshot = null;
        showNodes = false;
        nodeListExpanded = true;
        nodeScroll = 0;
        pendingGt = false;
        PortableSuperDashboard.close();
        EngineeringDashboardCache.close();
    }

    public static double scale(int width, int height) {
        return Math.max(.1, Math.min(1, Math.min((width - 16) / 640.0, (height - 12) / 400.0)));
    }

    private static CanvasTransform canvas(Minecraft mc) {
        ScaledResolution r = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        return new CanvasTransform(0, 0, 0, 0, scale(r.getScaledWidth(), r.getScaledHeight()));
    }

    private static int width(Minecraft mc) {
        ScaledResolution r = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        return (int) canvas(mc).toLocalX(r.getScaledWidth());
    }

    private static int height(Minecraft mc) {
        ScaledResolution r = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        return (int) canvas(mc).toLocalY(r.getScaledHeight());
    }

    private static int localX(Minecraft mc, int displayX) {
        return (int) (displayX * width(mc) / (double) mc.displayWidth);
    }

    private static int localY(Minecraft mc, int displayY) {
        return (int) ((mc.displayHeight - displayY - 1) * height(mc) / (double) mc.displayHeight);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void hideVanillaHud(RenderGameOverlayEvent.Pre event) {
        if (!EngineeringModeController.isActive() || Minecraft.getMinecraft().currentScreen != null) return;
        switch (event.type) {
            case HOTBAR:
            case HEALTH:
            case FOOD:
            case ARMOR:
            case AIR:
            case EXPERIENCE:
            case HEALTHMOUNT:
            case JUMPBAR:
            case BOSSHEALTH:
            case HELMET:
            case PORTAL:
                event.setCanceled(true);
                break;
            default:
                break;
        }
    }

    @SubscribeEvent
    public void render(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || !EngineeringModeController.isActive()
            || mc.currentScreen != null
            || mc.thePlayer == null) return;
        int w = width(mc), h = height(mc);
        EngineeringSnapshot ready = EngineeringDashboardCache.snapshot();
        if (pendingGt && ready != null && ready.patternResolved) {
            pendingGt = false;
            gtGrid = true;
            defaultRegion();
            subscribe(2, true);
        }
        try (GlScope ignored = new GlScope()) {
            double s = canvas(mc).scale();
            GL11.glScaled(s, s, 1);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            drawMap(mc.fontRenderer, w, h);
            drawDock(mc, w);
            if (PortableSuperDashboard.isOpen())
                PortableSuperDashboard.render(w, h, localX(mc, Mouse.getX()), localY(mc, Mouse.getY()));
            else {
                drawDetails(mc.fontRenderer, h);
                drawNodes(mc.fontRenderer, w, h);
                rect(8, h - 27, w - 8, h - 8, BACK);
                text(mc.fontRenderer, "L " + tr("dash.legend.link"), 16, h - 21, LINK, 102);
                text(mc.fontRenderer, "Q " + tr("dash.legend.quantum"), 122, h - 21, QUANTUM, 102);
                text(mc.fontRenderer, "+ " + tr("dash.legend.inc"), 230, h - 21, INC, 102);
                text(
                    mc.fontRenderer,
                    tr(!notice.isEmpty() ? notice : selecting ? "dash.selection.hint" : "dash.navigation"),
                    340,
                    h - 21,
                    MUTED,
                    w - 358);
                if (dragging) outline(
                    Math.min(dragX, endX),
                    Math.min(dragY, endY),
                    Math.max(dragX, endX),
                    Math.max(dragY, endY),
                    ACCENT);
            }
            if (menu >= 0) drawMenu(mc.fontRenderer, w);
        }
    }

    private static void drawDock(Minecraft mc, int w) {
        FontRenderer f = mc.fontRenderer;
        rect(8, 8, w - 8, 49, BACK);
        rect(8, 8, w - 8, 10, ACCENT);
        text(f, tr("title"), 16, 15, ACCENT, 210);
        button(f, 16, 29, 64, tr(EngineeringModeController.INSTANCE.following() ? "following" : "free"));
        button(f, 84, 29, 56, tr("return"));
        button(f, 144, 29, 70, tr("dash.camera"));
        ItemStack[] slots = TerminalBaubles.terminals(mc.thePlayer);
        for (int i = 0; i < 8; i++) {
            int x = w - 392 + i * 48;
            boolean present = slots[i] != null;
            rect(x + 2, 13, x + 46, 44, present ? (i == activeSlot ? 0xFF325C63 : HEADER) : 0xFF202B32);
            text(
                f,
                tr(i == 0 ? "dash.portable" : i == 1 ? "dash.link" : i == 2 ? "devices" : "dash.quantum"),
                x + 6,
                17,
                present ? TEXT : 0xFF52616A,
                36);
            text(
                f,
                present ? (i >= 3 ? "Q" + (i - 2) + " v" : "v") : tr("dash.empty"),
                x + 6,
                31,
                present ? ACCENT : 0xFF52616A,
                36);
        }
    }

    private static int menuX(int w) {
        return Math.max(8, Math.min(w - 226, w - 392 + menu * 48));
    }

    private static String[] menuLabels() {
        if (menu == 0) return new String[] { tr("dash.board"), tr("dash.original") };
        if (menu == 1) return new String[] { tr("dash.link.map"), tr("dash.original") };
        if (menu == 2) return new String[] { tr("dash.device.map"), tr("dash.grid.mc"), tr("dash.grid.gt"),
            tr(selecting ? "dash.select.off" : "dash.select.on"), tr(boundOnly ? "dash.scope.bound" : "dash.scope.all"),
            tr("dash.baseline"), tr("dash.stop"), tr("dash.original") };
        return new String[] { tr("dash.quantum.map"), tr("dash.quantum.info"), tr("dash.original") };
    }

    private static void drawMenu(FontRenderer f, int w) {
        int x = menuX(w);
        String[] labels = menuLabels();
        rect(x, 52, x + 218, 58 + labels.length * 23, BACK);
        for (int i = 0; i < labels.length; i++) button(f, x + 5, 56 + i * 23, 208, labels[i]);
    }

    private static List<String> detailLines() {
        List<String> lines = new ArrayList<>();
        EngineeringSnapshot s = EngineeringDashboardCache.snapshot();
        if (s == null) lines.add(tr(activeSlot < 0 ? "dash.choose" : "waiting"));
        else {
            lines
                .add(tr(EngineeringDashboardCache.fresh() ? "dash.server.live" : "stale") + " · " + s.serverTick + "t");
            lines.add(tr("dash.status." + s.status));
            if (s.truncated) lines.add(tr("dash.truncated"));
            if (activeSlot == 2) {
                lines.add(tr(s.coverageBoundOnly ? "dash.scope.bound" : "dash.scope.all"));
                lines.add(
                    tr("dash.coverage") + " "
                        + s.monitored
                        + " / "
                        + s.totalBound
                        + " · "
                        + tr("dash.unknown")
                        + " "
                        + s.unknown);
                lines.add(tr("dash.cell.range") + " " + minCellX + "," + minCellZ + " → " + maxCellX + "," + maxCellZ);
                lines.add(tr("dash.plane") + " " + (int) planeY + " · " + tr(gtGrid ? "dash.grid.gt" : "dash.grid.mc"));
                if (gtGrid && !s.patternResolved) lines.add(tr("dash.pattern.unknown"));
                if (gtGrid && s.axisSymmetrical
                    && (minCellX <= -1 && maxCellX >= -1 || minCellZ <= -1 && maxCellZ >= -1))
                    lines.add(tr("dash.pattern.transition"));
                lines.add(tr("dash.power.current") + " " + power(s.current, false));
                lines.add(tr("dash.power.average") + " " + power(s.current, true));
                lines.add(tr(s.coverageBoundOnly ? "dash.average.bound" : "dash.average.window"));
                if (s.review != null) {
                    lines.add(
                        tr("dash.chunks") + " "
                            + s.review.loadedChunks
                            + "/"
                            + s.review.totalChunks
                            + " · "
                            + tr("dash.scanned")
                            + " "
                            + s.review.scannedChunks);
                    if (s.review.discovering) lines.add(tr("dash.discovering"));
                    if (s.review.modeled > 0) lines.add(tr("dash.modeled") + " " + s.review.modeled);
                    lines.add(tr("dash.baseline.tick") + " " + s.review.baselineTick + "t");
                    lines.add(
                        tr("dash.comparable") + " "
                            + s.review.comparableMonitored
                            + " · +"
                            + s.review.addedMonitored
                            + " / -"
                            + s.review.removedMonitored);
                    lines.add(
                        s.review.comparable ? tr("dash.power.delta") + " " + power(s.delta, false)
                            : tr("dash.not.comparable"));
                }
                if (s.coverageChanged) lines.add(tr("dash.coverage.changed"));
                lines.add(tr("dash.unknown.hint"));
            } else if (activeSlot >= 3 && s.quantum != null) {
                QuantumNetworkData q = s.quantum;
                lines.add(tr(q.online ? "dash.online" : "dash.offline"));
                lines.add("DIM " + q.anchorDim + " · " + q.anchorX + " / " + q.anchorY + " / " + q.anchorZ);
                if (q.online) {
                    lines.add(
                        tr("dash.channels") + " "
                            + q.usedChannels
                            + "/"
                            + (q.channelsInfinite ? "∞" : q.totalChannels));
                    lines.add(
                        tr("dash.legend.quantum") + " "
                            + q.quantumNodeCount
                            + " · "
                            + tr("dash.legend.inc")
                            + " "
                            + q.incorporationCount);
                    lines.add(tr("dash.inc.channels") + " " + q.incorporationChannels);
                }
            } else lines.add(tr("dash.node.count") + " " + s.nodes.size());
            Node n = currentNode(s);
            if (n != null) {
                lines.add("────────────────────────");
                lines.add(n.name + " · " + tr(n.online ? "dash.online" : "dash.offline"));
                lines.add("DIM " + n.dim + " · " + n.x + " / " + n.y + " / " + n.z);
                if (n.dim != Minecraft.getMinecraft().theWorld.provider.dimensionId)
                    lines.add(tr("dash.dimension.other"));
                lines.add(tr("dash.owner") + " " + n.ownerName);
                if (n.kind == 1) {
                    lines.add(tr("dash.node.mode") + " " + n.mode + " · " + n.voltage + " V × " + n.amperage + " A");
                    lines.add(tr("dash.stored") + " " + metric(n.stored) + "/" + metric(n.capacity) + " EU");
                } else {
                    lines.add(
                        tr("dash.anchor") + " "
                            + n.anchorDim
                            + ": "
                            + n.anchorX
                            + " / "
                            + n.anchorY
                            + " / "
                            + n.anchorZ);
                    lines.add(tr("dash.channels") + " " + n.channels);
                }
            }
        }
        Target target = EngineeringModeController.INSTANCE.selected();
        if (target != null) {
            lines.add("────────────────────────");
            lines.add(target.name + " · " + target.x + " / " + target.y + " / " + target.z);
            boolean found = false;
            for (Entry e : EngineeringTerminalHudSnapshot.capture(Minecraft.getMinecraft().thePlayer).device.devices) {
                if (e.x != target.x || e.y != target.y
                    || e.z != target.z
                    || e.dim != Minecraft.getMinecraft().theWorld.provider.dimensionId) continue;
                found = true;
                lines.add(
                    tr("state." + Math.max(0, Math.min(3, e.state))) + " · "
                        + metric(e.instIn)
                        + " → "
                        + metric(e.instOut)
                        + " EU/t");
                lines.add(tr("dash.power.average") + " " + metric(e.avgIn) + " → " + metric(e.avgOut) + " EU/t");
                if (!e.recipeIn.isEmpty()) lines.add(e.recipeIn);
                if (!e.recipeOut.isEmpty()) lines.add(e.recipeOut);
                break;
            }
            if (!found && selectedNode == null) lines.add(tr("dash.target.unknown"));
        }
        return lines;
    }

    private static boolean sameNode(Node a, Node b) {
        return a.kind == b.kind && a.dim == b.dim
            && a.x == b.x
            && a.y == b.y
            && a.z == b.z
            && (a.kind != 1 || a.mode == b.mode && a.name.equals(b.name));
    }

    private static Node currentNode(EngineeringSnapshot s) {
        if (selectedNode != null) for (Node node : s.nodes) if (sameNode(node, selectedNode)) return node;
        return null;
    }

    private static void drawDetails(FontRenderer f, int h) {
        int top = h - (details ? 246 : 53);
        rect(8, top, 298, h - 32, BACK);
        rect(8, top, 298, top + 20, HEADER);
        text(f, (details ? "− " : "+ ") + tr("dash.details"), 16, top + 6, ACCENT, 210);
        if (!details) return;
        List<String> lines = detailLines();
        detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, lines.size() - 11)));
        for (int i = 0; i < 11 && i + detailScroll < lines.size(); i++)
            text(f, lines.get(i + detailScroll), 16, top + 27 + i * 15, TEXT, 274);
        if (lines.size() > 11) text(f, "↕ " + (detailScroll + 1) + "/" + lines.size(), 238, top + 6, MUTED, 56);
        if (EngineeringModeController.INSTANCE.selected() != null) {
            button(f, 16, h - 56, 82, tr("focus"));
            button(f, 102, h - 56, 82, tr(EngineeringModeController.INSTANCE.pinned() ? "unpin" : "pin"));
            button(f, 188, h - 56, 100, tr("clear"));
        }
    }

    private static String power(Power p, boolean average) {
        return p == null ? tr("dash.unknown")
            : metric(average ? p.avgIn : p.instIn) + " → " + metric(average ? p.avgOut : p.instOut) + " EU/t";
    }

    private static String metric(double value) {
        return FormatUtil.formatMetricDouble(value);
    }

    private static int nodeListBottom(int h) {
        return Math.min(h - 40, 270);
    }

    private static void drawNodes(FontRenderer f, int w, int h) {
        if (!showNodes) return;
        EngineeringSnapshot s = EngineeringDashboardCache.snapshot();
        if (s != listSnapshot) {
            nodeRows.clear();
            listSnapshot = s;
            if (s != null) {
                Map<String, List<Node>> owners = new LinkedHashMap<>();
                for (Node n : s.nodes) owners.computeIfAbsent(n.owner + "|" + n.dim, key -> new ArrayList<>())
                    .add(n);
                for (List<Node> owner : owners.values()) {
                    Node first = owner.get(0);
                    nodeRows.add(
                        new NodeRow(
                            (first.ownerName.isEmpty() ? first.owner.isEmpty() ? tr("dash.unknown") : first.owner
                                : first.ownerName) + " · DIM " + first.dim + " · " + owner.size(),
                            null));
                    for (Node n : owner) nodeRows.add(new NodeRow(n.name + " · " + n.x + "/" + n.y + "/" + n.z, n));
                }
            }
        }
        int x = w - 228, bottom = nodeListExpanded ? nodeListBottom(h) : 82;
        rect(x, 60, w - 8, bottom, BACK);
        rect(x, 60, w - 8, 80, HEADER);
        text(f, (nodeListExpanded ? "− " : "+ ") + tr("dash.nodes"), x + 8, 66, ACCENT, 204);
        if (!nodeListExpanded) return;
        int visible = Math.max(1, (bottom - 86) / 19);
        nodeScroll = Math.max(0, Math.min(nodeScroll, Math.max(0, nodeRows.size() - visible)));
        if (nodeRows.isEmpty()) text(f, tr(s == null ? "waiting" : "dash.node.empty"), x + 8, 90, MUTED, 204);
        for (int i = 0; i < visible && i + nodeScroll < nodeRows.size(); i++) {
            NodeRow row = nodeRows.get(i + nodeScroll);
            if (row.node != null && selectedNode != null && sameNode(row.node, selectedNode))
                rect(x + 3, 84 + i * 19, w - 11, 102 + i * 19, HEADER);
            int color = row.node == null ? ACCENT : row.node.online && EngineeringDashboardCache.fresh() ? TEXT : MUTED;
            text(f, row.label, x + (row.node == null ? 8 : 15), 89 + i * 19, color, row.node == null ? 204 : 197);
        }
    }

    private static void selectNode(Node node) {
        selectedNode = node;
        EngineeringModeController controller = EngineeringModeController.INSTANCE;
        if (node.dim == Minecraft.getMinecraft().theWorld.provider.dimensionId)
            controller.selectPosition(node.x, node.y, node.z, node.name);
        else controller.clearSelection();
        details = true;
        detailScroll = Math.max(0, detailLines().size() - 11);
    }

    private static void drawMap(FontRenderer f, int w, int h) {
        hits.clear();
        if (PortableSuperDashboard.isOpen()) return;
        EngineeringSnapshot s = EngineeringDashboardCache.snapshot();
        if (s == null) return;
        if (grid) for (Cell c : s.cells) {
            int x1 = EngineeringGrid.minBlock(c.x, gtGrid, s.axisSymmetrical),
                z1 = EngineeringGrid.minBlock(c.z, gtGrid, s.axisSymmetrical);
            int x2 = EngineeringGrid.maxBlockExclusive(c.x, gtGrid, s.axisSymmetrical),
                z2 = EngineeringGrid.maxBlockExclusive(c.z, gtGrid, s.axisSymmetrical);
            double net = c.power == null ? 0 : c.power.instOut - c.power.instIn;
            boolean unknown = !EngineeringDashboardCache.fresh() || c.unknown > 0
                || s.review != null && (s.review.discovering || s.review.loadedChunks < s.review.totalChunks);
            polygon(
                x1,
                z1,
                x2,
                z2,
                planeY,
                w,
                h,
                unknown ? 0x304B5F6B : net > 0 ? 0x3657C6A0 : net < 0 ? 0x365B9EDB : 0x304E7880);
            int[] p = project((x1 + x2) / 2.0, planeY, (z1 + z2) / 2.0, w, h);
            if (p != null && p[0] > 10 && p[0] < w - 10 && p[1] > 58 && p[1] < h - 30) {
                text(f, c.x + "," + c.z, p[0] - 22, p[1] - 10, MUTED, 90);
                text(f, unknown ? "?" : metric(net) + " EU/t", p[0] - 22, p[1] + 2, TEXT, 100);
            }
        }
        if (!showNodes) return;
        Map<Long, NodeHit> clusters = new LinkedHashMap<>();
        for (Node n : s.nodes) {
            if (n.dim != Minecraft.getMinecraft().theWorld.provider.dimensionId) continue;
            int[] p = project(n.x + .5, n.y + .5, n.z + .5, w, h);
            if (p == null || p[0] < 10 || p[0] >= w - 10 || p[1] < 55 || p[1] > h - 30) continue;
            long key = ((long) (p[0] / 24) << 32) ^ (p[1] / 24 & 0xffffffffL);
            NodeHit hit = clusters.get(key);
            if (hit == null) {
                hit = new NodeHit(p[0], p[1]);
                clusters.put(key, hit);
            }
            hit.nodes.add(n);
        }
        for (NodeHit hit : clusters.values()) {
            Node n = hit.nodes.get(0);
            int color = !EngineeringDashboardCache.fresh() || !n.online ? MUTED
                : n.kind == 1 ? LINK : n.kind == 2 ? QUANTUM : INC;
            rect(hit.x - 7, hit.y - 7, hit.x + 8, hit.y + 8, BACK);
            outline(hit.x - 7, hit.y - 7, hit.x + 8, hit.y + 8, color);
            text(
                f,
                hit.nodes.size() > 1 ? String.valueOf(hit.nodes.size()) : n.kind == 3 ? "+" : n.kind == 1 ? "L" : "Q",
                hit.x - 3,
                hit.y - 3,
                color,
                20);
            hits.add(hit);
        }
    }

    private static int[] project(double x, double y, double z, int w, int h) {
        Minecraft mc = Minecraft.getMinecraft();
        ProjectedPoint p = WorldCameraHooks.projectWorld(EngineeringModeController.OWNER, mc.theWorld, x, y, z);
        return p == null || Math.abs(p.ndcZ()) > 1 ? null
            : new int[] { (int) ((p.ndcX() + 1) * w / 2), (int) ((1 - p.ndcY()) * h / 2) };
    }

    private static void polygon(int x1, int z1, int x2, int z2, double y, int w, int h, int color) {
        int[][] points = { project(x1, y, z1, w, h), project(x2, y, z1, w, h), project(x2, y, z2, w, h),
            project(x1, y, z2, w, h) };
        for (int[] p : points) if (p == null) return;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(
            (color >> 16 & 255) / 255f,
            (color >> 8 & 255) / 255f,
            (color & 255) / 255f,
            (color >>> 24) / 255f);
        GL11.glBegin(GL11.GL_QUADS);
        for (int[] p : points) GL11.glVertex2i(p[0], p[1]);
        GL11.glEnd();
        GL11.glColor4f(.35f, .7f, .75f, .7f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int[] p : points) GL11.glVertex2i(p[0], p[1]);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** Core raw navigation precedes Forge; supports() consults this hit test before capturing a pointer. */
    static boolean blocksNavigation(int displayX, int displayY) {
        Minecraft mc = Minecraft.getMinecraft();
        int x = localX(mc, displayX), y = localY(mc, displayY), w = width(mc), h = height(mc);
        return y >= 8 && y < 49 && x >= 8 && x < w - 8
            || menu >= 0 && x >= menuX(w) && x < menuX(w) + 218 && y >= 52 && y < 58 + menuLabels().length * 23
            || PortableSuperDashboard.isOpen() && y >= 50
            || showNodes && x >= w - 228 && x < w - 8 && y >= 60 && y < (nodeListExpanded ? nodeListBottom(h) : 82)
            || x >= 8 && x < 298 && y >= h - (details ? 246 : 53) && y < h - 32
            || y >= h - 27;
    }

    static boolean mouse(MouseEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        int x = localX(mc, e.x), y = localY(mc, e.y), w = width(mc), h = height(mc);
        boolean press = e.button == 0 && e.buttonstate;
        if (dragging) {
            endX = x;
            endY = y;
            double[] p = planePoint(e.x, e.y);
            if (p != null) {
                endWorldX = p[0];
                endWorldZ = p[1];
            }
            if (e.button == 0 && !e.buttonstate) finishSelection();
            return e.button != 1 && e.button != 2;
        }
        if (menu >= 0 && x >= menuX(w) && x < menuX(w) + 218 && y >= 52 && y < 58 + menuLabels().length * 23) {
            if (press) menuAction((y - 56) / 23);
            return true;
        }
        if (x >= 8 && x < w - 8 && y >= 8 && y < 49) {
            if (press) {
                if (x >= w - 392) {
                    int slot = Math.max(0, Math.min(7, (x - (w - 392)) / 48));
                    if (TerminalBaubles.terminals(mc.thePlayer)[slot] != null) menu = menu == slot ? -1 : slot;
                } else if (y >= 29 && x < 80) EngineeringModeController.INSTANCE.toggleFollowing();
                else if (y >= 29 && x < 140) EngineeringModeController.INSTANCE.returnToPlayer();
                else if (y >= 29 && x < 214) EngineeringModeController.INSTANCE.toggleCameraPanel();
            }
            return true;
        }
        if (PortableSuperDashboard.isOpen()) {
            if (e.dwheel != 0) PortableSuperDashboard.wheel(x, y, e.dwheel);
            if (e.button >= 0 && e.buttonstate) PortableSuperDashboard.click(x, y, e.button);
            return y >= 50;
        }
        if (showNodes && x >= w - 228 && x < w - 8 && y >= 60 && y < (nodeListExpanded ? nodeListBottom(h) : 82)) {
            if (e.dwheel != 0) nodeScroll += e.dwheel > 0 ? -2 : 2;
            if (press && y < 80) nodeListExpanded = !nodeListExpanded;
            else if (press && y >= 84 && nodeListExpanded) {
                int index = nodeScroll + (y - 84) / 19;
                if (index >= 0 && index < nodeRows.size() && nodeRows.get(index).node != null)
                    selectNode(nodeRows.get(index).node);
            }
            return true;
        }
        int top = h - (details ? 246 : 53);
        if (x >= 8 && x < 298 && y >= top && y < h - 32) {
            if (e.dwheel != 0) detailScroll += e.dwheel > 0 ? -2 : 2;
            if (press && y < top + 20) details = !details;
            else if (press && details && y >= h - 56 && EngineeringModeController.INSTANCE.selected() != null) {
                Target target = EngineeringModeController.INSTANCE.selected();
                if (x < 100) EngineeringModeController.focus(target.x, target.y, target.z);
                else if (x < 186) EngineeringModeController.INSTANCE.togglePin();
                else EngineeringModeController.INSTANCE.clearSelection();
            }
            return true;
        }
        if (y >= h - 27) return true;
        if (press && menu >= 0) {
            menu = -1;
            return true;
        }
        if (selecting && e.button == 0) {
            if (press) {
                double[] p = planePoint(e.x, e.y);
                if (p != null) {
                    dragging = true;
                    dragX = endX = x;
                    dragY = endY = y;
                    dragWorldX = endWorldX = p[0];
                    dragWorldZ = endWorldZ = p[1];
                } else notice = "dash.pick.unavailable";
            }
            return true;
        }
        if (press) for (NodeHit hit : hits) {
            if (Math.abs(x - hit.x) > 10 || Math.abs(y - hit.y) > 10) continue;
            int at = -1;
            for (int i = 0; i < hit.nodes.size(); i++)
                if (selectedNode != null && sameNode(hit.nodes.get(i), selectedNode)) at = i;
            selectNode(hit.nodes.get((at + 1) % hit.nodes.size()));
            return true;
        }
        return false;
    }

    static void cancelAreaDrag() {
        if (dragging) dragging = selecting = false;
    }

    static boolean escape() {
        if (dragging || selecting) {
            dragging = selecting = false;
            notice = "";
            return true;
        }
        if (menu >= 0) {
            menu = -1;
            return true;
        }
        if (PortableSuperDashboard.isOpen()) {
            PortableSuperDashboard.close();
            return true;
        }
        return false;
    }

    private static void menuAction(int action) {
        ItemStack stack = TerminalBaubles.terminals(Minecraft.getMinecraft().thePlayer)[menu];
        if (stack == null || action < 0 || action >= menuLabels().length) {
            menu = -1;
            return;
        }
        int slot = menu;
        menu = -1;
        boolean wasSelecting = selecting;
        pendingGt = false;
        dragging = selecting = false;
        notice = "";
        if (slot == 0) {
            EngineeringDashboardCache.close();
            activeSlot = 0;
            grid = showNodes = false;
            if (action == 0) PortableSuperDashboard.open(stack);
            else TerminalBaubleController.select(slot);
            return;
        }
        PortableSuperDashboard.close();
        if (slot == 2) {
            if (action == 7) {
                EngineeringDashboardCache.close();
                TerminalBaubleController.select(slot);
                return;
            }
            if (action == 6) {
                EngineeringDashboardCache.close();
                activeSlot = -1;
                grid = false;
                return;
            }
            if (action == 5) {
                EngineeringDashboardCache.baseline();
                return;
            }
            if (action == 4) {
                boundOnly = !boundOnly;
                if (activeSlot != 2) defaultRegion();
                subscribe(slot, true);
                return;
            }
            if (action == 3) {
                if (activeSlot != 2) {
                    defaultRegion();
                    subscribe(2, true);
                }
                selecting = !wasSelecting;
                return;
            }
            if (action == 1 || action == 2) {
                EngineeringSnapshot s = EngineeringDashboardCache.snapshot();
                pendingGt = action == 2 && (s == null || !s.patternResolved);
                gtGrid = action == 2 && !pendingGt;
                if (pendingGt) notice = "dash.pattern.unknown";
                defaultRegion();
                subscribe(slot, true);
            } else {
                EngineeringTerminalActions.openGrid(ItemDeviceInfoTerminal.readTerminalId(stack));
                defaultRegion();
                subscribe(slot, false);
            }
        } else {
            if (action == (slot == 1 ? 1 : 2)) {
                EngineeringDashboardCache.close();
                TerminalBaubleController.select(slot);
                return;
            }
            defaultRegion();
            subscribe(slot, false);
            showNodes = slot == 1 || action == 0;
        }
    }

    private static void subscribe(int slot, boolean showGrid) {
        activeSlot = slot;
        grid = showGrid;
        selectedNode = null;
        detailScroll = 0;
        details = true;
        EngineeringModeController.INSTANCE.clearSelection();
        showNodes = slot == 1 || slot >= 3;
        nodeScroll = 0;
        EngineeringDashboardCache.subscribe(slot, gtGrid, minCellX, minCellZ, maxCellX, maxCellZ, boundOnly);
    }

    private static void defaultRegion() {
        Minecraft mc = Minecraft.getMinecraft();
        CameraFrame frame = WorldCameraHooks.frame(EngineeringModeController.OWNER, mc.theWorld);
        double x = frame == null ? mc.thePlayer.posX
            : frame.pose()
                .anchorX(),
            z = frame == null ? mc.thePlayer.posZ
                : frame.pose()
                    .anchorZ();
        planeY = EngineeringModeController.INSTANCE.mapPlaneY();
        EngineeringSnapshot s = EngineeringDashboardCache.snapshot();
        boolean pattern = s != null && s.axisSymmetrical;
        int cx = EngineeringGrid.cellOfBlock((int) Math.floor(x), gtGrid, pattern),
            cz = EngineeringGrid.cellOfBlock((int) Math.floor(z), gtGrid, pattern);
        minCellX = cx - 1;
        minCellZ = cz - 1;
        maxCellX = cx + 1;
        maxCellZ = cz + 1;
    }

    private static double[] planePoint(int displayX, int displayY) {
        Minecraft mc = Minecraft.getMinecraft();
        RayInterval ray = WorldCameraHooks
            .pixelRayInterval(EngineeringModeController.OWNER, mc.theWorld, displayX, displayY);
        if (ray == null || Math.abs(
            ray.ray()
                .directionY())
            < 1e-9) return null;
        double t = (planeY - ray.ray()
            .originY()) / ray.ray()
                .directionY();
        if (!Double.isFinite(t) || t < ray.minDistance() || t > ray.maxDistance()) return null;
        return new double[] { ray.ray()
            .originX()
            + ray.ray()
                .directionX() * t,
            ray.ray()
                .originZ()
                + ray.ray()
                    .directionZ() * t };
    }

    private static void finishSelection() {
        dragging = false;
        EngineeringSnapshot s = EngineeringDashboardCache.snapshot();
        boolean pattern = s != null && s.axisSymmetrical;
        int x1 = EngineeringGrid.cellOfBlock((int) Math.floor(Math.min(dragWorldX, endWorldX)), gtGrid, pattern);
        int z1 = EngineeringGrid.cellOfBlock((int) Math.floor(Math.min(dragWorldZ, endWorldZ)), gtGrid, pattern);
        int x2 = EngineeringGrid.cellOfBlock((int) Math.floor(Math.max(dragWorldX, endWorldX)), gtGrid, pattern);
        int z2 = EngineeringGrid.cellOfBlock((int) Math.floor(Math.max(dragWorldZ, endWorldZ)), gtGrid, pattern);
        if ((long) (x2 - x1 + 1) * (z2 - z1 + 1) > 64) {
            notice = "dash.selection.large";
            return;
        }
        minCellX = x1;
        minCellZ = z1;
        maxCellX = x2;
        maxCellZ = z2;
        selecting = false;
        subscribe(2, true);
    }

    @SubscribeEvent
    public void world(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        Target target = EngineeringModeController.INSTANCE.selected();
        if (!EngineeringModeController.isActive() || target == null || mc.renderViewEntity == null) return;
        Entity view = mc.renderViewEntity;
        double x = view.lastTickPosX + (view.posX - view.lastTickPosX) * event.partialTicks;
        double y = view.lastTickPosY + (view.posY - view.lastTickPosY) * event.partialTicks;
        double z = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * event.partialTicks;
        try (GlScope ignored = new GlScope()) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glLineWidth(2);
            GL11.glColor4f(.4f, .9f, .85f, .9f);
            RenderGlobal.drawOutlinedBoundingBox(
                AxisAlignedBB.getBoundingBox(
                    target.x - .005 - x,
                    target.y - .005 - y,
                    target.z - .005 - z,
                    target.x + 1.005 - x,
                    target.y + 1.005 - y,
                    target.z + 1.005 - z),
                -1);
        }
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.engineering." + key);
    }

    private static void text(FontRenderer f, String value, int x, int y, int color, int width) {
        f.drawString(f.trimStringToWidth(value == null ? "" : value, Math.max(0, width)), x, y, color);
    }

    private static void button(FontRenderer f, int x, int y, int width, String value) {
        rect(x, y, x + width, y + 17, CARD);
        rect(x, y + 16, x + width, y + 17, 0x805CA9AC);
        text(f, value, x + 5, y + 5, TEXT, width - 10);
    }

    private static void outline(int x, int y, int right, int bottom, int color) {
        rect(x, y, right, y + 1, color);
        rect(x, bottom - 1, right, bottom, color);
        rect(x, y, x + 1, bottom, color);
        rect(right - 1, y, right, bottom, color);
    }

    private static void rect(int x, int y, int right, int bottom, int color) {
        Gui.drawRect(x, y, right, bottom, color);
    }

    private static final class NodeHit {

        final int x, y;
        final List<Node> nodes = new ArrayList<>();

        NodeHit(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class NodeRow {

        final String label;
        final Node node;

        NodeRow(String label, Node node) {
            this.label = label;
            this.node = node;
        }
    }
}
