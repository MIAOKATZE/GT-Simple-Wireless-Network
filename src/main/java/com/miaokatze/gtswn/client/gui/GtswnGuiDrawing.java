package com.miaokatze.gtswn.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

/**
 * gtswn GUI 贴图绘制工具层（MC 1.7.10 直绘，无 GlStateManager，不依赖 Gui 重载/SRG 名）。
 * <p>
 * 全部经 {@link Tessellator} 手动指定 UV 四边形：先绑定贴图并复位顶点色
 * {@code GL11.glColor4f(1,1,1,1)}，UV 以 {@link GtswnGuiTextures} 的真实像素尺寸归一化。
 * <p>
 * blend 语义（契约 §4）：本层不改动混合开关——不透明贴图直绘即可；
 * {@link #drawStretch} 绘制半透明 HUD 底时由调用方负责开/恢复 {@code GL_BLEND}。
 * <p>
 * 契约出处：plan/ui/texture-list.md §4「Java 消费层 API 契约」之 GtswnGuiDrawing。
 */
public final class GtswnGuiDrawing {

    private GtswnGuiDrawing() {}

    /** 绑定 gtswn GUI 贴图（经 TextureManager，等价原版 GuiButton 的 bindTexture 用法） */
    public static void bind(ResourceLocation rl) {
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(rl);
    }

    /**
     * 从贴图 (u,v) 起 1:1 绘制 w×h 像素区域到屏幕 (x,y)，深度用传参 z。
     * 等价原版 drawTexturedModalRect，但 UV 按 gtswn 贴图真实尺寸归一化而非假定 256。
     */
    public static void drawRegion(ResourceLocation rl, int x, int y, int u, int v, int w, int h, float z) {
        bind(rl);
        final int tw = texW(rl);
        final int th = texH(rl);
        quad(x, y, w, h, (float) u / tw, (float) v / th, (float) (u + w) / tw, (float) (v + h) / th, z);
    }

    /**
     * 9-slice 绘制：slice 为角区边长（像素）。四角 1:1、四边单向拉伸、芯区双向拉伸，
     * 目标尺寸 w×h，源为整张贴图（按钮/标签/chip 切片 4px，滚动条纵向 2px，row_hover 横向 4px）。
     * 契约 §3 每张 9-slice 贴图均保证 2×slice ≤ 贴图边长，调用方保证 2×slice ≤ w、h。
     */
    public static void drawNineSlice(ResourceLocation rl, int slice, int x, int y, int w, int h, float z) {
        bind(rl);
        final int tw = texW(rl);
        final int th = texH(rl);
        // 源：角区 slice×slice，边/芯取中间段
        final float su0 = (float) slice / tw;
        final float su1 = (float) (tw - slice) / tw;
        final float sv0 = (float) slice / th;
        final float sv1 = (float) (th - slice) / th;
        // 目标：中间段宽高（小于 2×slice 时 dw/dh 为负会画翻转四边形，直接不画）
        final int dw = w - 2 * slice;
        final int dh = h - 2 * slice;
        if (dw < 0 || dh < 0) {
            return;
        }
        final int rx = x + w - slice;
        final int by = y + h - slice;
        // 三行 × 三列：角 1:1，边/芯拉伸
        quad(x, y, slice, slice, 0f, 0f, su0, sv0, z); // 左上角
        quad(x + slice, y, dw, slice, su0, 0f, su1, sv0, z); // 上边
        quad(rx, y, slice, slice, su1, 0f, 1f, sv0, z); // 右上角
        quad(x, y + slice, slice, dh, 0f, sv0, su0, sv1, z); // 左边
        quad(x + slice, y + slice, dw, dh, su0, sv0, su1, sv1, z); // 芯
        quad(rx, y + slice, slice, dh, su1, sv0, 1f, sv1, z); // 右边
        quad(x, by, slice, slice, 0f, sv1, su0, 1f, z); // 左下角
        quad(x + slice, by, dw, slice, su0, sv1, su1, 1f, z); // 下边
        quad(rx, by, slice, slice, su1, sv1, 1f, 1f, z); // 右下角
    }

    /**
     * 整图拉伸绘制到 (x,y,w,h)：HUD 半透明黑底等整图铺放用。
     * blend 不在本层管理，由调用方负责开/恢复。
     */
    public static void drawStretch(ResourceLocation rl, int x, int y, int w, int h, float z) {
        bind(rl);
        quad(x, y, w, h, 0f, 0f, 1f, 1f, z);
    }

    /**
     * 单个 UV 四边形（Tessellator 直绘，顶点顺序与原版 drawTexturedModalRect 一致）。
     * u/v 为已按贴图尺寸归一化的 0..1 坐标；先复位顶点色，保证不受前次 glColor 残留影响。
     */
    private static void quad(double x, double y, double w, double h, float u0, float v0, float u1, float v1, float z) {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        final Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(x, y + h, z, u0, v1);
        tessellator.addVertexWithUV(x + w, y + h, z, u1, v1);
        tessellator.addVertexWithUV(x + w, y, z, u1, v0);
        tessellator.addVertexWithUV(x, y, z, u0, v0);
        tessellator.draw();
    }

