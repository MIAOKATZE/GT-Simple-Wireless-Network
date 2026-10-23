package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.device.DevicePowerCapabilities;
import com.miaokatze.gtswn.common.device.DeviceTerminalRecognitionMode;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Bounded radial thermal volumes over the anchor renderer's already observed devices. */
public final class DeviceEnergyWorldRenderer {

    private static final int WORK_PER_TICK = 32;
    private static final int OUTLINE_BUDGET = 64;
    private static final int[] LEGEND_COLORS = { 0xFF6E36, 0x44EA77 };
    private final List<Visual> visible = new ArrayList<>(1024);
    private final List<Visual> building = new ArrayList<>(1024);
    private final List<Visual> drawn = new ArrayList<>(1024);
    private final Map<String, Visual> electricPrevious = new HashMap<>();
    private List<DeviceTerminalAnchorRenderer.Anchor> source;
    private UUID terminal;
    private long sourceGeneration = Long.MIN_VALUE;
    private long buildGeneration;
    private long buildEpoch;
    private int cursor;
    private int ticks;
    private long appearance;
    private long buildAppearance;
    private double maxElectric;
    private double buildElectricMax;
    private String[] legendLines = new String[0];
    private int legendWidth;
    private String legendLanguage;
    public int lastRenderedLayers;
    public int lastFilledBatches;
    public int lastLineBatches;
    public int lastVisibleLayers;
    public int lastRenderedVertices;
    public int lastMaxBatchVertices;
    /** 0 rendered, 1 no world/player, 2 inactive, 3 stale, 4 no layers, 5 outside range/frustum. */
    public int lastRenderSkipReason;

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            clear();
            return;
        }
        DeviceTerminalVisualConfig.load();
        UUID held = DeviceTerminalVisualizationState.presentationTerminalId();
        if (!DeviceTerminalVisualConfig.enabled || !DeviceTerminalVisualizationState.presentationActive(held)) {
            clear();
            return;
        }
        if (!held.equals(terminal)) {
            clear();
            terminal = held;
        }
        DeviceTerminalAnchorRenderer worldView = DeviceTerminalAnchorRenderer.INSTANCE;
        ticks++;
        // Networking and World/TileEntity checks belong exclusively to the shared anchor world view.
        long settings = DeviceTerminalVisualConfig.appearanceKey();
        if (source != null && (buildEpoch != worldView.dataEpoch() || buildAppearance != settings)) {
            source = null;
            building.clear();
        }
        if (source == null && worldView.viewReady()
            && (sourceGeneration != worldView.viewGeneration() || settings != appearance)) {
            source = new ArrayList<>(worldView.observedDevices());
            buildGeneration = worldView.viewGeneration();
            buildEpoch = worldView.dataEpoch();
            buildAppearance = settings;
            cursor = 0;
            building.clear();
            buildElectricMax = 0D;
        }
        // A soft camera/order rebuild never pauses or restarts an already bounded thermal build.
        if (source != null) advance();
        if (ticks % 5 == 0) {
            for (Visual visual : visible) {
                visual.previousRadius = visual.radius;
                visual.intensity = DeviceEnergyVisualMath
                    .smooth(visual.intensity, visual.target, DeviceTerminalVisualConfig.smoothing);
                visual.updateAppearance();
            }
        }
        if (!java.util.Objects.equals(legendLanguage, mc.gameSettings.language)) updateLegend(mc);
    }

    private void advance() {
        int end = Math.min(source.size(), cursor + WORK_PER_TICK);
        for (; cursor < end; cursor++) {
            DeviceTerminalAnchorRenderer.Anchor device = source.get(cursor);
            if (!device.loaded) continue;
            Entry entry = device.entry;
            if (ignored(entry)) {
                // Ignored machines stay locatable regardless of energy filters, with one small gray core.
                building.add(new Visual(device, false, 0D, true));
                continue;
            }
            if (channels(entry) != 0) add(device);
        }
        if (cursor < source.size()) return;
        maxElectric = buildElectricMax;
        for (Visual visual : building) {
            visual.target = DeviceEnergyVisualMath.intensity(visual.magnitude, maxElectric);
            Visual previous = electricPrevious.get(visual.device.entry.key);
            visual.intensity = visual.ignored ? 0D : previous == null ? visual.target : previous.intensity;
            visual.updateAppearance();
            visual.previousRadius = visual.ignored ? 0D : previous == null ? visual.radius : previous.previousRadius;
            visual.updateBounds();
        }
        visible.clear();
        visible.addAll(building);
        electricPrevious.clear();
        for (Visual visual : visible) electricPrevious.put(visual.device.entry.key, visual);
        sourceGeneration = buildGeneration;
        appearance = buildAppearance;
        source = null;
        updateLegend(Minecraft.getMinecraft());
    }

    static boolean ignored(Entry entry) {
        return entry.state == 3 || entry.recognitionMode == DeviceTerminalRecognitionMode.IGNORE;
    }

    static int channels(Entry entry) {
        return ignored(entry) ? 0
            : entry.capabilityMask & (DevicePowerCapabilities.EU_IN | DevicePowerCapabilities.EU_OUT);
    }

    private void add(DeviceTerminalAnchorRenderer.Anchor device) {
        Entry entry = device.entry;
        double input = DeviceEnergyVisualMath
            .weighted(entry.instIn, entry.avgIn, DeviceTerminalVisualConfig.averageWeight);
        double output = DeviceEnergyVisualMath
            .weighted(entry.instOut, entry.avgOut, DeviceTerminalVisualConfig.averageWeight);
        boolean producer = output > input || output == input && entry.powerType == 1;
        if (!DeviceTerminalVisualConfig.visible(producer)) return;
        // Balanced input/output is still activity: net-zero must not hide a working device.
        double magnitude = Math.max(0D, Math.max(input, output));
        building.add(new Visual(device, producer, magnitude, entry.evidenceKind >= 2 || magnitude == 0D));
        buildElectricMax = Math.max(buildElectricMax, magnitude);
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        lastRenderedLayers = lastFilledBatches = lastLineBatches = 0;
        lastRenderedVertices = lastMaxBatchVertices = 0;
        lastVisibleLayers = visible.size();
        Minecraft mc = Minecraft.getMinecraft();
        lastRenderSkipReason = renderGate(mc);
        if (lastRenderSkipReason != 0) return;
        if (visible.isEmpty()) {
            lastRenderSkipReason = 4;
            return;
        }
        Entity camera = mc.renderViewEntity == null ? mc.thePlayer : mc.renderViewEntity;
        double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * event.partialTicks;
        double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * event.partialTicks;
        double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * event.partialTicks;
        Frustrum frustum = new Frustrum();
        frustum.setPosition(x, y, z);
        drawn.clear();
        int range = DeviceTerminalVisualConfig.range;
        double radiusProgress = (ticks % 5 + event.partialTicks) / 5D;
        for (Visual visual : visible) {
            Entry entry = visual.device.entry;
            if (!visual.device.loaded || entry.dim != mc.theWorld.provider.dimensionId) continue;
            visual.renderRadius = DeviceEnergyVisualMath
                .interpolateRadius(visual.previousRadius, visual.radius, radiusProgress);
            double dx = visual.centerX - x, dy = visual.centerY - y, dz = visual.centerZ - z;
            if (DeviceEnergyVisualMath.withinRange(dx, dy, dz, range, visual.extent)
                && frustum.isBoundingBoxInFrustum(visual.box)) drawn.add(visual);
        }
        if (drawn.isEmpty()) {
            lastRenderSkipReason = 5;
            return;
        }
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            DeviceEnergyRenderState.begin(DeviceTerminalVisualConfig.depthTest);
            Tessellator tess = Tessellator.instance;
            DeviceEnergyRenderState.start(tess, GL11.GL_QUADS);
            int batchVertices = 0;
            // Device groups are cached nearest-first; reverse groups for translucent far-to-near compositing.
            for (int i = drawn.size() - 1; i >= 0; i--) {
                Visual visual = drawn.get(i);
                batchVertices = fillLayer(tess, visual, x, y, z, batchVertices);
            }
            tess.draw();
            lastFilledBatches++;
            lastMaxBatchVertices = Math.max(lastMaxBatchVertices, batchVertices);
            lastRenderedLayers = drawn.size();
            // A bounded edge batch supplies shape detail; the filled color layer is the primary visual.
            GL11.glLineWidth(Math.max(1F, mc.displayHeight / 720F));
            DeviceEnergyRenderState.start(tess, GL11.GL_LINES);
            int outlines = 0;
            for (Visual visual : drawn) {
                if (outlines >= OUTLINE_BUDGET) break;
                if (visual.neutral || visual.intensity < .85D) continue;
                tess.setColorRGBA_F(visual.red, visual.green, visual.blue, visual.alpha * .6F);
                outline(tess, visual.coreBox, x, y, z);
                outlines++;
            }
            tess.draw();
            lastLineBatches = outlines == 0 ? 0 : 1;
        } finally {
            GL11.glPopMatrix();
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
        }
    }

    private int fillLayer(Tessellator tess, Visual visual, double x, double y, double z, int batchVertices) {
        int vertices = DeviceEnergyVisualMath.vertices(visual.renderRadius > 0D);
        if (DeviceEnergyVisualMath.startsNewBatch(batchVertices, vertices)) {
            tess.draw();
            lastFilledBatches++;
            lastMaxBatchVertices = Math.max(lastMaxBatchVertices, batchVertices);
            DeviceEnergyRenderState.start(tess, GL11.GL_QUADS);
            batchVertices = 0;
        }
        if (visual.renderRadius > 0D) {
            // Two octahedral isosurfaces give a radial envelope with a weaker outer falloff.
            // Degenerate quads keep core and halo geometry in the same fixed-buffer draw batch.
            tess.setColorRGBA_F(visual.red, visual.green, visual.blue, visual.alpha * .25F);
            halo(tess, visual, visual.renderRadius, x, y, z);
            tess.setColorRGBA_F(visual.red, visual.green, visual.blue, visual.alpha * .45F);
            halo(tess, visual, visual.renderRadius * .64D, x, y, z);
        }
        tess.setColorRGBA_F(visual.red, visual.green, visual.blue, visual.alpha);
        fill(tess, visual.coreBox, x, y, z);
        lastRenderedVertices += vertices;
        return batchVertices + vertices;
    }

    private static void halo(Tessellator t, Visual visual, double radius, double cx, double cy, double cz) {
        double x = visual.centerX - cx, y = visual.centerY - cy, z = visual.centerZ - cz;
        for (int side = 0; side < 4; side++) {
            double ax = side == 0 ? radius : side == 2 ? -radius : 0D;
            double az = side == 1 ? radius : side == 3 ? -radius : 0D;
            double bx = side == 3 ? radius : side == 1 ? -radius : 0D;
            double bz = side == 0 ? radius : side == 2 ? -radius : 0D;
            face(t, x, y + radius, z, x + ax, y, z + az, x + bx, y, z + bz, x + bx, y, z + bz);
            face(t, x, y - radius, z, x + bx, y, z + bz, x + ax, y, z + az, x + ax, y, z + az);
        }
    }

    private boolean renderable(Minecraft mc) {
        return renderGate(mc) == 0;
    }

    private int renderGate(Minecraft mc) {
        if (mc.theWorld == null || mc.thePlayer == null) return 1;
        if (!DeviceTerminalVisualConfig.enabled || terminal == null
            || !terminal.equals(DeviceTerminalVisualizationState.presentationTerminalId())) return 2;
        return DeviceTerminalClientCache.isFresh(terminal, System.currentTimeMillis()) ? 0 : 3;
    }

    private static void fill(Tessellator t, AxisAlignedBB box, double cx, double cy, double cz) {
        double x0 = box.minX - cx, x1 = box.maxX - cx, y0 = box.minY - cy, y1 = box.maxY - cy, z0 = box.minZ - cz,
            z1 = box.maxZ - cz;
        face(t, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        face(t, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        face(t, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        face(t, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0);
        face(t, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        face(t, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
    }

    private static void face(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1, double x2,
        double y2, double z2, double x3, double y3, double z3) {
        t.addVertex(x0, y0, z0);
        t.addVertex(x1, y1, z1);
        t.addVertex(x2, y2, z2);
        t.addVertex(x3, y3, z3);
    }

    private static void outline(Tessellator t, AxisAlignedBB b, double cx, double cy, double cz) {
        double x0 = b.minX - cx, x1 = b.maxX - cx, y0 = b.minY - cy, y1 = b.maxY - cy, z0 = b.minZ - cz,
            z1 = b.maxZ - cz;
        for (int side = 0; side < 2; side++) {
            double y = side == 0 ? y0 : y1;
            edge(t, x0, y, z0, x1, y, z0);
            edge(t, x1, y, z0, x1, y, z1);
            edge(t, x1, y, z1, x0, y, z1);
            edge(t, x0, y, z1, x0, y, z0);
        }
        edge(t, x0, y0, z0, x0, y1, z0);
        edge(t, x1, y0, z0, x1, y1, z0);
        edge(t, x1, y0, z1, x1, y1, z1);
        edge(t, x0, y0, z1, x0, y1, z1);
    }

    private static void edge(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1) {
        t.addVertex(x0, y0, z0);
        t.addVertex(x1, y1, z1);
    }

    private void updateLegend(Minecraft mc) {
        legendLanguage = mc.gameSettings.language;
        String[] keys = { "consumer", "generator" };
        legendLines = new String[3];
        legendWidth = 0;
        for (int i = 0; i < keys.length; i++)
            legendLines[i] = StatCollector.translateToLocal("gtswn.device.settings." + keys[i]);
        legendLines[2] = StatCollector.translateToLocalFormatted(
            "gtswn.device.settings.maximum.eu",
            FormatUtil.formatMetricDouble(maxElectric, 1));
        for (String line : legendLines) legendWidth = Math.max(legendWidth, mc.fontRenderer.getStringWidth(line));
    }

    @SubscribeEvent
    public void legend(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT || !renderable(mc)
            || mc.gameSettings.hideGUI
            || visible.isEmpty()) return;
        int x = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaledWidth() - legendWidth - 8;
        for (int i = 0; i < legendLines.length; i++) mc.fontRenderer.drawStringWithShadow(
            legendLines[i],
            x,
            70 + i * 11,
            i < LEGEND_COLORS.length ? LEGEND_COLORS[i] : 0xDDDDDD);
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.world.isRemote) {
            clear();
            DeviceTerminalClientCache.clear();
        }
    }

    private void clear() {
        visible.clear();
        building.clear();
        drawn.clear();
        electricPrevious.clear();
        source = null;
        terminal = null;
        sourceGeneration = Long.MIN_VALUE;
        ticks = 0;
        lastRenderedLayers = lastFilledBatches = lastLineBatches = 0;
        lastRenderedVertices = lastMaxBatchVertices = 0;
        lastVisibleLayers = 0;
        lastRenderSkipReason = 2;
    }

    static final class Visual {

        final DeviceTerminalAnchorRenderer.Anchor device;
        final AxisAlignedBB coreBox;
        final AxisAlignedBB box;
        final double centerX, centerY, centerZ;
        final boolean producer;
        final boolean neutral;
        final boolean ignored;
        final double magnitude;
        double target;
        double intensity;
        double radius;
        double previousRadius;
        double renderRadius;
        double extent;
        float red, green, blue, alpha;

        Visual(DeviceTerminalAnchorRenderer.Anchor device, boolean producer, double magnitude, boolean neutral) {
            this.device = device;
            this.producer = producer;
            ignored = DeviceEnergyWorldRenderer.ignored(device.entry);
            this.magnitude = ignored ? 0D : magnitude;
            this.neutral = ignored || neutral;
            coreBox = device.heatBounds;
            centerX = (coreBox.minX + coreBox.maxX) * .5D;
            centerY = (coreBox.minY + coreBox.maxY) * .5D;
            centerZ = (coreBox.minZ + coreBox.maxZ) * .5D;
            box = AxisAlignedBB
                .getBoundingBox(coreBox.minX, coreBox.minY, coreBox.minZ, coreBox.maxX, coreBox.maxY, coreBox.maxZ);
        }

        void updateAppearance() {
            if (ignored) previousRadius = 0D;
            radius = neutral ? 0D : DeviceEnergyVisualMath.radius(intensity, DeviceTerminalVisualConfig.heatSize);
            updateBounds();
            float strength = (float) intensity;
            if (neutral) {
                red = green = blue = .42F;
                alpha = (float) DeviceTerminalVisualConfig.opacity * .18F;
                return;
            }
            red = producer ? .06F : .55F + .45F * strength;
            green = producer ? .4F + .6F * strength : .18F + .13F * strength;
            blue = producer ? .13F + .13F * strength : .07F;
            alpha = (float) DeviceTerminalVisualConfig.opacity * (.25F + .75F * strength);
        }

        void updateBounds() {
            double envelope = Math.max(previousRadius, radius);
            box.setBounds(
                Math.min(coreBox.minX, centerX - envelope),
                Math.min(coreBox.minY, centerY - envelope),
                Math.min(coreBox.minZ, centerZ - envelope),
                Math.max(coreBox.maxX, centerX + envelope),
                Math.max(coreBox.maxY, centerY + envelope),
                Math.max(coreBox.maxZ, centerZ + envelope));
            // The enclosing sphere also includes the unchanged machine core at zero activity.
            double halfX = (coreBox.maxX - coreBox.minX) * .5D;
            double halfY = (coreBox.maxY - coreBox.minY) * .5D;
            double halfZ = (coreBox.maxZ - coreBox.minZ) * .5D;
            extent = Math.max(envelope, Math.sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ));
        }
    }
}
