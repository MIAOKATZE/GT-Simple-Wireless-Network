package com.miaokatze.gtswn.common.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.gui.GtswnGuiDrawing;
import com.miaokatze.gtswn.client.gui.GtswnGuiTextures;
import com.miaokatze.gtswn.config.Config;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 便携式无线网络监测终端 HUD 渲染器
 * <p>
 * 当监测终端在玩家背包内时，在饱食度上方显示无线电网能量值。
 * 使用 Forge 事件系统监听游戏渲染事件，在适当的位置绘制 HUD 文本。
 * <p>
 * O2-B10 / O2-B03-6：渲染方法只留 ⑤ GL 绘制段；原 ①世界切换状态机 / ②背包复合扫描 /
 * ③开关与 gap 检测 / ④数据集更新四段业务与全部可变态迁 {@link HudController}/{@link HudState}
 * （经构造注入共享同一实例，唯一实例持有点 = ClientProxy 注册处）。
 */
public class WirelessMonitorHUD extends Gui {

    private final HudController controller;

    public WirelessMonitorHUD(HudController controller) {
        this.controller = controller;
    }

    /**
     * 渲染游戏覆盖层事件处理器
     * 在饱食度上方绘制无线电网能量信息
     */
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        // 仅在绘制所有元素后执行
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        // 确保游戏正常运行且有玩家
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        EntityPlayer player = mc.thePlayer;

        // ①-④ 业务段（世界切换/背包扫描/开关与 gap/数据集更新）在控制器内驱动；
        // 返回 false 表示本帧不渲染
        if (!this.controller.updateForFrame(player)) {
            return;
        }

        // 计算 HUD 位置（饱食度上方）
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int screenWidth = resolution.getScaledWidth();
        int screenHeight = resolution.getScaledHeight();

        // 饱食度图标位置：x = screenWidth / 2 + 91, y = screenHeight - 39
        // HUD 显示在饱食度上方 15 像素处
        // 应用配置的偏移：X 正=右（直接加），Y 正=上（减去偏移以反转屏幕坐标——屏幕 Y 向下为正）
        // Apply configured offsets: X positive = right (add directly);
        // Y positive = up (subtract to invert screen coords — screen Y points downward)
        int hudX = screenWidth / 2 + 91 + Config.hudXOffset;
        int hudY = screenHeight - 54 - Config.hudYOffset;

        // 保存 OpenGL 状态
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();

        // 应用缩放变换：以 (hudX, hudY) 为缩放原点，避免缩放后 HUD 位置漂移。
        // Apply scale transform: use (hudX, hudY) as scale origin to prevent HUD drift after scaling.
        // 流程：先平移到原点 → 缩放 → 平移回原位置，使 HUD 基准点保持不变。
        // Pipeline: translate to origin → scale → translate back, keeping HUD base point fixed.
        float scale = Config.hudScale;
        if (scale != 1.0f) {
            GL11.glTranslatef(hudX, hudY, 0);
            GL11.glScalef(scale, scale, 1.0f);
            GL11.glTranslatef(-hudX, -hudY, 0);
        }

        // 禁用深度测试和光照，确保 HUD 始终在最上层
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);

        // 自下向上紧凑排列；充电行位于原有三行上方。
        String[] lines = { Config.hudEUEnabled ? this.controller.euText() : null,
            Config.hudAverageEnabled ? this.controller.eutText() : null,
            Config.hudInstantEnabled ? this.controller.realtimeEutText() : null,
            Config.hudChargeEnabled && this.controller.hasBattery() ? this.controller.chargeText() : null };
        int rowY = hudY;
        int textWidth = 0;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            textWidth = Math.max(textWidth, mc.fontRenderer.getStringWidth(line));
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GtswnGuiDrawing.drawStretch(GtswnGuiTextures.HUD_BASE, hudX - 2, rowY - 2, textWidth + 4, 12, 0);
            mc.fontRenderer.drawStringWithShadow(line, hudX, rowY, 0xFFFFFF);
            rowY -= 12;
        }

        GL11.glDisable(GL11.GL_BLEND);

        // 恢复 OpenGL 状态
        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }
}
