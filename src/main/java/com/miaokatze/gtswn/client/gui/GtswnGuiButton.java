package com.miaokatze.gtswn.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.GL11;

/**
 * gtswn 工业琥珀风格按钮：9-slice 三态贴图（normal/hover/disabled，切片 4px）+ 原版标签逻辑。
 * <p>
 * 几何与交互语义与原版 GuiButton 完全一致（id/x/y/w/h、hover 判定、mousePressed 不变），
 * 仅将 widgets.png 三段贴图替换为 {@link GtswnGuiTextures} 的 button_* 三态 9-slice，
 * 并按契约 §4 复刻原版标签三色：hover 0xFFFFA0、normal 0xE0E0E0（均带阴影）、
 * 禁用 0xA0A0A0（无阴影）。
 * <p>
 * 契约出处：plan/ui/texture-list.md §3 #4-#6、§4 之 GtswnGuiButton。
 */
public class GtswnGuiButton extends GuiButton {

    /** 三态贴图 9-slice 角区边长（契约 §3：按钮切片 4px） */
    private static final int SLICE = 4;

    /**
     * 本类自维护的 hover 态（逐帧由 drawButton 按原版算法重算）。
     * 原版 GuiButton 的对应字段在 RFG/GTNH 编译映射下仍为 SRG 名（field_146123_n），
     * 无法以 MCP 名 {@code hovered} 引用，故在此自声明，语义与原版一致。
     */
    private boolean hovered;

    public GtswnGuiButton(int id, int x, int y, int w, int h, String label) {
        super(id, x, y, w, h, label);
    }

    /**
     * 三态贴图 + 原版标签绘制（1.7.10 签名，无 partialTicks）。
     * 优先级与原版 getHoverState 一致：禁用态压过 hover 态。
     */
    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        // hover 判定复刻原版 GuiButton（字段 hovered 在 stable_12 映射中即此用途）
        this.hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width
            && mouseY < this.yPosition + this.height;

        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (!this.enabled) {
            GtswnGuiDrawing.drawNineSlice(
                GtswnGuiTextures.BUTTON_DISABLED,
                SLICE,
                this.xPosition,
                this.yPosition,
                this.width,
                this.height,
                this.zLevel);
        } else if (this.hovered) {
            GtswnGuiDrawing.drawNineSlice(
                GtswnGuiTextures.BUTTON_HOVER,
                SLICE,
                this.xPosition,
                this.yPosition,
                this.width,
                this.height,
                this.zLevel);
        } else {
            GtswnGuiDrawing.drawNineSlice(
                GtswnGuiTextures.BUTTON_NORMAL,
                SLICE,
                this.xPosition,
                this.yPosition,
                this.width,
                this.height,
                this.zLevel);
        }
        this.mouseDragged(mc, mouseX, mouseY);

        // 标签：水平/垂直居中（y 取 (h-8)/2 与原版 drawCenteredString 落点一致），三色复刻原版
        final FontRenderer fontrenderer = mc.fontRenderer;
        final int tx = this.xPosition + this.width / 2 - fontrenderer.getStringWidth(this.displayString) / 2;
        final int ty = this.yPosition + (this.height - 8) / 2;
        if (!this.enabled) {
            fontrenderer.drawString(this.displayString, tx, ty, 0xA0A0A0);
        } else if (this.hovered) {
            fontrenderer.drawStringWithShadow(this.displayString, tx, ty, 0xFFFFA0);
        } else {
            fontrenderer.drawStringWithShadow(this.displayString, tx, ty, 0xE0E0E0);
        }
    }
}
