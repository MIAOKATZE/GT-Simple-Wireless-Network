package com.miaokatze.gtswn.client;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.StatCollector;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.engineering.EngineeringHud;
import com.miaokatze.gtswn.client.engineering.EngineeringModeController;
import com.miaokatze.gtswn.client.engineering.WorkbenchLayout;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.miaokatzecore.api.camera.ProjectedPoint;
import com.miaokatze.miaokatzecore.client.camera.WorldCameraHooks;
import com.miaokatze.miaokatzecore.client.render.GlScope;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Bound-machine travel markers. All snapshot work is sliced on client ticks; render reads cached text only. */
public final class DeviceTerminalAnchorRenderer {

    private static final int WORK_PER_TICK = 32;
    private static final Comparator<Anchor> NEAREST = Comparator.comparingDouble((Anchor anchor) -> anchor.distance)
        .thenComparing(anchor -> anchor.entry.key);
    private static final String[] STATES = { "idle", "running", "stopped", "ignored" };
    // The constructor creates a queue using NEAREST, so initialize its dependencies first.
    public static final DeviceTerminalAnchorRenderer INSTANCE = new DeviceTerminalAnchorRenderer();
    private final List<Anchor> visible = new ArrayList<>();
    private final List<Anchor> drawn = new ArrayList<>(512);
    private final DevicePowerClusterLayout factoryClusters = new DevicePowerClusterLayout();
    public int lastRenderedMarkers;
    public int lastRenderedLabels;
    public int lastMarkerBatches;
    public int lastProjectedLabels;
    private final FloatBuffer modelviewBuffer = ByteBuffer.allocateDirect(16 * Float.BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer();
    private final FloatBuffer projectionBuffer = ByteBuffer.allocateDirect(16 * Float.BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer();
    private final IntBuffer viewportBuffer = ByteBuffer.allocateDirect(16 * Integer.BYTES)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer();
    private final float[] labelModelview = new float[16];
    private final float[] labelProjection = new float[16];
    private final double[] labelTransform = new double[16];
    private final int[] labelViewport = new int[4];
    private final double[] candidateRectangle = new double[4];
    private final double[] acceptedRectangles = new double[64 * 4];
    private final PriorityQueue<Anchor> building = new PriorityQueue<>(NEAREST.reversed());
    private DeviceTerminalClientCache.Snapshot scanning;
    private DeviceTerminalClientCache.Snapshot displayed;
    private UUID terminal;
    private Anchor target;
    private int cursor;
    private int refreshCursor;
    private int availabilityRemaining;
    private int nextAvailabilityTick;
    private long viewGeneration;
    private long dataEpoch;
    private boolean buildStructureChanged;
    private boolean buildSnapshotChanged;
    private final Map<String, Anchor> cached = new HashMap<>();
    private Map<String, Entry> boundIdentity = new HashMap<>();
    private Map<String, Entry> buildingIdentity = new HashMap<>();
    private int ticks;
    private int scanDimension;
    private int scanRange;
    private int scanHeatSize;
    private double scanSourceRange;
    private int scanLimit;
    private final HudText hudHint = new HudText();
    private final HudText hudNotice = new HudText();
    private final HudText hudCount = new HudText();
    private double scanX;
    private double scanY;
    private double scanZ;

    private DeviceTerminalAnchorRenderer() {}

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            reset();
            DeviceTerminalVisualizationState.clear();
            return;
        }
        UUID held = DeviceTerminalVisualizationState.presentationTerminalId();
        if (!DeviceTerminalVisualizationState.presentationActive(held)) {
            reset();
            return;
        }
        if (terminal != null && !terminal.equals(held)) reset();
        terminal = held;
        DeviceTerminalVisualConfig.load();
        // Screens poll their own terminal UUID; persistent inventory mode needs no GUI-time network lease.
        if (!DeviceTerminalVisualizationState.factoryPresentation() && mc.currentScreen == null
            && (ticks % 20 == 0 || DeviceTerminalClientCache.hasMorePending(terminal) && ticks % 5 == 0)) {
            GTSWNPacketHandler.NETWORK.sendToServer(DeviceTerminalClientCache.requestMetadata(terminal));
        }
        ticks++;
        DeviceTerminalClientCache.Snapshot snapshot = DeviceTerminalVisualizationState.factoryPresentation()
            ? com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot
                .visualizationSnapshot(terminal)
            : DeviceTerminalClientCache.getSnapshot(terminal);
        if (snapshot != null && scanning == null && (snapshot != displayed || moved(mc))) begin(snapshot, mc);
        if (scanning != null) advance(mc);
        else if (!visible.isEmpty()) {
            if (ticks >= nextAvailabilityTick) {
                availabilityRemaining = visible.size();
                nextAvailabilityTick = ticks + 20;
            }
            boolean fresh = DeviceTerminalClientCache.isFresh(terminal, System.currentTimeMillis());
            int budget = Math.min(WORK_PER_TICK, availabilityRemaining);
            for (int i = 0; i < budget; i++) {
                if (refreshCursor >= visible.size()) refreshCursor = 0;
                Anchor anchor = visible.get(refreshCursor++);
                boolean wasLoaded = anchor.loaded;
                update(anchor, mc, fresh);
                if (wasLoaded != anchor.loaded) viewGeneration++;
                availabilityRemaining--;
            }
        }
        target = select(mc);
    }

