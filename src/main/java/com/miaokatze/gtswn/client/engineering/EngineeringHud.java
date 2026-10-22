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

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.PortableMonitorClientCache.PinnedLine;
import com.miaokatze.gtswn.client.engineering.EngineeringModeController.Target;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot.Source;
import com.miaokatze.gtswn.client.gui.PortableMonitorCharts;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.miaokatzecore.client.render.GlScope;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** A nonmodal engineering workspace. Its data is a view of existing terminal HUD subscriptions. */
public final class EngineeringHud extends Gui {

    private static final int BACK = 0xEB17202A, HEADER = 0xF0233543, CARD = 0xD9273642;
    private static final int ACCENT = 0xFF65C6C2, TEXT = 0xFFE7F0F5, MUTED = 0xFF9AAEBB;
    private static int scroll, tab;
    private static List<Row> rows = new ArrayList<>();
    private static int contentHeight;
    private static EngineeringTerminalHudSnapshot renderedSnapshot;
    private static int renderedTab = -1;

    static void reset() {
        scroll = 0;
        tab = 0;
        rows.clear();
        contentHeight = 0;
        renderedSnapshot = null;
        renderedTab = -1;
    }

    public static double scale(int width, int height) {
        return Math.min(1, Math.min((width - 16) / 480.0, (height - 12) / 340.0));
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
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        double scale = scale(resolution.getScaledWidth(), resolution.getScaledHeight());
        int width = (int) (resolution.getScaledWidth() / scale);
        int height = (int) (resolution.getScaledHeight() / scale);
        try (GlScope ignored = new GlScope()) {
            GL11.glScaled(scale, scale, 1);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            renderTools(mc.fontRenderer);
            renderSelection(mc.fontRenderer);
            renderRail(mc, width, height);
        }
    }

    private static void renderTools(FontRenderer font) {
        EngineeringModeController camera = EngineeringModeController.INSTANCE;
        rect(12, 12, 204, 78, BACK);
        rect(12, 12, 204, 31, HEADER);
        text(font, tr("title"), 20, 18, ACCENT, 172);
        button(font, 20, 38, 86, camera.following() ? tr("following") : tr("free"));
        button(font, 110, 38, 86, tr("return"));
        text(font, tr("navigation"), 20, 60, MUTED, 176);
    }

    private static void renderSelection(FontRenderer font) {
        Target selected = EngineeringModeController.INSTANCE.selected();
        if (selected == null) return;
        rect(12, 268, 204, 330, BACK);
        rect(12, 268, 204, 283, HEADER);
        text(font, selected.name, 20, 272, TEXT, 176);
        text(font, selected.x + " / " + selected.y + " / " + selected.z, 20, 288, MUTED, 176);
        button(font, 20, 307, 54, tr("focus"));
        button(font, 78, 307, 56, EngineeringModeController.INSTANCE.pinned() ? tr("unpin") : tr("pin"));
        button(font, 138, 307, 58, tr("clear"));
    }

