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

import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Filled thermal layers over the anchor renderer's bounded, already observed devices. */
public final class DeviceEnergyWorldRenderer {

    private static final int WORK_PER_TICK = 32;
    private static final int OUTLINE_BUDGET = 64;
    // 320 * 24 vertices * 8 ints = 61,440: fits Forge's default 65,536-int raw buffer.
    // A larger single batch repeatedly grows and is then shrunk by native Tessellator.draw().
    private static final int FILL_LAYER_BUDGET = 320;
    private static final int[] LEGEND_COLORS = { 0xFF6E36, 0x44EA77, 0xC2C2C2, 0x707070 };
    private final List<Visual> visible = new ArrayList<>(1024);
    private final List<Visual> building = new ArrayList<>(1024);
    private final List<Visual> drawn = new ArrayList<>(1024);
    private final Map<String, Visual> electricPrevious = new HashMap<>();
    private final Map<String, Visual> steamPrevious = new HashMap<>();
    private List<DeviceTerminalAnchorRenderer.Anchor> source;
    private UUID terminal;
    private long sourceGeneration = Long.MIN_VALUE;
    private long buildGeneration;
    private long buildEpoch;
    private int cursor;
    private int ticks;
    private int appearance;
    private int buildAppearance;
    private double maxElectric;
    private double maxSteam;
    private double buildElectricMax;
    private double buildSteamMax;
    private String[] legendLines = new String[0];
    private int legendWidth;
    private String legendLanguage;
    public int lastRenderedLayers;
    public int lastFilledBatches;
    public int lastLineBatches;

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            clear();
            return;
        }
        DeviceTerminalVisualConfig.load();
        UUID held = DeviceTerminalVisualizationState.heldTerminalId();
        if (!DeviceTerminalVisualConfig.enabled || !DeviceTerminalVisualizationState.active(held)) {
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
        int settings = DeviceTerminalVisualConfig.appearanceKey();
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
            buildSteamMax = 0D;
        }
        // A soft camera/order rebuild never pauses or restarts an already bounded thermal build.
        if (source != null) advance();
        if (ticks % 5 == 0) {
            for (Visual visual : visible) {
                visual.intensity = DeviceEnergyVisualMath
                    .smooth(visual.intensity, visual.target, DeviceTerminalVisualConfig.smoothing);
                visual.updateColor();
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
            if (entry.energyKind != 1) add(device, false);
            if (entry.energyKind != 0) add(device, true);
        }
        if (cursor < source.size()) return;
        maxElectric = buildElectricMax;
        maxSteam = buildSteamMax;
        for (Visual visual : building) {
            visual.target = DeviceEnergyVisualMath.intensity(visual.magnitude, visual.steam ? maxSteam : maxElectric);
            Visual previous = (visual.steam ? steamPrevious : electricPrevious).get(visual.device.entry.key);
            visual.intensity = previous == null ? visual.target : previous.intensity;
            visual.updateColor();
        }
        visible.clear();
        visible.addAll(building);
        electricPrevious.clear();
        steamPrevious.clear();
        for (Visual visual : visible)
            (visual.steam ? steamPrevious : electricPrevious).put(visual.device.entry.key, visual);
        sourceGeneration = buildGeneration;
        appearance = buildAppearance;
        source = null;
        updateLegend(Minecraft.getMinecraft());
    }

    private void add(DeviceTerminalAnchorRenderer.Anchor device, boolean steam) {
        Entry entry = device.entry;
        double input = DeviceEnergyVisualMath.weighted(
            steam ? entry.steamInstIn : entry.instIn,
            steam ? entry.steamAvgIn : entry.avgIn,
            DeviceTerminalVisualConfig.averageWeight);
        double output = DeviceEnergyVisualMath.weighted(
            steam ? entry.steamInstOut : entry.instOut,
            steam ? entry.steamAvgOut : entry.avgOut,
            DeviceTerminalVisualConfig.averageWeight);
        boolean producer = output > input || output == input && entry.powerType == 1;
        if (!DeviceTerminalVisualConfig.visible(steam, producer)) return;
        // Balanced input/output is still activity: net-zero must not hide a working device.
        double magnitude = Math.max(0D, Math.max(input, output));
        Visual visual = new Visual(device, steam, producer, magnitude, entry.evidenceKind >= 2 || magnitude == 0D);
        building.add(visual);
        if (steam) buildSteamMax = Math.max(buildSteamMax, magnitude);
        else buildElectricMax = Math.max(buildElectricMax, magnitude);
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        lastRenderedLayers = lastFilledBatches = lastLineBatches = 0;
        Minecraft mc = Minecraft.getMinecraft();
        if (!renderable(mc) || visible.isEmpty()) return;
        Entity camera = mc.renderViewEntity == null ? mc.thePlayer : mc.renderViewEntity;
        double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * event.partialTicks;
        double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * event.partialTicks;
        double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * event.partialTicks;
        Frustrum frustum = new Frustrum();
        frustum.setPosition(x, y, z);
        drawn.clear();
        int range = DeviceTerminalVisualConfig.range;
        for (Visual visual : visible) {
            Entry entry = visual.device.entry;
            if (!visual.device.loaded || entry.dim != mc.theWorld.provider.dimensionId) continue;
            double dx = entry.x + .5D - x, dy = entry.y + .5D - y, dz = entry.z + .5D - z;
            if (dx * dx + dy * dy + dz * dz <= range * range && frustum.isBoundingBoxInFrustum(visual.box))
                drawn.add(visual);
        }
        if (drawn.isEmpty()) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
            // Vanilla's inherited GREATER 0.1 alpha test otherwise discards genuinely translucent heat faces.
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            if (DeviceTerminalVisualConfig.depthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glColor4f(1F, 1F, 1F, 1F);
            Tessellator tess = Tessellator.instance;
            tess.startDrawingQuads();
            int batchLayers = 0;
            // Device groups are cached nearest-first; reverse groups for translucent far-to-near compositing.
            // Within a mixed device, EU fills first and the distinct steam top slab fills last.
            for (int i = drawn.size() - 1; i >= 0; i--) {
                Visual visual = drawn.get(i);
                if (visual.steam && i > 0 && drawn.get(i - 1).device == visual.device) {
                    Visual electric = drawn.get(--i);
                    batchLayers = fillLayer(tess, electric, x, y, z, batchLayers);
                }
                batchLayers = fillLayer(tess, visual, x, y, z, batchLayers);
            }
            tess.draw();
            lastFilledBatches++;
            lastRenderedLayers = drawn.size();
            // A bounded edge batch supplies shape detail; the filled color layer is the primary visual.
            GL11.glLineWidth(Math.max(1F, mc.displayHeight / 720F));
            tess.startDrawing(GL11.GL_LINES);
            int outlines = 0;
            for (Visual visual : drawn) {
                if (outlines >= OUTLINE_BUDGET) break;
                if (visual.neutral || visual.intensity < .85D) continue;
                tess.setColorRGBA_F(visual.red, visual.green, visual.blue, visual.alpha * .6F);
                outline(tess, visual.box, x, y, z);
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

    private int fillLayer(Tessellator tess, Visual visual, double x, double y, double z, int batchLayers) {
        if (batchLayers == FILL_LAYER_BUDGET) {
            tess.draw();
            lastFilledBatches++;
            tess.startDrawingQuads();
            batchLayers = 0;
        }
        tess.setColorRGBA_F(visual.red, visual.green, visual.blue, visual.alpha);
        fill(tess, visual.box, x, y, z);
        return batchLayers + 1;
    }

    private boolean renderable(Minecraft mc) {
        return mc.theWorld != null && mc.thePlayer != null
            && DeviceTerminalVisualConfig.enabled
            && terminal != null
            && DeviceTerminalVisualizationState.active(terminal)
            && terminal.equals(DeviceTerminalVisualizationState.heldTerminalId())
            && DeviceTerminalClientCache.isFresh(terminal, System.currentTimeMillis());
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
        String[] keys = { "consumer", "generator", "steam.producer", "steam.consumer" };
        legendLines = new String[6];
        legendWidth = 0;
        for (int i = 0; i < 4; i++) legendLines[i] = StatCollector.translateToLocal("gtswn.device.settings." + keys[i]);
        legendLines[4] = StatCollector.translateToLocalFormatted(
            "gtswn.device.settings.maximum.eu",
            FormatUtil.formatMetricDouble(maxElectric, 1));
        legendLines[5] = StatCollector.translateToLocalFormatted(
            "gtswn.device.settings.maximum.steam",
            FormatUtil.formatMetricDouble(maxSteam, 1));
        for (String line : legendLines) legendWidth = Math.max(legendWidth, mc.fontRenderer.getStringWidth(line));
    }

    @SubscribeEvent
    public void legend(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT || !renderable(mc)
            || mc.gameSettings.hideGUI
            || visible.isEmpty()) return;
        int x = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaledWidth() - legendWidth - 8;
        for (int i = 0; i < legendLines.length; i++)
            mc.fontRenderer.drawStringWithShadow(legendLines[i], x, 70 + i * 11, i < 4 ? LEGEND_COLORS[i] : 0xDDDDDD);
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
        steamPrevious.clear();
        source = null;
        terminal = null;
        sourceGeneration = Long.MIN_VALUE;
        ticks = 0;
        lastRenderedLayers = lastFilledBatches = lastLineBatches = 0;
    }

    static final class Visual {

        final DeviceTerminalAnchorRenderer.Anchor device;
        final AxisAlignedBB box;
        final boolean steam;
        final boolean producer;
        final boolean neutral;
        final double magnitude;
        double target;
        double intensity;
        float red, green, blue, alpha;

        Visual(DeviceTerminalAnchorRenderer.Anchor device, boolean steam, boolean producer, double magnitude,
            boolean neutral) {
            this.device = device;
            this.steam = steam;
            this.producer = producer;
            this.magnitude = magnitude;
            this.neutral = neutral;
            box = steam && device.entry.energyKind == 2 ? device.steamBounds : device.heatBounds;
        }

        void updateColor() {
            float strength = (float) intensity;
            if (neutral) {
                red = green = blue = .42F;
                alpha = (float) DeviceTerminalVisualConfig.opacity * .18F;
                return;
            }
            if (steam) red = green = blue = producer ? .38F + strength * .42F : .22F - strength * .20F;
            else {
                red = producer ? .06F : .55F + .45F * strength;
                green = producer ? .4F + .6F * strength : .18F + .13F * strength;
                blue = producer ? .13F + .13F * strength : .07F;
            }
            alpha = (float) DeviceTerminalVisualConfig.opacity * (.25F + .75F * strength);
        }
    }
}
