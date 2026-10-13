package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestDeviceTerminalData;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Bounded client heat view of already bound devices; no world enumeration or per-frame networking. */
public final class DeviceEnergyWorldRenderer {

    private final List<Visual> visible = new ArrayList<>();
    private final Map<String, Double> smoothed = new HashMap<>();
    private UUID terminal;
    private int ticks;
    private double maxElectric;
    private double maxSteam;

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            clear();
            return;
        }
        DeviceTerminalVisualConfig.load();
        ItemStack stack = mc.thePlayer.getHeldItem();
        if (!DeviceTerminalVisualConfig.enabled || !DeviceTerminalVisualizationState.isActiveHeld()
            || stack == null
            || !(stack.getItem() instanceof ItemDeviceInfoTerminal)) {
            clear();
            return;
        }
        UUID id = ItemDeviceInfoTerminal.readTerminalId(stack);
        if (id == null || !id.equals(terminal)) {
            clear();
            terminal = id;
        }
        // Existing data request queue coalesces this with the GUI's poll and reuses its page/version cursor.
        if (ticks++ % 20 == 0 && mc.currentScreen == null) {
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestDeviceTerminalData());
        }
        if (id == null || ticks % 5 != 0) return;
        DeviceTerminalClientCache.Snapshot snapshot = DeviceTerminalClientCache.getSnapshot(id);
        if (snapshot == null || !DeviceTerminalClientCache.isFresh(id, System.currentTimeMillis())) {
            visible.clear();
            smoothed.clear();
            return;
        }
        List<Entry> candidates = new ArrayList<>();
        double distanceLimit = DeviceTerminalVisualConfig.range * DeviceTerminalVisualConfig.range;
        for (Entry entry : snapshot.entries) {
            if (entry.dim == mc.theWorld.provider.dimensionId && distanceSquared(entry, mc.thePlayer) <= distanceLimit
                && mc.theWorld.blockExists(entry.x, entry.y, entry.z)) candidates.add(entry);
        }
        // Closest machines remain useful even for terminals with many distant bindings.
        candidates.sort(Comparator.comparingDouble(entry -> distanceSquared(entry, mc.thePlayer)));
        if (candidates.size() > DeviceTerminalVisualConfig.maxDevices) {
            candidates = candidates.subList(0, DeviceTerminalVisualConfig.maxDevices);
        }
        List<Visual> next = new ArrayList<>();
        double electricMax = 0D;
        double steamMax = 0D;
        for (Entry entry : candidates) {
            if (entry.energyKind != 1) {
                Visual visual = create(entry, false);
                if (visual != null) {
                    next.add(visual);
                    electricMax = Math.max(electricMax, Math.abs(visual.value));
                }
            }
            if (entry.energyKind != 0) {
                Visual visual = create(entry, true);
                if (visual != null) {
                    next.add(visual);
                    steamMax = Math.max(steamMax, Math.abs(visual.value));
                }
            }
        }
        Map<String, Double> newSmoothed = new HashMap<>();
        maxElectric = electricMax;
        maxSteam = steamMax;
        for (Visual visual : next) {
            double target = DeviceEnergyVisualMath.intensity(visual.value, visual.steam ? steamMax : electricMax);
            String key = visual.entry.key + (visual.steam ? ":steam" : ":eu");
            Double previous = smoothed.get(key);
            visual.intensity = previous == null ? target
                : DeviceEnergyVisualMath.smooth(previous, target, DeviceTerminalVisualConfig.smoothing);
            newSmoothed.put(key, visual.intensity);
        }
        smoothed.clear();
        smoothed.putAll(newSmoothed);
        // Transparent boxes are drawn from far to near.
        next.sort(
            Comparator.comparingDouble((Visual value) -> distanceSquared(value.entry, mc.thePlayer))
                .reversed());
        visible.clear();
        visible.addAll(next);
    }

    private static Visual create(Entry entry, boolean steam) {
        double value = DeviceEnergyVisualMath.weighted(
            steam ? entry.steamInstNet() : entry.instNet(),
            steam ? entry.steamAvgNet() : entry.avgNet(),
            DeviceTerminalVisualConfig.averageWeight);
        if (value == 0D || !DeviceTerminalVisualConfig.visible(steam, value > 0D)) return null;
        return new Visual(entry, steam, value);
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (!DeviceTerminalVisualConfig.enabled || !DeviceTerminalVisualizationState.isActiveHeld()
            || visible.isEmpty()
            || mc.theWorld == null
            || held == null
            || !(held.getItem() instanceof ItemDeviceInfoTerminal)
            || terminal == null
            || !terminal.equals(ItemDeviceInfoTerminal.readTerminalId(held))
            || !DeviceTerminalClientCache.isFresh(terminal, System.currentTimeMillis())) return;
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
            GL11.glTranslated(-x, -y, -z);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
            if (DeviceTerminalVisualConfig.depthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glLineWidth(Math.max(1F, mc.displayHeight / 540F));
            for (Visual visual : visible) {
                Entry entry = visual.entry;
                if (entry.dim != mc.theWorld.provider.dimensionId
                    || distanceSquared(entry, mc.thePlayer)
                        > DeviceTerminalVisualConfig.range * DeviceTerminalVisualConfig.range
                    || !mc.theWorld.blockExists(entry.x, entry.y, entry.z)) continue;
                double expand = visual.steam && entry.energyKind == 2 ? 0.035D : 0.006D;
                AxisAlignedBB box = AxisAlignedBB.getBoundingBox(
                    entry.x - expand,
                    entry.y - expand,
                    entry.z - expand,
                    entry.x + 1D + expand,
                    entry.y + 1D + expand,
                    entry.z + 1D + expand);
                if (!frustum.isBoundingBoxInFrustum(box)) continue;
                draw(visual, box, visual.steam && entry.energyKind == 2);
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static void draw(Visual visual, AxisAlignedBB box, boolean outlineOnly) {
        float strength = (float) visual.intensity;
        boolean producer = visual.value > 0D;
        float red;
        float green;
        float blue;
        if (visual.steam) {
            // Steam production lightens with flow; steam consumption darkens toward coal black.
            red = green = blue = producer ? 0.38F + strength * 0.42F : 0.22F - strength * 0.20F;
        } else {
            red = producer ? 0.06F : 0.55F + 0.45F * strength;
            green = producer ? 0.40F + 0.60F * strength : 0.18F + 0.13F * strength;
            blue = producer ? 0.13F + 0.13F * strength : 0.07F;
        }
        float alpha = (float) DeviceTerminalVisualConfig.opacity * (0.20F + 0.80F * strength);
        if (!outlineOnly) {
            GL11.glColor4f(red, green, blue, alpha * 0.65F);
            GL11.glBegin(GL11.GL_QUADS);
            face(
                box.minX,
                box.minY,
                box.minZ,
                box.maxX,
                box.minY,
                box.minZ,
                box.maxX,
                box.maxY,
                box.minZ,
                box.minX,
                box.maxY,
                box.minZ);
            face(
                box.minX,
                box.minY,
                box.maxZ,
                box.maxX,
                box.minY,
                box.maxZ,
                box.maxX,
                box.maxY,
                box.maxZ,
                box.minX,
                box.maxY,
                box.maxZ);
            face(
                box.minX,
                box.minY,
                box.minZ,
                box.minX,
                box.minY,
                box.maxZ,
                box.minX,
                box.maxY,
                box.maxZ,
                box.minX,
                box.maxY,
                box.minZ);
            face(
                box.maxX,
                box.minY,
                box.minZ,
                box.maxX,
                box.minY,
                box.maxZ,
                box.maxX,
                box.maxY,
                box.maxZ,
                box.maxX,
                box.maxY,
                box.minZ);
            face(
                box.minX,
                box.minY,
                box.minZ,
                box.maxX,
                box.minY,
                box.minZ,
                box.maxX,
                box.minY,
                box.maxZ,
                box.minX,
                box.minY,
                box.maxZ);
            face(
                box.minX,
                box.maxY,
                box.minZ,
                box.maxX,
                box.maxY,
                box.minZ,
                box.maxX,
                box.maxY,
                box.maxZ,
                box.minX,
                box.maxY,
                box.maxZ);
            GL11.glEnd();
        }
        GL11.glColor4f(red, green, blue, Math.min(1F, alpha * 1.7F + 0.12F));
        GL11.glBegin(GL11.GL_LINES);
        for (int side = 0; side < 2; side++) {
            double y = side == 0 ? box.minY : box.maxY;
            edge(box.minX, y, box.minZ, box.maxX, y, box.minZ);
            edge(box.maxX, y, box.minZ, box.maxX, y, box.maxZ);
            edge(box.maxX, y, box.maxZ, box.minX, y, box.maxZ);
            edge(box.minX, y, box.maxZ, box.minX, y, box.minZ);
        }
        edge(box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ);
        edge(box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ);
        edge(box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ);
        edge(box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ);
        GL11.glEnd();
    }

    private static void face(double x1, double y1, double z1, double x2, double y2, double z2, double x3, double y3,
        double z3, double x4, double y4, double z4) {
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
        GL11.glVertex3d(x3, y3, z3);
        GL11.glVertex3d(x4, y4, z4);
    }

    private static void edge(double x1, double y1, double z1, double x2, double y2, double z2) {
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
    }

    private static double distanceSquared(Entry entry, Entity player) {
        double x = entry.x + 0.5D - player.posX;
        double y = entry.y + 0.5D - player.posY;
        double z = entry.z + 0.5D - player.posZ;
        return x * x + y * y + z * z;
    }

    @SubscribeEvent
    public void legend(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT || visible.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (!DeviceTerminalVisualConfig.enabled || !DeviceTerminalVisualizationState.isActiveHeld()
            || mc.gameSettings.hideGUI
            || held == null
            || !(held.getItem() instanceof ItemDeviceInfoTerminal)
            || terminal == null
            || !terminal.equals(ItemDeviceInfoTerminal.readTerminalId(held))
            || !DeviceTerminalClientCache.isFresh(terminal, System.currentTimeMillis())) return;
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        String[] keys = { "consumer", "generator", "steam.producer", "steam.consumer" };
        int[] colors = { 0xFF6E36, 0x44EA77, 0xC2C2C2, 0x707070 };
        String[] lines = new String[6];
        int textWidth = 0;
        for (int i = 0; i < keys.length; i++) {
            lines[i] = StatCollector.translateToLocal("gtswn.device.settings." + keys[i]);
            textWidth = Math.max(textWidth, mc.fontRenderer.getStringWidth(lines[i]));
        }
        lines[4] = StatCollector.translateToLocalFormatted(
            "gtswn.device.settings.maximum.eu",
            FormatUtil.formatMetricDouble(maxElectric, 1));
        lines[5] = StatCollector.translateToLocalFormatted(
            "gtswn.device.settings.maximum.steam",
            FormatUtil.formatMetricDouble(maxSteam, 1));
        for (String line : lines) textWidth = Math.max(textWidth, mc.fontRenderer.getStringWidth(line));
        int x = resolution.getScaledWidth() - textWidth - 8;
        for (int i = 0; i < lines.length; i++) {
            mc.fontRenderer.drawStringWithShadow(lines[i], x, 70 + i * 11, i < 4 ? colors[i] : 0xDDDDDD);
        }
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
        smoothed.clear();
        terminal = null;
        ticks = 0;
    }

    private static final class Visual {

        final Entry entry;
        final boolean steam;
        final double value;
        double intensity;

        Visual(Entry entry, boolean steam, double value) {
            this.entry = entry;
            this.steam = steam;
            this.value = value;
        }
    }
}
