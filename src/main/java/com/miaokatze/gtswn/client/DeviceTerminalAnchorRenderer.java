package com.miaokatze.gtswn.client;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
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

import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestDeviceTerminalData;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Bound-machine travel markers. All snapshot work is sliced on client ticks; render reads cached text only. */
public final class DeviceTerminalAnchorRenderer {

    public static final DeviceTerminalAnchorRenderer INSTANCE = new DeviceTerminalAnchorRenderer();
    private static final int WORK_PER_TICK = 64;
    private static final int LABELS_PER_FRAME = 64;
    private static final Comparator<Anchor> NEAREST = Comparator.comparingDouble((Anchor anchor) -> anchor.distance)
        .thenComparing(anchor -> anchor.entry.key);
    private static final String[] STATES = { "idle", "running", "stopped" };
    private final List<Anchor> visible = new ArrayList<>();
    private final PriorityQueue<Anchor> building = new PriorityQueue<>(NEAREST.reversed());
    private DeviceTerminalClientCache.Snapshot scanning;
    private DeviceTerminalClientCache.Snapshot displayed;
    private UUID terminal;
    private Anchor target;
    private int cursor;
    private int refreshCursor;
    private int ticks;
    private int scanDimension;
    private int scanRange;
    private int scanLimit;
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
        UUID held = DeviceTerminalVisualizationState.heldTerminalId();
        if (!DeviceTerminalVisualizationState.active(held)) {
            if (terminal != null && !terminal.equals(held)) DeviceTerminalVisualizationState.clear();
            reset();
            return;
        }
        if (terminal != null && !terminal.equals(held)) reset();
        terminal = held;
        DeviceTerminalVisualConfig.load();
        if (mc.currentScreen == null
            && (ticks % 20 == 0 || DeviceTerminalClientCache.hasMorePending(terminal) && ticks % 5 == 0)) {
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestDeviceTerminalData());
        }
        ticks++;
        DeviceTerminalClientCache.Snapshot snapshot = DeviceTerminalClientCache.getSnapshot(terminal);
        if (snapshot != null && scanning == null && (snapshot != displayed || moved(mc))) begin(snapshot, mc);
        if (scanning != null) advance(mc);
        else if (!visible.isEmpty()) {
            boolean fresh = DeviceTerminalClientCache.isFresh(terminal, System.currentTimeMillis());
            for (int i = 0; i < Math.min(WORK_PER_TICK, visible.size()); i++) {
                if (refreshCursor >= visible.size()) refreshCursor = 0;
                update(visible.get(refreshCursor++), mc, fresh);
            }
        }
        target = select(mc);
    }

    private boolean moved(Minecraft mc) {
        return scanDimension != mc.theWorld.provider.dimensionId || scanRange != range()
            || scanLimit != limit()
            || DeviceTerminalAnchorMath.distanceSquared(
                scanX,
                scanY,
                scanZ,
                mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ) > 4D;
    }

    private void begin(DeviceTerminalClientCache.Snapshot snapshot, Minecraft mc) {
        scanning = snapshot;
        cursor = 0;
        building.clear();
        scanX = mc.thePlayer.posX;
        scanY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        scanZ = mc.thePlayer.posZ;
        scanDimension = mc.theWorld.provider.dimensionId;
        scanRange = range();
        scanLimit = limit();
    }

    private void advance(Minecraft mc) {
        int end = Math.min(scanning.entries.size(), cursor + WORK_PER_TICK);
        boolean fresh = DeviceTerminalClientCache.isFresh(terminal, System.currentTimeMillis());
        for (; cursor < end; cursor++) {
            Entry entry = scanning.entries.get(cursor);
            if (entry.dim != scanDimension) continue;
            double distance = distance(entry, scanX, scanY, scanZ);
            if (!Double.isFinite(distance) || distance > scanRange * scanRange) continue;
            Anchor anchor = new Anchor(entry, distance);
            if (building.size() == scanLimit && NEAREST.compare(anchor, building.peek()) >= 0) continue;
            update(anchor, mc, fresh);
            building.offer(anchor);
            if (building.size() > scanLimit) building.poll();
        }
        if (cursor == scanning.entries.size()) {
            visible.clear();
            visible.addAll(building);
            visible.sort(NEAREST);
            displayed = scanning;
            scanning = null;
            building.clear();
            refreshCursor = 0;
        }
    }

    private static void update(Anchor anchor, Minecraft mc, boolean fresh) {
        Entry entry = anchor.entry;
        boolean loaded = entry.dim == mc.theWorld.provider.dimensionId
            && mc.theWorld.blockExists(entry.x, entry.y, entry.z);
        TileEntity tile = loaded ? mc.theWorld.getTileEntity(entry.x, entry.y, entry.z) : null;
        anchor.available = fresh && tile instanceof IGregTechTileEntity
            && (entry.metaTileId < 0 || ((IGregTechTileEntity) tile).getMetaTileID() == entry.metaTileId);
        anchor.lines.clear();
        anchor.name = DeviceTerminalNames.localName(entry);
        if (DeviceTerminalVisualConfig.showLabelName) addLine(anchor, mc, anchor.name);
        if (!anchor.available) {
            addLine(anchor, mc, tr(!fresh ? "stale" : !loaded ? "unloaded" : "missing"));
        } else {
            if (DeviceTerminalVisualConfig.showLabelState) {
                addLine(
                    anchor,
                    mc,
                    StatCollector
                        .translateToLocal("gtswn.device.gui.state." + STATES[Math.max(0, Math.min(2, entry.state))]));
            }
            if (DeviceTerminalVisualConfig.showLabelPower) {
                if (entry.evidenceKind >= 2) addLine(anchor, mc, tr("power.unknown"));
                else {
                    if (entry.energyKind != 1) addLine(
                        anchor,
                        mc,
                        StatCollector.translateToLocalFormatted(
                            "gtswn.device.visual.power.eu",
                            metric(entry.instIn),
                            metric(entry.instOut)));
                    if (entry.energyKind != 0) addLine(
                        anchor,
                        mc,
                        StatCollector.translateToLocalFormatted(
                            "gtswn.device.visual.power.steam",
                            metric(entry.steamInstIn),
                            metric(entry.steamInstOut)));
                }
            }
            if (DeviceTerminalVisualConfig.showLabelRecipe) {
                String recipe = entry.recipeOut.isEmpty() ? entry.recipeIn : entry.recipeOut;
                if (!recipe.isEmpty()) addLine(anchor, mc, recipe);
            }
        }
        anchor.width = 0;
        for (String line : anchor.lines) anchor.width = Math.max(anchor.width, mc.fontRenderer.getStringWidth(line));
    }

    private static void addLine(Anchor anchor, Minecraft mc, String line) {
        if (anchor.lines.size() < 5) anchor.lines.add(mc.fontRenderer.trimStringToWidth(line, 180));
    }

    private static String metric(long value) {
        return FormatUtil.formatMetric(BigInteger.valueOf(value), 1);
    }

    private Anchor select(Minecraft mc) {
        if (!canRender(mc) || !DeviceTerminalClientCache.isFresh(terminal, System.currentTimeMillis())) return null;
        Vec3 look = mc.thePlayer.getLookVec();
        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
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

    public String currentTargetKey() {
        target = select(Minecraft.getMinecraft());
        return target == null ? null : target.entry.key;
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!canRender(mc) || visible.isEmpty()) return;
        Entity camera = mc.renderViewEntity == null ? mc.thePlayer : mc.renderViewEntity;
        double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * event.partialTicks;
        double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * event.partialTicks;
        double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * event.partialTicks;
        Frustrum frustum = new Frustrum();
        frustum.setPosition(x, y, z);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            if (DeviceTerminalVisualConfig.labelDepthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            int labels = 0;
            if (target != null && drawable(target, mc, frustum, x, y, z)) {
                draw(target, mc, x, y, z, true);
                labels++;
            }
            for (Anchor anchor : visible) {
                if (anchor == target || !drawable(anchor, mc, frustum, x, y, z)) continue;
                draw(anchor, mc, x, y, z, labels++ < LABELS_PER_FRAME);
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static boolean drawable(Anchor anchor, Minecraft mc, Frustrum frustum, double x, double y, double z) {
        Entry entry = anchor.entry;
        return entry.dim == mc.theWorld.provider.dimensionId
            && distance(entry, x, y + mc.thePlayer.getEyeHeight(), z) <= range() * range()
            && frustum.isBoundingBoxInFrustum(anchor.bounds);
    }

    private void draw(Anchor anchor, Minecraft mc, double x, double y, double z, boolean labelBudget) {
        Entry entry = anchor.entry;
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(entry.x + 0.5D - x, entry.y + 1.6D - y, entry.z + 0.5D - z);
            GL11.glRotatef(-RenderManager.instance.playerViewY, 0F, 1F, 0F);
            GL11.glRotatef(RenderManager.instance.playerViewX, 1F, 0F, 0F);
            float scale = (float) Math
                .max(0.027D, Math.min(0.15D, 0.027D + Math.sqrt(distance(entry, x, y, z)) * 0.001D));
            GL11.glScalef(-scale, -scale, scale);
            boolean selected = anchor == target;
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(
                selected ? 1F : 0.35F,
                selected ? 0.82F : 0.9F,
                selected ? 0.35F : 0.95F,
                anchor.available ? 0.95F : 0.5F);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2d(0D, -5D);
            GL11.glVertex2d(5D, 0D);
            GL11.glVertex2d(0D, 5D);
            GL11.glVertex2d(-5D, 0D);
            GL11.glEnd();
            if (labelBudget && DeviceTerminalVisualConfig.showLabels && !anchor.lines.isEmpty()) {
                int half = anchor.width / 2 + 4;
                int bottom = 12 + anchor.lines.size() * 10;
                GL11.glColor4f(selected ? 0.17F : 0.04F, selected ? 0.13F : 0.055F, 0.07F, selected ? 0.85F : 0.68F);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2d(-half, 7);
                GL11.glVertex2d(-half, bottom);
                GL11.glVertex2d(half, bottom);
                GL11.glVertex2d(half, 7);
                GL11.glEnd();
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glColor4f(1F, 1F, 1F, 1F);
                int textY = 9;
                for (String line : anchor.lines) {
                    mc.fontRenderer.drawString(line, -mc.fontRenderer.getStringWidth(line) / 2, textY, 0xEEEEEE);
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
        String notice = scanning != null
            ? StatCollector.translateToLocalFormatted("gtswn.device.visual.progress", cursor, scanning.entries.size())
            : DeviceTerminalClientCache.hasMorePending(terminal) ? tr("loading")
                : visible.isEmpty() ? tr("empty")
                    : target == null ? ""
                        : tr("teleport") + (DeviceTerminalVisualConfig.showLabelName ? " " + target.name : "");
        String hint = tr("hint");
        int y = sr.getScaledHeight() / 2 + 28;
        mc.fontRenderer
            .drawStringWithShadow(hint, (sr.getScaledWidth() - mc.fontRenderer.getStringWidth(hint)) / 2, y, 0xDDDDDD);
        if (!notice.isEmpty()) mc.fontRenderer.drawStringWithShadow(
            notice,
            (sr.getScaledWidth() - mc.fontRenderer.getStringWidth(notice)) / 2,
            y + 11,
            target == null ? 0xBBBBBB : 0xFFD68D);
        DeviceTerminalClientCache.Snapshot snapshot = DeviceTerminalClientCache.getSnapshot(terminal);
        String count = StatCollector.translateToLocalFormatted(
            "gtswn.device.visual.count",
            visible.size(),
            snapshot == null ? 0 : snapshot.entries.size());
        mc.fontRenderer.drawStringWithShadow(
            count,
            (sr.getScaledWidth() - mc.fontRenderer.getStringWidth(count)) / 2,
            y + 22,
            0xBBBBBB);
    }

    private boolean canRender(Minecraft mc) {
        return mc.theWorld != null && mc.thePlayer != null
            && !mc.gameSettings.hideGUI
            && mc.currentScreen == null
            && terminal != null
            && DeviceTerminalVisualizationState.active(terminal)
            && terminal.equals(DeviceTerminalVisualizationState.heldTerminalId());
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
        visible.clear();
        building.clear();
        scanning = null;
        displayed = null;
        terminal = null;
        target = null;
        cursor = 0;
        ticks = 0;
        refreshCursor = 0;
    }

    private static final class Anchor {

        final Entry entry;
        final double distance;
        final AxisAlignedBB bounds;
        final List<String> lines = new ArrayList<>();
        boolean available;
        String name;
        int width;

        Anchor(Entry entry, double distance) {
            this.entry = entry;
            this.distance = distance;
            this.bounds = AxisAlignedBB
                .getBoundingBox(entry.x - 1D, entry.y, entry.z - 1D, entry.x + 2D, entry.y + 3D, entry.z + 2D);
        }
    }
}