    private boolean moved(Minecraft mc) {
        return scanDimension != mc.theWorld.provider.dimensionId || scanRange != range()
            || scanHeatSize != DeviceEnergyVisualMath.validHeatSize(DeviceTerminalVisualConfig.heatSize)
            || scanLimit != limit()
            || DeviceTerminalAnchorMath
                .distanceSquared(scanX, scanY, scanZ, mc.thePlayer.posX, eyeY(mc), mc.thePlayer.posZ) > 4D;
    }

    private void begin(DeviceTerminalClientCache.Snapshot snapshot, Minecraft mc) {
        if (moved(mc)) viewGeneration++;
        buildStructureChanged = false;
        buildSnapshotChanged = snapshot != displayed;
        if (buildSnapshotChanged) {
            buildingIdentity.clear();
            buildStructureChanged = snapshot.entries.size() != boundIdentity.size();
        }
        scanning = snapshot;
        cursor = 0;
        building.clear();
        scanX = mc.thePlayer.posX;
        scanY = eyeY(mc);
        scanZ = mc.thePlayer.posZ;
        scanDimension = mc.theWorld.provider.dimensionId;
        scanRange = range();
        scanHeatSize = DeviceEnergyVisualMath.validHeatSize(DeviceTerminalVisualConfig.heatSize);
        scanSourceRange = DeviceEnergyVisualMath.sourceRange(scanRange, scanHeatSize);
        scanLimit = limit();
    }

    private void advance(Minecraft mc) {
        int end = Math.min(scanning.entries.size(), cursor + WORK_PER_TICK);
        boolean fresh = DeviceTerminalClientCache.isFresh(terminal, System.currentTimeMillis());
        for (; cursor < end; cursor++) {
            Entry entry = scanning.entries.get(cursor);
            // Compare the full binding topology, not a camera-dependent nearest subset.
            if (buildSnapshotChanged) {
                Entry previous = boundIdentity.get(entry.key);
                buildingIdentity.put(entry.key, entry);
                if (previous == null || previous.metaTileId != entry.metaTileId
                    || previous.capabilityMask != entry.capabilityMask
                    || previous.recognitionMode != entry.recognitionMode) buildStructureChanged = true;
            }
            if (entry.dim != scanDimension) continue;
            double distance = distance(entry, scanX, scanY, scanZ);
            // Keep sources whose heat halo reaches the range boundary; label and travel selection retain scanRange.
            if (!Double.isFinite(distance) || distance > scanSourceRange * scanSourceRange) continue;
            Anchor anchor = cached.get(entry.key);
            if (anchor == null) {
                anchor = new Anchor(entry, distance);
            } else {
                anchor.distance = distance;
                if (!equivalent(anchor.entry, entry)) {
                    anchor.textDirty = true;
                    viewGeneration++;
                }
                anchor.entry = entry;
            }
            if (building.size() == scanLimit && NEAREST.compare(anchor, building.peek()) >= 0) continue;
            update(anchor, mc, fresh);
            building.offer(anchor);
            if (building.size() > scanLimit) building.poll();
        }
        if (cursor == scanning.entries.size()) {
            boolean selectionChanged = visible.size() != building.size();
            if (!selectionChanged) for (Anchor candidate : building) {
                if (cached.get(candidate.entry.key) != candidate) {
                    selectionChanged = true;
                    break;
                }
            }
            if (selectionChanged) viewGeneration++;
            if (buildStructureChanged) dataEpoch++;
            if (buildSnapshotChanged) {
                Map<String, Entry> oldIdentity = boundIdentity;
                boundIdentity = buildingIdentity;
                buildingIdentity = oldIdentity;
            }
            visible.clear();
            visible.addAll(building);
            visible.sort(NEAREST);
            displayed = scanning;
            scanning = null;
            building.clear();
            refreshCursor = 0;
            availabilityRemaining = 0;
            nextAvailabilityTick = ticks + 20;
            cached.clear();
            for (Anchor anchor : visible) cached.put(anchor.entry.key, anchor);
        }
    }

