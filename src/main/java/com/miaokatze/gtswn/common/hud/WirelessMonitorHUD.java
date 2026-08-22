package com.miaokatze.gtswn.common.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

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

        String euText = this.controller.euText();

        // 获取文本宽度
        int textWidth = mc.fontRenderer.getStringWidth(euText);

        // 绘制半透明背景
        drawRect(hudX - 2, hudY - 2, hudX + textWidth + 2, hudY + 10, 0x80000000);

        // 绘制文本（使用格式化字符串，带颜色代码）
        mc.fontRenderer.drawStringWithShadow(euText, hudX, hudY, 0xFFFFFF);

        // 绘制 EU/t 信息（在上方一行）
        int eutY = hudY - 12;
        int eutTextWidth = mc.fontRenderer.getStringWidth(this.controller.eutText());
        drawRect(hudX - 2, eutY - 2, hudX + Math.max(textWidth, eutTextWidth) + 2, eutY + 10, 0x80000000);
        mc.fontRenderer.drawStringWithShadow(this.controller.eutText(), hudX, eutY, 0xFFFFFF);

        int realtimeY = hudY - 24;
        int realtimeTextWidth = mc.fontRenderer.getStringWidth(this.controller.realtimeEutText());
        drawRect(
            hudX - 2,
            realtimeY - 2,
            hudX + Math.max(Math.max(textWidth, eutTextWidth), realtimeTextWidth) + 2,
            realtimeY + 10,
            0x80000000);
        mc.fontRenderer.drawStringWithShadow(this.controller.realtimeEutText(), hudX, realtimeY, 0xFFFFFF);

        // 恢复 OpenGL 状态
        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }
}
