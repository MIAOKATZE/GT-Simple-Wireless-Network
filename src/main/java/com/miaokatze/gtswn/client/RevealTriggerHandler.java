package com.miaokatze.gtswn.client;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.MouseEvent;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestNodeReveal;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 节点显形触发处理器（v1.7.23）。
 * <p>
 * 监听 Forge 客户端鼠标事件 {@link MouseEvent}（1.7.10 中由
 * {@code ForgeHooksClient.postMouseEvent()} 在原版 {@code Minecraft.runTick} 鼠标循环内
 * 发布到 {@code MinecraftForge.EVENT_BUS}）：手持 {@link WirelessEnergyTap} 时按住
 * Alt + 右键按下沿 → 立即向服务端发送 {@link PacketRequestNodeReveal}（disc 11），
 * 并 {@code setCanceled(true)} 抑制本次原版右键处理（取消后 runTick 直接
 * {@code continue}，跳过 KeyBinding/右键机器 GUI/放置全流程，防止 Alt+右击误操作）。
 * <p>
 * 替代 v1.7.20~v1.7.22 的「右击空气蓄力 2.5 秒松手」路径（蓄力机制已整体删除）。
 * <p>
 * 【类加载安全】本类仅在 {@code ClientProxy#init} 注册（client-only），服务端不会加载，
 * 可安全引用 {@code Minecraft} / {@code org.lwjgl.input.Keyboard} 等客户端类。
 * GUI 打开时原版本就不向本总线发布鼠标事件（runTick 中
 * {@code currentScreen == null || allowUserInput} 门控），{@code currentScreen == null}
 * 守卫为对 {@code allowUserInput} 类屏幕的额外保险。
 */
public class RevealTriggerHandler {

    /**
     * Alt+右键即时扫描触发。
     * <p>
     * 命中条件全部满足才触发：右键（{@code button == 1}）按下沿（{@code buttonstate == true}）、
     * 左/右 Alt 按住、无 GUI 打开、玩家手持 {@link WirelessEnergyTap}。
     */
    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        // 仅右键按下沿
        if (event.button != 1 || !event.buttonstate) {
            return;
        }
        // Alt 按住（左/右 Alt 任一）
        if (!Keyboard.isKeyDown(Keyboard.KEY_LMENU) && !Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        // 无 GUI 守卫（防 GUI 内误触）
        if (mc.currentScreen != null) {
            return;
        }
        // 仅手持链路终端时触发
        ItemStack held = (mc.thePlayer == null) ? null : mc.thePlayer.inventory.getCurrentItem();
        if (held == null || !(held.getItem() instanceof WirelessEnergyTap)) {
            return;
        }
        GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestNodeReveal());
        // 抑制本次原版右键（防 Alt+右击误开机器 GUI/放置）
        event.setCanceled(true);
    }
}