    private static boolean equivalent(Entry a, Entry b) {
        return a.state == b.state && a.capabilityMask == b.capabilityMask
            && a.recognitionMode == b.recognitionMode
            && a.evidenceKind == b.evidenceKind
            && a.instIn == b.instIn
            && a.instOut == b.instOut
            && a.avgIn == b.avgIn
            && a.avgOut == b.avgOut
            && a.nameColor == b.nameColor
            && a.nameBold == b.nameBold
            && a.metaTileId == b.metaTileId
            && a.powerType == b.powerType
            && Objects.equals(a.displayName, b.displayName)
            && Objects.equals(a.recipeIn, b.recipeIn)
            && Objects.equals(a.recipeOut, b.recipeOut)
            && Objects.equals(a.name, b.name);
    }

    private static void update(Anchor anchor, Minecraft mc, boolean fresh) {
        Entry entry = anchor.entry;
        boolean loaded = entry.dim == mc.theWorld.provider.dimensionId
            && mc.theWorld.blockExists(entry.x, entry.y, entry.z);
        TileEntity tile = loaded ? mc.theWorld.getTileEntity(entry.x, entry.y, entry.z) : null;
        boolean matchingMachine = tile instanceof IGregTechTileEntity
            && (entry.metaTileId < 0 || ((IGregTechTileEntity) tile).getMetaTileID() == entry.metaTileId);
        anchor.available = fresh && matchingMachine;
        anchor.loaded = matchingMachine;
        boolean ignored = ignored(entry);
        String status = !fresh ? "stale" : !loaded ? "unloaded" : !anchor.available ? "missing" : "";
        long appearance = DeviceTerminalVisualConfig.appearanceKey();
        String language = mc.gameSettings.language;
        if (!anchor.textDirty && status.equals(anchor.status)
            && appearance == anchor.appearance
            && Objects.equals(language, anchor.language)) return;
        anchor.textDirty = false;
        anchor.status = status;
        anchor.appearance = appearance;
        anchor.language = language;
        anchor.factoryPower = factoryPowerText(entry, anchor.available);
        anchor.factoryPowerWidth = mc.fontRenderer.getStringWidth(anchor.factoryPower);
        anchor.lines.clear();
        anchor.lineWidths.clear();
        anchor.name = DeviceTerminalNames.localName(entry);
        if (DeviceTerminalVisualConfig.showLabelName) addLine(anchor, mc, anchor.name);
        if (!anchor.available) {
            addLine(anchor, mc, tr(!fresh ? "stale" : !loaded ? "unloaded" : "missing"));
        } else {
            if (ignored || DeviceTerminalVisualConfig.showLabelState) {
                addLine(
                    anchor,
                    mc,
                    StatCollector
                        .translateToLocal("gtswn.device.gui.state." + STATES[Math.max(0, Math.min(3, entry.state))]));
            }
            if (DeviceTerminalVisualConfig.showLabelPower) {
                if (!ignored && entry.evidenceKind >= 2) addLine(anchor, mc, tr("power.unknown"));
                else {
                    addLine(
                        anchor,
                        mc,
                        StatCollector.translateToLocalFormatted(
                            "gtswn.device.visual.power.eu",
                            metric(ignored ? 0 : entry.instIn),
                            metric(ignored ? 0 : entry.instOut)));
                }
            }
            if (!ignored && DeviceTerminalVisualConfig.showLabelRecipe) {
                String recipe = entry.recipeOut.isEmpty() ? entry.recipeIn : entry.recipeOut;
                if (!recipe.isEmpty()) addLine(anchor, mc, recipe);
            }
        }
        anchor.width = 0;
        for (String line : anchor.lines) {
            int width = mc.fontRenderer.getStringWidth(line);
            anchor.lineWidths.add(width);
            anchor.width = Math.max(anchor.width, width);
        }
    }

    private static void addLine(Anchor anchor, Minecraft mc, String line) {
        if (anchor.lines.size() < 5) anchor.lines.add(mc.fontRenderer.trimStringToWidth(line, 180));
    }

    private static String metric(long value) {
        return FormatUtil.formatMetric(BigInteger.valueOf(value), 1);
    }

    /** Unavailable/unknown readings never turn into a measured zero; ignored devices remain neutral. */
    static String factoryPowerText(Entry entry, boolean available) {
        if (!available) return "\u00a77? EU/t";
        if (DeviceEnergyWorldRenderer.ignored(entry)) return "\u00a78\u2014 EU/t";
        if (entry.evidenceKind >= 2) return "\u00a77\u2193? \u2191? EU/t";
        return "\u00a76\u2193" + metric(entry.instIn) + " \u00a7a\u2191" + metric(entry.instOut) + "\u00a77 EU/t";
    }

    private static boolean ignored(Entry entry) {
        return entry.state == 3;
    }