    private static void renderRail(Minecraft mc, int width, int height) {
        FontRenderer font = mc.fontRenderer;
        int left = width - 252, top = 12, bottom = height - 12;
        rect(left, top, left + 240, bottom, BACK);
        rect(left, top, left + 240, 35, HEADER);
        text(font, tr("hud"), left + 10, 20, ACCENT, 200);
        String[] tabs = { tr("all"), tr("devices"), tr("wireless") };
        for (int i = 0; i < 3; i++) {
            rect(left + 8 + i * 76, 41, left + 80 + i * 76, 58, i == tab ? 0xFF345961 : CARD);
            text(font, tabs[i], left + 14 + i * 76, 46, i == tab ? ACCENT : MUTED, 60);
        }
        EngineeringTerminalHudSnapshot snapshot = EngineeringTerminalHudSnapshot.capture(mc.thePlayer);
        if (snapshot != renderedSnapshot || tab != renderedTab) {
            rows = buildRows(snapshot);
            renderedSnapshot = snapshot;
            renderedTab = tab;
        }
        contentHeight = 0;
        for (Row row : rows) contentHeight += row.height + 4;
        int visible = Math.max(1, bottom - 82);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - visible)));
        int y = 66 - scroll;
        // The rail uses physical framebuffer scissoring, so chart and text cannot escape its scroll viewport.
        int clipLeft = (int) Math.floor((left + 6) * mc.displayWidth / (double) width);
        int clipRight = (int) Math.ceil((left + 234) * mc.displayWidth / (double) width);
        int clipBottom = (int) Math.floor(20 * mc.displayHeight / (double) height);
        int clipTop = (int) Math.ceil((height - 64) * mc.displayHeight / (double) height);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(clipLeft, clipBottom, clipRight - clipLeft, Math.max(0, clipTop - clipBottom));
        for (Row row : rows) {
            if (y + row.height >= 64 && y < bottom - 8) drawRow(font, row, left + 8, y, 224);
            y += row.height + 4;
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        if (contentHeight > visible) {
            int track = bottom - 82;
            int thumb = Math.max(12, track * visible / contentHeight);
            int at = 66 + (track - thumb) * scroll / Math.max(1, contentHeight - visible);
            rect(left + 235, 66, left + 237, bottom - 16, CARD);
            rect(left + 235, at, left + 237, at + thumb, ACCENT);
        }
        text(font, tr("scroll"), left + 10, bottom - 10, MUTED, 220);
    }

    private static List<Row> buildRows(EngineeringTerminalHudSnapshot snapshot) {
        List<Row> result = new ArrayList<>();
        if (tab != 2) {
            Source device = snapshot.device;
            result.add(Row.header(tr("devices") + " · " + source(device)));
            if (!device.enabled) result.add(Row.note(tr("device.empty")));
            else if (device.devices.isEmpty()) result.add(Row.note(tr("waiting")));
            else for (Entry entry : device.devices) result.add(Row.device(entry, device.fresh));
        }
        if (tab != 1) {
            Source portable = snapshot.portable;
            result.add(Row.header(tr("wireless") + " · " + source(portable)));
            if (!portable.enabled) result.add(Row.note(tr("wireless.empty")));
            else {
                for (String line : portable.basicLines) result.add(Row.note(line));
                for (PinnedLine line : portable.pinnedLines) result.add(Row.pinned(line, portable.fresh));
                if (portable.basicLines.isEmpty() && portable.pinnedLines.isEmpty())
                    result.add(Row.note(tr("waiting")));
            }
        }
        return result;
    }

    private static String source(Source source) {
        return source.terminalId == null && !source.enabled ? tr("absent")
            : source.bauble ? tr("bauble") : tr("inventory");
    }

    private static void drawRow(FontRenderer font, Row row, int x, int y, int width) {
        rect(x, y, x + width, y + row.height, row.header ? HEADER : CARD);
        if (row.entry != null) {
            Entry entry = row.entry;
            int color = !row.fresh ? MUTED : entry.state == 1 ? 0xFF7BDCA1 : entry.state == 2 ? 0xFFF4BA6A : MUTED;
            rect(x, y, x + 3, y + row.height, color);
            text(font, entry.displayName.isEmpty() ? entry.name : entry.displayName, x + 8, y + 6, TEXT, width - 16);
            text(
                font,
                "DIM " + entry.dim + "  " + entry.x + " / " + entry.y + " / " + entry.z,
                x + 8,
                y + 19,
                MUTED,
                width - 16);
            String state = !row.fresh ? tr("stale") : tr("state." + Math.max(0, Math.min(3, entry.state)));
            text(
                font,
                state + "  "
                    + FormatUtil.formatMetricDouble(entry.instIn)
                    + " → "
                    + FormatUtil.formatMetricDouble(entry.instOut)
                    + " EU/t",
                x + 8,
                y + 32,
                color,
                width - 16);
            String recipe = entry.recipeOut.isEmpty() ? entry.recipeIn : entry.recipeOut;
            if (!recipe.isEmpty()) text(font, recipe, x + 8, y + 45, MUTED, width - 16);
        } else if (row.pinned != null && row.pinned.graph) {
            PortableMonitorCharts.drawCompact(row.pinned, x + 8, y + 6, width - 16, row.height - 8, font);
            if (!row.fresh) text(font, tr("stale"), x + width - 48, y + 6, MUTED, 44);
        } else text(font, row.text, x + 8, y + 6, row.header ? ACCENT : TEXT, width - 16);
    }

    static boolean mouse(MouseEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        double scale = scale(resolution.getScaledWidth(), resolution.getScaledHeight());
        int width = (int) (resolution.getScaledWidth() / scale), height = (int) (resolution.getScaledHeight() / scale);
        double x = event.x * resolution.getScaledWidth() / (double) mc.displayWidth / scale;
        double y = (mc.displayHeight - event.y - 1) * resolution.getScaledHeight() / (double) mc.displayHeight / scale;
        int left = width - 252;
        if (x >= left && x < left + 240 && y >= 12 && y < height - 12) {
            if (event.dwheel != 0) scroll = Math.max(0, scroll + (event.dwheel > 0 ? -28 : 28));
            if (event.button == 0 && event.buttonstate) {
                if (y >= 41 && y < 58) {
                    tab = Math.max(0, Math.min(2, (int) (x - left - 8) / 76));
                    scroll = 0;
                } else if (y >= 64 && y < height - 20) {
                    int rowY = 66 - scroll;
                    for (Row row : rows) {
                        if (y >= rowY && y < rowY + row.height
                            && row.entry != null
                            && row.entry.dim == mc.theWorld.provider.dimensionId) {
                            EngineeringModeController.INSTANCE.select(row.entry);
                            EngineeringModeController.focus(row.entry.x, row.entry.y, row.entry.z);
                            break;
                        }
                        rowY += row.height + 4;
                    }
                }
            }
            return true;
        }
        if (x >= 12 && x < 204 && y >= 12 && y < 78) {
            if (event.button == 0 && event.buttonstate && y >= 38 && y < 55) {
                if (x < 108) EngineeringModeController.INSTANCE.toggleFollowing();
                else EngineeringModeController.INSTANCE.returnToPlayer();
            }
            return true;
        }
        if (EngineeringModeController.INSTANCE.selected() != null && x >= 12 && x < 204 && y >= 268 && y < 330) {
            if (event.button == 0 && event.buttonstate && y >= 307 && y < 324) {
                Target target = EngineeringModeController.INSTANCE.selected();
                if (x < 76) EngineeringModeController.focus(target.x, target.y, target.z);
                else if (x < 136) EngineeringModeController.INSTANCE.togglePin();
                else EngineeringModeController.INSTANCE.clearSelection();
            }
            return true;
        }
        return false;
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

    private static String tr(String name) {
        return StatCollector.translateToLocal("gtswn.engineering." + name);
    }

    private static void text(FontRenderer font, String value, int x, int y, int color, int width) {
        font.drawString(font.trimStringToWidth(value, width), x, y, color);
    }

    private static void button(FontRenderer font, int x, int y, int width, String value) {
        rect(x, y, x + width, y + 17, CARD);
        rect(x, y + 16, x + width, y + 17, 0x805CA9AC);
        text(font, value, x + 6, y + 5, TEXT, width - 12);
    }

    private static void rect(int x, int y, int right, int bottom, int color) {
        Gui.drawRect(x, y, right, bottom, color);
    }

    private static final class Row {

        String text = "";
        int height;
        boolean header, fresh;
        Entry entry;
        PinnedLine pinned;

        static Row header(String text) {
            Row row = note(text);
            row.header = true;
            return row;
        }

        static Row note(String text) {
            Row row = new Row();
            row.text = text;
            row.height = 22;
            return row;
        }

        static Row device(Entry entry, boolean fresh) {
            Row row = new Row();
            row.entry = entry;
            row.fresh = fresh;
            row.height = entry.recipeIn.isEmpty() && entry.recipeOut.isEmpty() ? 46 : 59;
            return row;
        }

        static Row pinned(PinnedLine line, boolean fresh) {
            Row row = note(line.text);
            row.pinned = line;
            row.fresh = fresh;
            row.height = line.graph ? 86 : 22;
            return row;
        }
    }
}
