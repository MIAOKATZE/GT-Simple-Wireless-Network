package com.miaokatze.gtswn.client.engineering;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.engineering.EngineeringModeController.Target;
import com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringGrid;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Cell;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Node;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Power;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.NodePresentation;
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

/** Factory world markers and workbench. Terminal actions stay in the same non-modal world HUD. */
public final class EngineeringHud extends Gui {

    private static final int BACK = 0xEC141E27, HEADER = 0xF0263946;
    private static final int ACCENT = 0xFF67D8CD, TEXT = 0xFFE2EEF4, MUTED = 0xFF92A7B4;
    private static final int LINK = 0xFFF1BD70, QUANTUM = 0xFF8EB2FF, INC = 0xFFDA97F1;
    private static int activeSlot = -1;
    private static boolean grid, gtGrid, selecting, dragging, boundOnly;
    private static final FactoryPowerSelection powerSelection = new FactoryPowerSelection();
    private static int minCellX, minCellZ, maxCellX, maxCellZ;
    private static double dragWorldX, dragWorldZ, endWorldX, endWorldZ, planeY;
    private static String notice = "";
    private static Node selectedNode;
    private static final List<NodeHit> hits = new ArrayList<>();
    private static boolean showNodes;
    private static boolean pendingGt;
    private static boolean overview, suspended;
    private static Source activeSource = Source.EMPTY;

    static void reset() {
        activeSlot = -1;
        overview = suspended = false;
        activeSource = Source.EMPTY;
        WorkbenchHud.reset();
        grid = gtGrid = selecting = dragging = boundOnly = false;
        powerSelection.reset();
        selectedNode = null;
        notice = "";
        hits.clear();
        showNodes = false;
        pendingGt = false;
        PortableSuperDashboard.close();
        EngineeringDashboardCache.close();
    }