    /** 贴图真实像素宽（契约 §3 唯一来源 GtswnGuiTextures；未知贴图回退 256 即原版默认分辨率，避免除零） */
    private static int texW(ResourceLocation rl) {
        if (GtswnGuiTextures.PANEL_DEVICE_INFO.equals(rl)) return GtswnGuiTextures.PANEL_DEVICE_INFO_W;
        if (GtswnGuiTextures.PANEL_QUANTUM.equals(rl)) return GtswnGuiTextures.PANEL_QUANTUM_W;
        if (GtswnGuiTextures.PANEL_NETWORK_INFO.equals(rl)) return GtswnGuiTextures.PANEL_NETWORK_INFO_W;
        if (GtswnGuiTextures.BUTTON_NORMAL.equals(rl)) return GtswnGuiTextures.BUTTON_NORMAL_W;
        if (GtswnGuiTextures.BUTTON_HOVER.equals(rl)) return GtswnGuiTextures.BUTTON_HOVER_W;
        if (GtswnGuiTextures.BUTTON_DISABLED.equals(rl)) return GtswnGuiTextures.BUTTON_DISABLED_W;
        if (GtswnGuiTextures.TAB_ACTIVE.equals(rl)) return GtswnGuiTextures.TAB_ACTIVE_W;
        if (GtswnGuiTextures.TAB_INACTIVE.equals(rl)) return GtswnGuiTextures.TAB_INACTIVE_W;
        if (GtswnGuiTextures.CHIP_NORMAL.equals(rl)) return GtswnGuiTextures.CHIP_NORMAL_W;
        if (GtswnGuiTextures.CHIP_ACTIVE.equals(rl)) return GtswnGuiTextures.CHIP_ACTIVE_W;
        if (GtswnGuiTextures.SCROLLBAR_TRACK.equals(rl)) return GtswnGuiTextures.SCROLLBAR_TRACK_W;
        if (GtswnGuiTextures.SCROLLBAR_THUMB.equals(rl)) return GtswnGuiTextures.SCROLLBAR_THUMB_W;
        if (GtswnGuiTextures.ROW_HOVER.equals(rl)) return GtswnGuiTextures.ROW_HOVER_W;
        if (GtswnGuiTextures.HUD_BASE.equals(rl)) return GtswnGuiTextures.HUD_BASE_W;
        if (GtswnGuiTextures.LIST_PANEL.equals(rl)) return GtswnGuiTextures.LIST_PANEL_W;
        return 256;
    }

    /** 贴图真实像素高（来源与回退语义同 {@link #texW}） */
    private static int texH(ResourceLocation rl) {
        if (GtswnGuiTextures.PANEL_DEVICE_INFO.equals(rl)) return GtswnGuiTextures.PANEL_DEVICE_INFO_H;
        if (GtswnGuiTextures.PANEL_QUANTUM.equals(rl)) return GtswnGuiTextures.PANEL_QUANTUM_H;
        if (GtswnGuiTextures.PANEL_NETWORK_INFO.equals(rl)) return GtswnGuiTextures.PANEL_NETWORK_INFO_H;
        if (GtswnGuiTextures.BUTTON_NORMAL.equals(rl)) return GtswnGuiTextures.BUTTON_NORMAL_H;
        if (GtswnGuiTextures.BUTTON_HOVER.equals(rl)) return GtswnGuiTextures.BUTTON_HOVER_H;
        if (GtswnGuiTextures.BUTTON_DISABLED.equals(rl)) return GtswnGuiTextures.BUTTON_DISABLED_H;
        if (GtswnGuiTextures.TAB_ACTIVE.equals(rl)) return GtswnGuiTextures.TAB_ACTIVE_H;
        if (GtswnGuiTextures.TAB_INACTIVE.equals(rl)) return GtswnGuiTextures.TAB_INACTIVE_H;
        if (GtswnGuiTextures.CHIP_NORMAL.equals(rl)) return GtswnGuiTextures.CHIP_NORMAL_H;
        if (GtswnGuiTextures.CHIP_ACTIVE.equals(rl)) return GtswnGuiTextures.CHIP_ACTIVE_H;
        if (GtswnGuiTextures.SCROLLBAR_TRACK.equals(rl)) return GtswnGuiTextures.SCROLLBAR_TRACK_H;
        if (GtswnGuiTextures.SCROLLBAR_THUMB.equals(rl)) return GtswnGuiTextures.SCROLLBAR_THUMB_H;
        if (GtswnGuiTextures.ROW_HOVER.equals(rl)) return GtswnGuiTextures.ROW_HOVER_H;
        if (GtswnGuiTextures.HUD_BASE.equals(rl)) return GtswnGuiTextures.HUD_BASE_H;
        if (GtswnGuiTextures.LIST_PANEL.equals(rl)) return GtswnGuiTextures.LIST_PANEL_H;
        return 256;
    }
}