    private Anchor select(Minecraft mc) {
        if (!canRender(mc) || !DeviceTerminalClientCache.isFresh(terminal, System.currentTimeMillis())) return null;
        Vec3 look = mc.thePlayer.getLookVec();
        double x = mc.thePlayer.posX;
        double y = eyeY(mc);
        double z = mc.thePlayer.posZ;
        Anchor best = null;
        double bestAlignment = -1D;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Anchor anchor : visible) {
            Entry entry = anchor.entry;
            if (!anchor.available || entry.dim != mc.theWorld.provider.dimensionId) continue;
            double distance = distance(entry, x, y, z);
            double alignment = DeviceTerminalAnchorMath.alignment(
                entry.x + 0.5D,
                entry.y + 1.6D,
                entry.z + 0.5D,
                x,
                y,
                z,
                look.xCoord,
                look.yCoord,
                look.zCoord);
            if (com.miaokatze.gtswn.common.device.DeviceTerminalVisualRules
                .isAimedAtMachineBody(x, y, z, look.xCoord, look.yCoord, look.zCoord, entry.x, entry.y, entry.z))
                alignment = 1D;
            if (DeviceTerminalAnchorMath.targetable(distance, alignment, range())
                && DeviceTerminalAnchorMath.betterTarget(
                    alignment,
                    distance,
                    entry.key,
                    bestAlignment,
                    bestDistance,
                    best == null ? null : best.entry.key)) {
                best = anchor;
                bestAlignment = alignment;
                bestDistance = distance;
            }
        }
        return best;
    }

    public void clearTarget() {
        target = null;
    }

    List<Anchor> observedDevices() {
        return visible;
    }

    long viewGeneration() {
        return viewGeneration;
    }

    long dataEpoch() {
        return dataEpoch;
    }

    boolean viewReady() {
        return scanning == null;
    }

    public void invalidatePosition() {
        target = null;
        scanning = null;
        displayed = null;
        building.clear();
        visible.clear();
        cached.clear();
        viewGeneration++;
        dataEpoch++;
    }

    public String currentTargetKey() {
        target = select(Minecraft.getMinecraft());
        return target == null ? null : target.entry.key;
    }

    /** HUD hints consume the renderer's current selection without repeating the full target scan. */
    public boolean hasCurrentTarget() {
        return target != null;
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        lastRenderedMarkers = lastRenderedLabels = lastMarkerBatches = lastProjectedLabels = 0;
        Minecraft mc = Minecraft.getMinecraft();
        if (!canRender(mc) || visible.isEmpty() || DeviceTerminalVisualizationState.factoryPresentation()) return;
        Entity camera = mc.renderViewEntity == null ? mc.thePlayer : mc.renderViewEntity;
        double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * event.partialTicks;
        double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * event.partialTicks;
        double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * event.partialTicks;
        Frustrum frustum = new Frustrum();
        frustum.setPosition(x, y, z);
        drawn.clear();
        if (target != null && drawable(target, mc, frustum, x, y, z)) drawn.add(target);
        for (Anchor anchor : visible) if (anchor != target && drawable(anchor, mc, frustum, x, y, z)) drawn.add(anchor);
        if (drawn.isEmpty()) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            if (DeviceTerminalVisualConfig.labelDepthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1F, 1F, 1F, 1F);
            GL11.glLineWidth(Math.max(1F, mc.displayHeight / 720F));
            // All diamonds share one vertex/color batch rather than one matrix and draw call per device.
            double yaw = Math.toRadians(RenderManager.instance.playerViewY);
            double pitch = Math.toRadians(RenderManager.instance.playerViewX);
            double rx = Math.cos(yaw), rz = Math.sin(yaw);
            double ux = -Math.sin(yaw) * Math.sin(pitch), uy = Math.cos(pitch), uz = Math.cos(yaw) * Math.sin(pitch);
            Tessellator tess = Tessellator.instance;
            tess.startDrawing(GL11.GL_LINES);
            for (Anchor anchor : drawn) {
                Entry entry = anchor.entry;
                double px = entry.x + .5D - x, py = entry.y + 1.6D - y, pz = entry.z + .5D - z;
                double scale = 5D
                    * Math.max(.027D, Math.min(.15D, .027D + Math.sqrt(distance(entry, x, y, z)) * .001D));
                boolean selected = anchor == target;
                boolean ignored = ignored(entry);
                tess.setColorRGBA_F(
                    ignored ? .55F : selected ? 1F : .35F,
                    ignored ? .55F : selected ? .82F : .9F,
                    ignored ? .55F : selected ? .35F : .95F,
                    anchor.available ? .95F : .5F);
                markerEdge(
                    tess,
                    px + ux * scale,
                    py + uy * scale,
                    pz + uz * scale,
                    px + rx * scale,
                    py,
                    pz + rz * scale);
                markerEdge(
                    tess,
                    px + rx * scale,
                    py,
                    pz + rz * scale,
                    px - ux * scale,
                    py - uy * scale,
                    pz - uz * scale);
                markerEdge(
                    tess,
                    px - ux * scale,
                    py - uy * scale,
                    pz - uz * scale,
                    px - rx * scale,
                    py,
                    pz - rz * scale);
                markerEdge(
                    tess,
                    px - rx * scale,
                    py,
                    pz - rz * scale,
                    px + ux * scale,
                    py + uy * scale,
                    pz + uz * scale);
            }
            tess.draw();
            lastMarkerBatches = 1;
            lastRenderedMarkers = drawn.size();
            int budget = Math.max(8, Math.min(64, DeviceTerminalVisualConfig.labelBudget));
            if (DeviceTerminalVisualConfig.showLabels) {
                readLabelTransform();
                for (Anchor anchor : drawn) {
                    if (lastRenderedLabels >= budget || lastProjectedLabels >= 64) break;
                    if (anchor.lines.isEmpty()) continue;
                    lastProjectedLabels++;
                    Entry entry = anchor.entry;
                    boolean projected = DeviceTerminalLabelLayout.project(
                        labelTransform,
                        labelViewport,
                        entry.x + .5D - x,
                        entry.y + 1.6D - y,
                        entry.z + .5D - z,
                        rx,
                        rz,
                        ux,
                        uy,
                        uz,
                        labelScale(entry, x, y, z),
                        anchor.width / 2 + 4,
                        12 + anchor.lines.size() * 10,
                        candidateRectangle);
                    // The selected label owns its rectangle before any nearest-neighbour candidate.
                    if (anchor != target && (!projected || !DeviceTerminalLabelLayout
                        .fits(candidateRectangle, acceptedRectangles, lastRenderedLabels, 3D))) continue;
                    drawLabel(anchor, mc, x, y, z, true);
                    if (!projected) {
                        // A selected billboard straddling the eye plane still draws, without allowing overlap.
                        lastRenderedLabels++;
                        break;
                    }
                    System.arraycopy(candidateRectangle, 0, acceptedRectangles, lastRenderedLabels * 4, 4);
                    lastRenderedLabels++;
                }
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
        }
    }

    private void readLabelTransform() {
        modelviewBuffer.clear();
        projectionBuffer.clear();
        viewportBuffer.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelviewBuffer);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projectionBuffer);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewportBuffer);
        for (int i = 0; i < 16; i++) {
            labelModelview[i] = modelviewBuffer.get(i);
            labelProjection[i] = projectionBuffer.get(i);
        }
        for (int i = 0; i < 4; i++) labelViewport[i] = viewportBuffer.get(i);
        DeviceTerminalLabelLayout.combine(labelModelview, labelProjection, labelTransform);
    }

    static float labelScale(Entry entry, double x, double y, double z) {
        double original = Math.max(.027D, Math.min(.15D, .027D + Math.sqrt(distance(entry, x, y, z)) * .001D));
        int size = Math.max(
            DeviceTerminalVisualConfig.LABEL_SIZE_MIN,
            Math.min(DeviceTerminalVisualConfig.LABEL_SIZE_MAX, DeviceTerminalVisualConfig.labelSize));
        return (float) (original * 1.5D * size / DeviceTerminalVisualConfig.LABEL_SIZE_DEFAULT);
    }

    private static void markerEdge(Tessellator tess, double x0, double y0, double z0, double x1, double y1, double z1) {
        tess.addVertex(x0, y0, z0);
        tess.addVertex(x1, y1, z1);
    }

    private static boolean drawable(Anchor anchor, Minecraft mc, Frustrum frustum, double x, double y, double z) {
        Entry entry = anchor.entry;
        if (entry.dim != mc.theWorld.provider.dimensionId || distance(entry, x, eyeY(mc), z) > range() * range())
            return false;
        if (frustum.isBoundingBoxInFrustum(anchor.bounds)) return true;
        if (!DeviceTerminalVisualConfig.showLabels || anchor.lines.isEmpty()) return false;
        updateLabelBounds(anchor, labelScale(entry, x, y, z));
        return frustum.isBoundingBoxInFrustum(anchor.labelBounds);
    }

    static void updateLabelBounds(Anchor anchor, double scale) {
        double half = anchor.width / 2D + 4D;
        double bottom = 12D + anchor.lines.size() * 10D;
        // A sphere about the billboard origin contains every corner for any camera yaw/pitch.
        double radius = Math.sqrt(half * half + bottom * bottom) * scale;
        Entry entry = anchor.entry;
        anchor.labelBounds.setBounds(
            entry.x + .5D - radius,
            entry.y + 1.6D - radius,
            entry.z + .5D - radius,
            entry.x + .5D + radius,
            entry.y + 1.6D + radius,
            entry.z + .5D + radius);
    }

    private void drawLabel(Anchor anchor, Minecraft mc, double x, double y, double z, boolean labelBudget) {
        Entry entry = anchor.entry;
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(entry.x + 0.5D - x, entry.y + 1.6D - y, entry.z + 0.5D - z);
            GL11.glRotatef(-RenderManager.instance.playerViewY, 0F, 1F, 0F);
            GL11.glRotatef(RenderManager.instance.playerViewX, 1F, 0F, 0F);
            float scale = labelScale(entry, x, y, z);
            GL11.glScalef(-scale, -scale, scale);
            boolean selected = anchor == target;
            boolean ignored = ignored(entry);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            if (labelBudget && DeviceTerminalVisualConfig.showLabels && !anchor.lines.isEmpty()) {
                int half = anchor.width / 2 + 4;
                int bottom = 12 + anchor.lines.size() * 10;
                GL11.glColor4f(
                    ignored ? .07F : selected ? .17F : .04F,
                    ignored ? .07F : selected ? .13F : .055F,
                    .07F,
                    selected ? .85F : .68F);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2d(-half, 7);
                GL11.glVertex2d(-half, bottom);
                GL11.glVertex2d(half, bottom);
                GL11.glVertex2d(half, 7);
                GL11.glEnd();
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glColor4f(1F, 1F, 1F, 1F);
                int textY = 9;
                for (int i = 0; i < anchor.lines.size(); i++) {
                    String line = anchor.lines.get(i);
                    if (ignored) line = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(line);
                    mc.fontRenderer
                        .drawString(line, -anchor.lineWidths.get(i) / 2, textY, ignored ? 0x999999 : 0xEEEEEE);
                    textY += 10;
                }
            }
        } finally {
            GL11.glPopMatrix();
        }
    }

    @SubscribeEvent
    public void hud(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!canRender(mc)) return;
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        if (DeviceTerminalVisualizationState.factoryPresentation()) {
            drawFactoryPower(mc, sr.getScaledWidth(), sr.getScaledHeight());
            return;
        }
        String notice = scanning != null
            ? StatCollector.translateToLocalFormatted("gtswn.device.visual.progress", cursor, scanning.entries.size())
            : DeviceTerminalClientCache.hasMorePending(terminal) ? tr("loading")
                : visible.isEmpty() ? tr("empty")
                    : target == null ? ""
                        : tr(DeviceTerminalVisualizationState.isActiveHeld() ? "teleport" : "hold_terminal")
                            + (DeviceTerminalVisualConfig.showLabelName ? " " + target.name : "");
        String hint = tr(DeviceTerminalVisualizationState.isActiveHeld() ? "hint" : "inventory_hint");
        DeviceTerminalClientCache.Snapshot snapshot = DeviceTerminalVisualizationState.factoryPresentation()
            ? com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot
                .visualizationSnapshot(terminal)
            : DeviceTerminalClientCache.getSnapshot(terminal);
        String count = StatCollector.translateToLocalFormatted(
            "gtswn.device.visual.count",
            visible.size(),
            snapshot == null ? 0 : snapshot.entries.size());
        int availableWidth = Math.max(32, sr.getScaledWidth() - 16);
        hudHint.update(mc.fontRenderer, hint, availableWidth);
        hudNotice.update(mc.fontRenderer, notice, availableWidth);
        hudCount.update(mc.fontRenderer, count, availableWidth);
        int rows = hudHint.lines.size() + hudNotice.lines.size() + hudCount.lines.size();
        int y = Math.max(8, Math.min(sr.getScaledHeight() / 2 + 28, sr.getScaledHeight() - 8 - rows * 11));
        y = hudHint.draw(mc.fontRenderer, sr.getScaledWidth(), y, 0xDDDDDD);
        y = hudNotice.draw(mc.fontRenderer, sr.getScaledWidth(), y, target == null ? 0xBBBBBB : 0xFFD68D);
        hudCount.draw(mc.fontRenderer, sr.getScaledWidth(), y, 0xBBBBBB);
    }

    /** Factory projection owns placement; font size stays constant while its camera pans, rotates or zooms. */
    private void drawFactoryPower(Minecraft mc, int width, int height) {
        lastRenderedLabels = lastProjectedLabels = 0;
        if (!DeviceTerminalVisualConfig.showLabels || !DeviceTerminalVisualConfig.showLabelPower) return;
        int budget = Math.max(8, Math.min(64, DeviceTerminalVisualConfig.labelBudget));
        double hudScale = EngineeringHud.scale(width, height);
        WorkbenchLayout.Rect viewport = EngineeringModeController
            .worldViewport((int) (width / hudScale), (int) (height / hudScale));
        DevicePowerClusterLayout.Bounds bounds = new DevicePowerClusterLayout.Bounds(
            (int) Math.ceil(viewport.x * hudScale) + 2,
            (int) Math.ceil(viewport.y * hudScale) + 2,
            (int) Math.floor((viewport.x + viewport.w) * hudScale) - 2,
            (int) Math.floor((viewport.y + viewport.h) * hudScale) - 2);
        if (bounds.right - bounds.left < 28 || bounds.bottom - bounds.top < 40) return;
        List<DevicePowerClusterLayout.Point> points = new ArrayList<>();
        boolean fresh = DeviceTerminalClientCache.isFresh(terminal, System.currentTimeMillis());
        for (Anchor anchor : visible) {
            if (lastProjectedLabels >= DevicePowerClusterLayout.MAX_POINTS) break;
            Entry entry = anchor.entry;
            if (entry.dim != mc.theWorld.provider.dimensionId || anchor.factoryPower == null
                || distance(entry, mc.thePlayer.posX, eyeY(mc), mc.thePlayer.posZ) > range() * range()) continue;
            lastProjectedLabels++;
            ProjectedPoint projected = WorldCameraHooks.projectWorld(
                EngineeringModeController.OWNER,
                mc.theWorld,
                entry.x + .5D,
                entry.y + 1.04D,
                entry.z + .5D);
            if (projected == null || Math.abs(projected.ndcZ()) > 1D) continue;
            DevicePowerClusterLayout.Reading reading = DeviceEnergyWorldRenderer.ignored(entry)
                ? DevicePowerClusterLayout.Reading.IGNORED
                : !fresh || !anchor.available ? DevicePowerClusterLayout.Reading.UNAVAILABLE
                    : entry.evidenceKind >= 2 || entry.instIn < 0 || entry.instOut < 0
                        ? DevicePowerClusterLayout.Reading.UNKNOWN
                        : DevicePowerClusterLayout.Reading.CONFIRMED;
            String detail = fresh ? anchor.factoryPower : factoryPowerText(entry, false);
            points.add(
                new DevicePowerClusterLayout.Point(
                    entry.key,
                    (projected.ndcX() + 1D) * width * .5D,
                    (1D - projected.ndcY()) * height * .5D,
                    entry.instIn,
                    entry.instOut,
                    reading,
                    detail,
                    fresh ? anchor.factoryPowerWidth : mc.fontRenderer.getStringWidth(detail),
                    mc.fontRenderer.FONT_HEIGHT));
        }
        DevicePowerClusterLayout.Result layout = factoryClusters.layout(
            points,
            bounds,
            budget,
            (group, availableWidth) -> measureFactoryGroup(mc.fontRenderer, group, availableWidth));
        try (GlScope ignored = new GlScope()) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            int scissorLeft = (int) Math.ceil(bounds.left * mc.displayWidth / (double) width);
            int scissorRight = (int) Math.floor(bounds.right * mc.displayWidth / (double) width);
            int scissorBottom = (int) Math.ceil((height - bounds.bottom) * mc.displayHeight / (double) height);
            int scissorTop = (int) Math.floor((height - bounds.top) * mc.displayHeight / (double) height);
            GL11.glScissor(scissorLeft, scissorBottom, scissorRight - scissorLeft, scissorTop - scissorBottom);
            for (DevicePowerClusterLayout.Group group : layout.groups) {
                if (group.points.size() > 1 || group.remainder) {
                    Gui.drawRect(group.left, group.top, group.left + group.width, group.top + group.height, 0xB01C2A35);
                }
                int textY = group.top;
                for (String line : group.lines) {
                    mc.fontRenderer.drawStringWithShadow(line, group.left, textY, 0xEEEEEE);
                    textY += mc.fontRenderer.FONT_HEIGHT + 1;
                }
                lastRenderedLabels++;
            }
        }
    }

    private static void measureFactoryGroup(FontRenderer font, DevicePowerClusterLayout.Group group, int maxWidth) {
        List<String> raw = new ArrayList<>();
        if (group.points.size() == 1 && !group.remainder) {
            DevicePowerClusterLayout.Point point = group.points.get(0);
            if (point.width <= maxWidth) {
                group.lines = new String[] { point.detail };
                group.width = point.width;
                group.height = point.height;
                return;
            }
            raw.add(point.detail);
        } else {
            raw.add(
                StatCollector.translateToLocalFormatted(
                    "gtswn.device.visual.cluster." + (group.remainder ? "remainder" : "nearby"),
                    group.points.size(),
                    group.confirmed));
            String input = group.confirmed == 0 ? "?" : DevicePowerClusterLayout.formatTotal(group.input);
            String output = group.confirmed == 0 ? "?" : DevicePowerClusterLayout.formatTotal(group.output);
            raw.add("\u00a76\u2193" + input + " \u00a7a\u2191" + output + "\u00a77 EU/t");
            if (group.unknown + group.unavailable + group.ignored != 0) {
                raw.add(
                    StatCollector.translateToLocalFormatted(
                        "gtswn.device.visual.cluster.unresolved",
                        group.unknown,
                        group.unavailable,
                        group.ignored));
            }
        }
        List<String> lines = new ArrayList<>();
        for (String line : raw) lines.addAll(font.listFormattedStringToWidth(line, Math.max(1, maxWidth)));
        group.lines = lines.toArray(new String[0]);
        group.width = 0;
        for (String line : group.lines) group.width = Math.max(group.width, font.getStringWidth(line));
        group.height = group.lines.length * (font.FONT_HEIGHT + 1) - 1;
    }

    /** Wrapping changes only with text, viewport or font mode; unchanged frames reuse line widths. */
    private static final class HudText {

        String text;
        int width;
        FontRenderer font;
        boolean unicode;
        List<String> lines = java.util.Collections.emptyList();
        int[] widths = new int[0];

        void update(FontRenderer renderer, String value, int maxWidth) {
            boolean unicodeNow = renderer.getUnicodeFlag();
            if (Objects.equals(text, value) && width == maxWidth && font == renderer && unicode == unicodeNow) return;
            text = value;
            width = maxWidth;
            font = renderer;
            unicode = unicodeNow;
            lines = value.isEmpty() ? java.util.Collections.emptyList()
                : renderer.listFormattedStringToWidth(value, maxWidth);
            widths = new int[lines.size()];
            for (int i = 0; i < lines.size(); i++) widths[i] = renderer.getStringWidth(lines.get(i));
        }

        int draw(FontRenderer renderer, int viewportWidth, int y, int color) {
            for (int i = 0; i < lines.size(); i++, y += 11)
                renderer.drawStringWithShadow(lines.get(i), (viewportWidth - widths[i]) / 2, y, color);
            return y;
        }
    }

    private boolean canRender(Minecraft mc) {
        return mc.theWorld != null && mc.thePlayer != null
            && !mc.gameSettings.hideGUI
            && mc.currentScreen == null
            && terminal != null
            && DeviceTerminalVisualizationState.presentationActive(terminal)
            && terminal.equals(DeviceTerminalVisualizationState.presentationTerminalId());
    }

    private static double eyeY(Minecraft mc) {
        return com.miaokatze.gtswn.common.device.DeviceTerminalVisualRules.eyeY(
            mc.thePlayer.posY,
            mc.thePlayer.yOffset,
            mc.thePlayer.getEyeHeight(),
            mc.thePlayer.getDefaultEyeHeight());
    }

    private static int range() {
        return Math.max(8, Math.min(128, DeviceTerminalVisualConfig.range));
    }

    private static int limit() {
        return Math.max(32, Math.min(512, DeviceTerminalVisualConfig.maxDevices));
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.device.visual." + key);
    }

    private static double distance(Entry entry, double x, double y, double z) {
        return DeviceTerminalAnchorMath.distanceSquared(entry.x + 0.5D, entry.y + 1.6D, entry.z + 0.5D, x, y, z);
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.world.isRemote) {
            reset();
            DeviceTerminalVisualizationState.clear();
        }
    }

    private void reset() {
        factoryClusters.clear();
        visible.clear();
        drawn.clear();
        building.clear();
        scanning = null;
        displayed = null;
        terminal = null;
        target = null;
        cursor = 0;
        ticks = 0;
        refreshCursor = 0;
        availabilityRemaining = 0;
        nextAvailabilityTick = 0;
        boundIdentity.clear();
        buildingIdentity.clear();
        cached.clear();
        viewGeneration++;
        dataEpoch++;
    }

    static final class Anchor {

        Entry entry;
        double distance;
        final AxisAlignedBB bounds;
        final AxisAlignedBB labelBounds;
        final List<String> lines = new ArrayList<>();
        boolean available;
        boolean loaded;
        boolean textDirty = true;
        String status;
        String language;
        long appearance;
        final List<Integer> lineWidths = new ArrayList<>();
        final AxisAlignedBB heatBounds;
        String name;
        int width;
        String factoryPower;
        int factoryPowerWidth;

        Anchor(Entry entry, double distance) {
            this.entry = entry;
            this.distance = distance;
            heatBounds = AxisAlignedBB.getBoundingBox(
                entry.x - 0.012D,
                entry.y - 0.012D,
                entry.z - 0.012D,
                entry.x + 1.012D,
                entry.y + 1.012D,
                entry.z + 1.012D);
            this.bounds = AxisAlignedBB
                .getBoundingBox(entry.x - 1D, entry.y, entry.z - 1D, entry.x + 2D, entry.y + 3D, entry.z + 2D);
            labelBounds = AxisAlignedBB
                .getBoundingBox(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        }
    }
}