    static void rememberPreferences() {
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.OVERVIEW, overview);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.SUSPENDED, suspended);
        if (!overview && activeSlot >= 0)
            FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.ACTIVE_SLOT, activeSlot);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.BOUND_ONLY, boundOnly);
    }

    static void restorePreferences() {
        reset();
        boundOnly = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.BOUND_ONLY);
        overview = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.OVERVIEW);
        suspended = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.SUSPENDED);
        if (suspended) return;
        if (overview) activateOverviewSource();
        else {
            int slot = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.ACTIVE_SLOT);
            if (slot < 0) return;
            activeSlot = slot;
            activateData(slot);
        }
    }

    private static void restorePowerPreferences() {
        powerSelection.reset();
        int requested = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.GRID);
        if (requested > 0)
            powerSelection.grid(requested == 2 ? FactoryPowerSelection.Grid.GT : FactoryPowerSelection.Grid.MC);
        if (FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.AUDIT)) powerSelection.toggleAudit();
        pendingGt = powerSelection.gt() && !EngineeringDashboardCache.patternResolved();
        syncPowerSelection();
    }

    public static double scale(int width, int height) {
        return Math.max(.1, Math.min(1, Math.min((width - 16) / 480.0, (height - 12) / 300.0)));
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
        try (GlScope ignored = new GlScope()) {
            double s = canvas(mc).scale();
            GL11.glScaled(s, s, 1);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            if (WorkbenchHud.worldView()) drawMap(mc.fontRenderer, w, h);
            WorkbenchHud.render(w, h, localX(mc, Mouse.getX()), localY(mc, Mouse.getY()));
            if (selecting || !notice.isEmpty()) {
                String hint = tr(notice.isEmpty() ? "dash.selection.hint" : notice);
                int hintWidth = Math.min(w - 32, mc.fontRenderer.getStringWidth(hint) + 16);
                rect((w - hintWidth) / 2, h - 25, (w + hintWidth) / 2, h - 7, BACK);
                text(mc.fontRenderer, hint, (w - hintWidth) / 2 + 8, h - 20, ACCENT, hintWidth - 16);
            }
        }
    }

    /** Default factory overview reads existing data without changing a terminal's visualization setting. */
    public static void activateOverview() {
        overview = true;
        suspended = false;
        activeSlot = -1;
        pendingGt = selecting = dragging = grid = gtGrid = false;
        powerSelection.reset();
        notice = "";
        activateOverviewSource();
    }

    private static void activateOverviewSource() {
        Minecraft mc = Minecraft.getMinecraft();
        int preferred = -1;
        if (EngineeringTerminalSources.resolve(mc.thePlayer, 2)
            .present()) preferred = 2;
        else if (EngineeringTerminalSources.resolve(mc.thePlayer, 0)
            .present()) preferred = 0;
        else for (int i = 1; i < 8; i++) if (EngineeringTerminalSources.resolve(mc.thePlayer, i)
            .present()) {
                preferred = i;
                break;
            }
        if (preferred < 0) {
            EngineeringDashboardCache.close();
            activeSlot = -1;
            activeSource = Source.EMPTY;
            grid = showNodes = false;
            return;
        }
        activateData(preferred);
    }

    public static void activateTerminal(int slot) {
        if (slot < 0 || slot > 7) return;
        Source source = EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, slot);
        if (source.stack == null) return;
        overview = false;
        suspended = false;
        WorkbenchHud.select(slot);
        if (source.present()) activateData(slot);
        else {
            EngineeringDashboardCache.close();
            activeSlot = slot;
            activeSource = source;
            powerSelection.reset();
            pendingGt = false;
            syncPowerSelection();
            grid = showNodes = false;
        }
    }

    static void activateData(int slot) {
        Source source = EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, slot);
        if (!source.present()) return;
        if (!suspended && activeSlot == slot && activeSource.sameIdentity(source)) return;
        suspended = false;
        PortableSuperDashboard.close();
        if (slot == 2) restorePowerPreferences();
        else {
            powerSelection.reset();
            pendingGt = false;
            syncPowerSelection();
        }
        defaultRegion();
        subscribe(slot, slot == 2 && grid);
    }

    static void useTaskSource(int slot) {
        overview = false;
        if (slot < 0 && activeSlot < 0 && suspended) return;
        if (slot < 0 || !EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, slot)
            .present()) {
            suspended = true;
            activeSlot = -1;
            activeSource = Source.EMPTY;
            EngineeringDashboardCache.close();
            return;
        }
        activateData(slot);
    }

    static void useOverviewSource() {
        overview = true;
        suspended = false;
        activateOverviewSource();
    }

    /** Source replacement/removal invalidates the previous subscription before choosing a new overview source. */
    public static void tickSources() {
        EngineeringSnapshot ready = EngineeringDashboardCache.snapshot();
        if (pendingGt && ready != null && ready.slot == 2 && ready.patternResolved) {
            pendingGt = false;
            if ("dash.pattern.unknown".equals(notice)) notice = "";
            syncPowerSelection();
            defaultRegion();
            subscribe(2, true);
        }
        if (suspended) return;
        if (overview && activeSlot < 0) {
            activateOverviewSource();
            return;
        }
        if (overview && activeSlot != 2
            && EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, 2)
                .present()) {
            activateOverviewSource();
            return;
        }
        Source current = EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, activeSlot);
        if (activeSource.sameIdentity(current) || !activeSource.present() && !current.present()) return;
        PortableSuperDashboard.close();
        if (overview) activateOverviewSource();
        else if (current.present()) {
            if (activeSlot == 2) restorePowerPreferences();
            else powerSelection.clearCustom();
            syncPowerSelection();
            defaultRegion();
            subscribe(activeSlot, grid);
        } else {
            EngineeringDashboardCache.close();
            activeSource = Source.EMPTY;
            powerSelection.reset();
            pendingGt = false;
            syncPowerSelection();
            grid = showNodes = false;
            selectedNode = null;
        }
    }

    public static String[] toolLabels(int slot) {
        if (slot == 0) return new String[] { tr("dash.board"), tr("tools.inspect") };
        if (slot == 1) return new String[] { tr("dash.link.map"), tr("tools.inspect"), tr("tools.stop") };
        if (slot == 2) return new String[] { tr("tools.inspect"), tr("dash.grid.mc"), tr("dash.grid.gt"),
            tr(selecting ? "dash.select.off" : "dash.select.on"), tr(boundOnly ? "dash.scope.bound" : "dash.scope.all"),
            tr("dash.baseline"), tr("dash.stop") };
        return new String[] { tr("dash.quantum.map"), tr("dash.quantum.info"), tr("tools.stop") };
    }

    static int activeSlot() {
        return activeSlot;
    }

    static boolean mapVisible() {
        return grid || showNodes;
    }

    public static boolean gridMcActive() {
        return activeSlot == 2 && powerSelection.visible() && !powerSelection.gt();
    }

    public static boolean gridGtActive() {
        return activeSlot == 2 && powerSelection.gt();
    }

    public static boolean customSelectionActive() {
        return activeSlot == 2 && powerSelection.custom();
    }

    public static boolean areaSelecting() {
        return activeSlot == 2 && powerSelection.selecting();
    }

    public static boolean auditActive() {
        return activeSlot == 2 && powerSelection.audit();
    }

    public static boolean boundScopeActive() {
        return activeSlot == 2 && boundOnly;
    }

    /** Audit uses the current subscription and comparison baseline. */
    public static void toggleAudit() {
        Source source = EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, 2);
        if (!source.present()) return;
        if (activeSlot != 2 || suspended) activateData(2);
        overview = false;
        boolean restore = powerSelection.toggleAudit();
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.AUDIT, powerSelection.audit());
        syncPowerSelection();
        notice = "";
        if (restore) restoreDefaultRegion();
        if (powerSelection.audit()) WorkbenchHud.inspect();
    }

    private static void syncPowerSelection() {
        gtGrid = powerSelection.gt();
        grid = powerSelection.visible() && !pendingGt;
        selecting = powerSelection.selecting();
        dragging = powerSelection.dragging();
    }

    private static void restoreDefaultRegion() {
        defaultRegion();
        if (activeSlot == 2) subscribe(2, grid);
    }

    static List<String> dataLines() {
        return detailLines();
    }

    static List<String> targetLines() {
        return selectedLines();
    }

    static void toggleMap() {
        if (activeSlot < 0) return;
        if (activeSlot == 2) {
            toolAction(2, gtGrid ? 2 : 1);
        } else if (activeSlot > 0) {
            showNodes = !showNodes;
            FactoryDisplayPreferences.put(FactoryDisplayPreferences.nodes(activeSlot), showNodes);
        }
    }

    static void stopData() {
        suspended = true;
        EngineeringDashboardCache.close();
        activeSource = Source.EMPTY;
        activeSlot = -1;
        grid = showNodes = pendingGt = dragging = selecting = false;
        powerSelection.reset();
        selectedNode = null;
        PortableSuperDashboard.close();
        notice = "";
    }

    public static void clearTarget() {
        selectedNode = null;
        EngineeringModeController.INSTANCE.clearSelection();
    }

    public static void inspect(int slot) {
        if (slot >= 0 && activeSlot != slot) activateData(slot);
        WorkbenchHud.inspect();
    }

    public static List<String> inspectionLines() {
        List<String> lines = selectedLines();
        lines.addAll(detailLines());
        return lines;
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
                if (powerSelection.audit() && s.review != null) {
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
        }
        return lines;
    }

    private static boolean sameNode(Node a, Node b) {
        return NodePresentation.sameIdentity(a, b);
    }

    private static Node currentNode(EngineeringSnapshot s) {
        Target target = EngineeringModeController.INSTANCE.selected();
        if (selectedNode != null && (target == null
            || target.x == selectedNode.x && target.y == selectedNode.y && target.z == selectedNode.z))
            for (Node node : s.nodes) if (sameNode(node, selectedNode)) return node;
        if (target != null) for (Node node : s.nodes)
            if (node.dim == Minecraft.getMinecraft().theWorld.provider.dimensionId && node.x == target.x
                && node.y == target.y
                && node.z == target.z) return node;
        return null;
    }

    private static List<String> selectedLines() {
        List<String> lines = new ArrayList<>();
        Target target = EngineeringModeController.INSTANCE.selected();
        EngineeringSnapshot s = EngineeringDashboardCache.snapshot();
        Node n = s == null ? null : currentNode(s);
        if (n != null) {
            lines.add(
                nodeRole(n) + " · "
                    + tr(!EngineeringDashboardCache.fresh() ? "stale" : n.online ? "dash.online" : "dash.offline"));
            lines.add(n.x + " / " + n.y + " / " + n.z + " · DIM " + n.dim);
            lines.add(tr("dash.owner") + " " + (n.ownerName.isEmpty() ? tr("dash.unknown") : n.ownerName));
            if (n.kind == 1) {
                lines.add(
                    tr("tools.face." + n.name.toLowerCase(java.util.Locale.ROOT)) + " · "
                        + n.voltage
                        + " V × "
                        + metric(n.amperage)
                        + " A");
                lines.add(tr("dash.stored") + " " + metric(n.stored) + "/" + metric(n.capacity) + " EU");
            } else lines.add(tr("dash.channels") + " " + n.channels);
        } else if (target != null) {
            lines.add(target.name);
            lines.add(target.x + " / " + target.y + " / " + target.z);
            boolean found = false;
            EngineeringTerminalHudSnapshot.Source source = EngineeringTerminalHudSnapshot
                .capture(Minecraft.getMinecraft().thePlayer).device;
            for (Entry e : source.devices) {
                if (e.dim != Minecraft.getMinecraft().theWorld.provider.dimensionId || e.x != target.x
                    || e.y != target.y
                    || e.z != target.z) continue;
                found = true;
                lines.add(
                    tr("state." + Math.max(0, Math.min(3, e.state))) + " · "
                        + tr(source.fresh ? "dash.server.live" : "stale"));
                lines.add(metric(e.instIn) + " → " + metric(e.instOut) + " EU/t");
                if (!e.recipeIn.isEmpty()) lines.add(e.recipeIn);
                if (!e.recipeOut.isEmpty()) lines.add(e.recipeOut);
                break;
            }
            if (!found) lines.add(tr("dash.target.unknown"));
        }
        return lines;
    }

    static String nodeRole(Node node) {
        return tr(
            node.kind == 1 ? node.mode == 0 ? "tools.energy" : "tools.dynamo"
                : node.kind == 2 ? "dash.legend.quantum" : "dash.legend.inc");
    }

    private static String power(Power p, boolean average) {
        return p == null ? tr("dash.unknown")
            : metric(average ? p.avgIn : p.instIn) + " → " + metric(average ? p.avgOut : p.instOut) + " EU/t";
    }

    private static String metric(double value) {
        return FormatUtil.formatMetricDouble(value);
    }

    static void selectNode(Node node) {
        selectedNode = node;
        EngineeringModeController controller = EngineeringModeController.INSTANCE;
        if (node.dim == Minecraft.getMinecraft().theWorld.provider.dimensionId)
            controller.selectPosition(node.x, node.y, node.z, node.name);
        else controller.clearSelection();
    }

    private static void drawMap(FontRenderer f, int w, int h) {
        hits.clear();
        if (PortableSuperDashboard.isOpen()) return;
        EngineeringSnapshot s = EngineeringDashboardCache.snapshot();
        FactoryPowerSelection.Region region = dragging ? selectionRegion()
            : new FactoryPowerSelection.Region(minCellX, minCellZ, maxCellX, maxCellZ);
        FactoryPowerPalette palette = FactoryPowerPalette
            .forRegion(s, EngineeringDashboardCache.fresh(), region != null && region.valid(gtGrid) ? region : null);
        if (s == null) {
            if (dragging) drawSelectionPreview(f, w, h, null, region, palette);
            return;
        }
        if (grid) for (Cell c : s.cells) {
            // Draw selected cells exactly once, with the preview's current range and opacity.
            if (!dragging) {
                drawPowerCell(f, w, h, c, s.axisSymmetrical, planeY, palette);
                continue;
            }
            if (palette.contains(c)) continue;
            int x1 = EngineeringGrid.minBlock(c.x, gtGrid, s.axisSymmetrical),
                z1 = EngineeringGrid.minBlock(c.z, gtGrid, s.axisSymmetrical);
            int x2 = EngineeringGrid.maxBlockExclusive(c.x, gtGrid, s.axisSymmetrical),
                z2 = EngineeringGrid.maxBlockExclusive(c.z, gtGrid, s.axisSymmetrical);
            polygon(x1, z1, x2, z2, planeY, w, h, FactoryPowerPalette.OUTSIDE);
        }
        if (dragging) drawSelectionPreview(f, w, h, s, region, palette);
        else if (grid) drawPowerLegend(f, powerOverlayArea(w, h), palette);
        if (!showNodes) return;
        List<StableNodeClusters.Projection> projected = new ArrayList<>();
        Minecraft mc = Minecraft.getMinecraft();
        for (Node n : s.nodes) {
            if (n.dim != mc.theWorld.provider.dimensionId) continue;
            ProjectedPoint p = WorldCameraHooks
                .projectWorld(EngineeringModeController.OWNER, mc.theWorld, n.x + .5, n.y + .5, n.z + .5);
            if (p == null || Math.abs(p.ndcZ()) > 1) continue;
            projected.add(new StableNodeClusters.Projection(n, (p.ndcX() + 1) * w / 2, (1 - p.ndcY()) * h / 2));
        }
        for (StableNodeClusters.Cluster cluster : nodeClusters
            .update(s.sessionId, mc.theWorld.provider.dimensionId, w, h, projected)) {
            NodeHit hit = new NodeHit(cluster);
            if (hit.x < 10 || hit.x >= w - 10 || hit.y < 10 || hit.y > h - 10) continue;
            Node n = hit.nodes.get(0);
            int color = !EngineeringDashboardCache.fresh() || !cluster.allOnline ? MUTED
                : n.kind == 1 ? LINK : n.kind == 2 ? QUANTUM : INC;
            rect(hit.x - 7, hit.y - 7, hit.x + 8, hit.y + 8, BACK);
            outline(hit.x - 7, hit.y - 7, hit.x + 8, hit.y + 8, color);
            // Role comes from the server's node kind/mode, never an invented L/Q placeholder.
            String label = nodeRole(n) + (hit.nodes.size() > 1 ? " ×" + hit.nodes.size() : "");
            text(f, label, hit.x + 12, hit.y - 4, color, 112);
            rect(hit.x - 3, hit.y - 3, hit.x + 4, hit.y + 4, color);
            hits.add(hit);
        }
    }

    private static int[] project(double x, double y, double z, int w, int h) {
        Minecraft mc = Minecraft.getMinecraft();
        ProjectedPoint p = WorldCameraHooks.projectWorld(EngineeringModeController.OWNER, mc.theWorld, x, y, z);
        return p == null || Math.abs(p.ndcZ()) > 1 ? null
            : new int[] { (int) ((p.ndcX() + 1) * w / 2), (int) ((1 - p.ndcY()) * h / 2) };
    }

    private static void drawPowerCell(FontRenderer f, int w, int h, Cell cell, boolean axis, double y,
        FactoryPowerPalette palette) {
        int x1 = EngineeringGrid.minBlock(cell.x, gtGrid, axis), z1 = EngineeringGrid.minBlock(cell.z, gtGrid, axis);
        int x2 = EngineeringGrid.maxBlockExclusive(cell.x, gtGrid, axis),
            z2 = EngineeringGrid.maxBlockExclusive(cell.z, gtGrid, axis);
        polygon(x1, z1, x2, z2, y, w, h, palette.cellColor(cell));
        int[] p = project((x1 + x2) / 2.0, y, (z1 + z2) / 2.0, w, h);
        if (p == null || p[0] <= 10 || p[0] >= w - 10 || p[1] <= 10 || p[1] >= h - 10) return;
        text(f, cell.x + "," + cell.z, p[0] - 22, p[1] - 10, MUTED, 90);
        text(
            f,
            palette.known(cell) ? metric(FactoryPowerPalette.net(cell)) + " EU/t" : "?",
            p[0] - 22,
            p[1] + 2,
            TEXT,
            100);
    }

    private static WorkbenchLayout.Rect powerOverlayArea(int w, int h) {
        WorkbenchLayout layout = WorkbenchHud.layout(w, h);
        if (layout.dashboard != null) return null;
        int left = layout.sidebar == null ? 43 : layout.sidebar.x + layout.sidebar.w + 7;
        int right = w - 42;
        if (layout.rail != null) right = Math.min(right, layout.rail.x - 8);
        if (layout.power != null) right = Math.min(right, layout.power.x - 8);
        int bottom = h - (selecting || !notice.isEmpty() ? 29 : 8);
        if (layout.alerts != null) bottom = Math.min(bottom, layout.alerts.y - 5);
        return new WorkbenchLayout.Rect(left, 56, right - left, bottom - 56);
    }

    private static void drawPowerLegend(FontRenderer f, WorkbenchLayout.Rect area, FactoryPowerPalette palette) {
        if (area == null || area.w < 80) return;
        String label = StatCollector.translateToLocalFormatted(
            "gtswn.engineering.dash.power.net.range",
            palette.hasValues ? metric(palette.min) : "?",
            palette.hasValues ? metric(palette.max) : "?");
        String unknown = tr("dash.unknown");
        int boxWidth = Math.min(area.w, Math.max(220, f.getStringWidth(label) + 16));
        List<String> lines = f.listFormattedStringToWidth(label, boxWidth - 16);
        int boxHeight = lines.size() * 10 + 17;
        if (boxHeight > area.h) return;
        int left = area.x + (area.w - boxWidth) / 2, top = area.y + area.h - boxHeight;
        rect(left, top, left + boxWidth, top + boxHeight, BACK);
        for (int i = 0; i < lines.size(); i++) text(f, lines.get(i), left + 8, top + 4 + i * 10, TEXT, boxWidth - 16);
        int barY = top + boxHeight - 10;
        int barLeft = left + 8, barRight = left + boxWidth - f.getStringWidth(unknown) - 28;
        for (int x = barLeft; x < barRight; x++) {
            int color = palette.colorAt((x - barLeft) / (double) Math.max(1, barRight - barLeft - 1));
            rect(x, barY, x + 1, barY + 6, color);
        }
        rect(barRight + 7, barY, barRight + 13, barY + 6, 0xFF4B5F6B);
        text(f, unknown, barRight + 17, barY - 2, MUTED, boxWidth - 16);
    }

    private static void drawSelectionPreview(FontRenderer f, int w, int h, EngineeringSnapshot s,
        FactoryPowerSelection.Region region, FactoryPowerPalette palette) {
        if (region == null) return;
        boolean valid = region.valid(gtGrid);
        boolean axis = EngineeringDashboardCache.axisSymmetrical();
        boolean incomplete = s != null && s.review != null
            && (s.review.discovering || s.review.loadedChunks < s.review.totalChunks);
        int sampled = 0, monitored = 0, unknown = 0;
        Power sampledPower = Power.ZERO;
        if (valid) {
            for (int cx = region.minX; cx <= region.maxX; cx++) for (int cz = region.minZ; cz <= region.maxZ; cz++) {
                Cell sampledCell = null;
                if (s != null) for (Cell cell : s.cells) if (cell.x == cx && cell.z == cz) {
                    sampledCell = cell;
                    break;
                }
                if (sampledCell == null) polygon(
                    EngineeringGrid.minBlock(cx, gtGrid, axis),
                    EngineeringGrid.minBlock(cz, gtGrid, axis),
                    EngineeringGrid.maxBlockExclusive(cx, gtGrid, axis),
                    EngineeringGrid.maxBlockExclusive(cz, gtGrid, axis),
                    planeY + .02,
                    w,
                    h,
                    FactoryPowerPalette.UNKNOWN);
                else drawPowerCell(f, w, h, sampledCell, axis, planeY + .02, palette);
                if (sampledCell != null) {
                    sampled++;
                    monitored += sampledCell.monitored;
                    unknown += sampledCell.unknown;
                    if (sampledCell.power != null) sampledPower = sampledPower.plus(sampledCell.power);
                }
            }
        }
        String range = tr(gtGrid ? "dash.grid.gt" : "dash.grid.mc") + " | "
            + region.count()
            + " "
            + tr("dash.selection.cells");
        if (valid) range += " | X " + EngineeringGrid.minBlock(region.minX, gtGrid, axis)
            + ".."
            + (EngineeringGrid.maxBlockExclusive(region.maxX, gtGrid, axis) - 1)
            + " / Z "
            + EngineeringGrid.minBlock(region.minZ, gtGrid, axis)
            + ".."
            + (EngineeringGrid.maxBlockExclusive(region.maxZ, gtGrid, axis) - 1)
            + " | Y "
            + (int) planeY;
        else range += " | " + tr("dash.selection.large");
        String content = tr("dash.selection.sampled") + " " + sampled + "/" + region.count();
        if (sampled > 0) content += " | " + tr("dash.coverage")
            + " "
            + monitored
            + " | "
            + tr("dash.unknown")
            + " "
            + unknown
            + " | "
            + power(sampledPower, false);
        if (sampled < region.count()) content += " | " + tr("dash.selection.unsampled");
        if (incomplete) content += " | " + tr("dash.discovering");
        if (s != null && !EngineeringDashboardCache.fresh()) content += " | " + tr("stale");
        if (s != null && !"ok".equals(s.status)) content += " | " + tr("dash.status." + s.status);
        WorkbenchLayout.Rect area = powerOverlayArea(w, h);
        if (area == null || area.w < 80 || area.h < 26) return;
        int boxWidth = Math.min(area.w, Math.max(f.getStringWidth(range), f.getStringWidth(content)) + 16);
        int left = area.x + (area.w - boxWidth) / 2, top = area.y + area.h - 26;
        rect(left, top, left + boxWidth, top + 26, BACK);
        text(f, range, left + 8, top + 3, valid ? ACCENT : LINK, boxWidth - 16);
        text(f, content, left + 8, top + 14, MUTED, boxWidth - 16);
        drawPowerLegend(f, new WorkbenchLayout.Rect(area.x, area.y, area.w, area.h - 30), palette);
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
        GL11.glColor4f(.7f, .76f, .8f, .35f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int[] p : points) GL11.glVertex2i(p[0], p[1]);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** Core raw navigation precedes Forge; supports() consults this hit test before capturing a pointer. */
    static boolean blocksNavigation(int displayX, int displayY) {
        Minecraft mc = Minecraft.getMinecraft();
        int x = localX(mc, displayX), y = localY(mc, displayY), w = width(mc), h = height(mc);
        return WorkbenchHud.layout(w, h)
            .contains(x, y);
    }

    static boolean mouse(MouseEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        int x = localX(mc, e.x), y = localY(mc, e.y), w = width(mc), h = height(mc);
        boolean press = e.button == 0 && e.buttonstate;
        if (dragging) {
            double[] p = planePoint(e.x, e.y);
            if (p != null) {
                endWorldX = p[0];
                endWorldZ = p[1];
            }
            if (e.button == 0 && !e.buttonstate) finishSelection();
            return true;
        }
        if (WorkbenchHud.mouse(x, y, w, h, e.button, e.buttonstate, e.dwheel)) return true;
        if (selecting && e.button == 0) {
            if (press) {
                double[] p = planePoint(e.x, e.y);
                if (p != null) {
                    powerSelection.beginDrag();
                    syncPowerSelection();
                    dragWorldX = endWorldX = p[0];
                    dragWorldZ = endWorldZ = p[1];
                } else notice = "dash.pick.unavailable";
            }
            return true;
        }
        if (selecting) return true;
        if (press) {
            List<Node> clicked = new ArrayList<>();
            for (NodeHit hit : hits)
                if (Math.abs(x - hit.x) <= 10 && Math.abs(y - hit.y) <= 10) clicked.addAll(hit.nodes);
            if (!clicked.isEmpty()) {
                // Distinct owner/role groups may overlap on screen; every current member remains reachable.
                clicked.sort(NodePresentation.ORDER);
                int at = -1;
                for (int i = 0; i < clicked.size(); i++)
                    if (selectedNode != null && sameNode(clicked.get(i), selectedNode)) at = i;
                selectNode(clicked.get((at + 1) % clicked.size()));
                return true;
            }
        }
        return false;
    }

    static void cancelAreaDrag() {
        if (dragging || selecting) cancelCustomSelection();
    }

    private static void cancelCustomSelection() {
        if (!powerSelection.clearCustom()) return;
        syncPowerSelection();
        notice = "";
        restoreDefaultRegion();
    }

    static boolean escape() {
        if (dragging || selecting) {
            cancelCustomSelection();
            return true;
        }
        return WorkbenchHud.escape();
    }

    public static void toolAction(int slot, int action) {
        Source source = EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, slot);
        if (!source.present() || action < 0 || action >= toolLabels(slot).length) return;
        if (slot == 1 && action == 2 || slot >= 3 && action == 2 || slot == 2 && action == 6) {
            stopData();
            return;
        }
        suspended = false;
        overview = false;
        notice = "";
        if (slot == 0) {
            if (action == 0) {
                activateTerminal(0);
                WorkbenchHud.openDashboard();
            } else activateData(0);
        } else if (slot == 2) {
            if (activeSlot != 2) activateData(2);
            if (action == 5) {
                EngineeringDashboardCache.baseline();
                WorkbenchHud.inspect();
            } else if (action == 4) {
                boundOnly = !boundOnly;
                subscribe(2, grid);
            } else if (action == 3) {
                EngineeringSnapshot s = EngineeringDashboardCache.snapshot();
                if (!powerSelection.custom() && !selecting && s != null && "denied".equals(s.status)) {
                    notice = "dash.status.denied";
                    return;
                }
                FactoryPowerSelection.Change change = powerSelection
                    .custom(!pendingGt && EngineeringDashboardCache.patternResolved());
                syncPowerSelection();
                if (change == FactoryPowerSelection.Change.DEFAULT_REGION) restoreDefaultRegion();
                else if (change == FactoryPowerSelection.Change.SELECTING) {
                    planeY = EngineeringModeController.INSTANCE.mapPlaneY();
                    clearTarget();
                } else if (change == FactoryPowerSelection.Change.NEED_GRID) notice = "dash.selection.need.grid";
                else if (change == FactoryPowerSelection.Change.NEED_PATTERN) notice = "dash.pattern.unknown";
            } else if (action == 1 || action == 2) {
                FactoryPowerSelection.Change change = powerSelection
                    .grid(action == 2 ? FactoryPowerSelection.Grid.GT : FactoryPowerSelection.Grid.MC);
                FactoryDisplayPreferences.put(
                    FactoryDisplayPreferences.Key.GRID,
                    powerSelection.visible() ? powerSelection.gt() ? 2 : 1 : 0);
                pendingGt = powerSelection.gt() && !EngineeringDashboardCache.patternResolved();
                syncPowerSelection();
                if (pendingGt) notice = "dash.pattern.unknown";
                if (change == FactoryPowerSelection.Change.DEFAULT_REGION) restoreDefaultRegion();
            } else WorkbenchHud.inspect();
        } else if (action == 0) {
            if (activeSlot == slot) showNodes = !showNodes;
            else {
                defaultRegion();
                subscribe(slot, false);
                showNodes = true;
            }
            FactoryDisplayPreferences.put(FactoryDisplayPreferences.nodes(slot), showNodes);
        } else {
            activateData(slot);
            WorkbenchHud.inspect();
        }
    }

    private static void subscribe(int slot, boolean showGrid) {
        if (slot != 2) {
            powerSelection.reset();
            pendingGt = false;
            syncPowerSelection();
        }
        activeSlot = slot;
        activeSource = EngineeringTerminalSources.resolve(Minecraft.getMinecraft().thePlayer, slot);
        grid = showGrid;
        selectedNode = null;
        EngineeringModeController.INSTANCE.clearSelection();
        FactoryDisplayPreferences.Key nodes = FactoryDisplayPreferences.nodes(slot);
        showNodes = nodes != null && FactoryDisplayPreferences.flag(nodes);
        EngineeringDashboardCache
            .subscribe(slot, gtGrid && !pendingGt, minCellX, minCellZ, maxCellX, maxCellZ, boundOnly);
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
        boolean pattern = EngineeringDashboardCache.axisSymmetrical();
        int cx = EngineeringGrid.cellOfBlock((int) Math.floor(x), gtGrid && !pendingGt, pattern),
            cz = EngineeringGrid.cellOfBlock((int) Math.floor(z), gtGrid && !pendingGt, pattern);
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
        FactoryPowerSelection.Region region = selectionRegion();
        if (!powerSelection.finish(region)) {
            syncPowerSelection();
            notice = region == null ? "dash.pattern.unknown" : "dash.selection.large";
            return;
        }
        syncPowerSelection();
        minCellX = region.minX;
        minCellZ = region.minZ;
        maxCellX = region.maxX;
        maxCellZ = region.maxZ;
        notice = "";
        subscribe(2, true);
    }

    private static FactoryPowerSelection.Region selectionRegion() {
        if (pendingGt || gtGrid && !EngineeringDashboardCache.patternResolved()) return null;
        return FactoryPowerSelection
            .region(dragWorldX, dragWorldZ, endWorldX, endWorldZ, gtGrid, EngineeringDashboardCache.axisSymmetrical());
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
        final List<Node> nodes;

        NodeHit(StableNodeClusters.Cluster cluster) {
            x = cluster.x;
            y = cluster.y;
            nodes = cluster.nodes;
        }
    }

    private static final StableNodeClusters nodeClusters = new StableNodeClusters();

}
