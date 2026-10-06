package com.miaokatze.gtswn.client;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.MouseEvent;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketAltDismantleMarker;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.util.GTUtility;

/**
 * Alt+扳手左键拆机标记触发处理器（v1.8.22）。
 * <p>
 * 监听 Forge 客户端鼠标事件 {@link MouseEvent}（发布机制同 {@link RevealTriggerHandler}）：
 * 手持任意 GT 扳手（{@code GregTechAPI.sWrenchList}）时按住 Alt + 左键按下沿 →
 * 立即向服务端发送 {@link PacketAltDismantleMarker}（disc 14），服务端为该玩家写入
 * 20t TTL 的拆机标记；随后的 {@code BlockEvent.BreakEvent}（服务端覆盖板拆机事件
 * 处理器）命中标记即静默清除被拆机器上的全部链路节点覆盖板。
 * <p>
 * 【与 RevealTriggerHandler 的关键差异：不取消事件】Alt+右键显形会取消事件
 * （抑制原版右键，防误开 GUI）；本处<b>绝不取消</b>——左键是挖掘的开始，取消会把
 * 挖掘输入一并吞掉（runTick 直接 continue，本次点击不再进入 PlayerControllerMP 的
 * dig 流程），Alt 拆机将永远走不到 BreakEvent。标记包只携带修饰键意图，挖掘本身
 * 必须照常继续，由服务端在 BreakEvent 里按标记分流。
 * <p>
 * 【类加载安全】本类仅在 {@code ClientProxy#init} 注册（client-only），服务端不会加载，
 * 可安全引用 {@code Minecraft} / {@code org.lwjgl.input.Keyboard} 等客户端类。
 * GUI 打开时原版本就不向本总线发布鼠标事件，{@code currentScreen == null}
 * 守卫为对 {@code allowUserInput} 类屏幕的额外保险。
 */
public class LinkNodeDismantleTriggerHandler {

    /**
     * Alt+左键拆机标记触发。
     * <p>
     * 命中条件全部满足才触发：左键（{@code button == 0}）按下沿（{@code buttonstate == true}）、
     * 左/右 Alt 按住（键码 56/184）、无 GUI 打开、玩家手持 GT 扳手。
     */
    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        // 仅左键按下沿
        if (event.button != 0 || !event.buttonstate) {
            return;
        }
        // Alt 按住（左 Alt=56 / 右 Alt=184 任一）
        if (!Keyboard.isKeyDown(Keyboard.KEY_LMENU) && !Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        // 无 GUI 守卫（防 GUI 内误触）
        if (mc.currentScreen != null) {
            return;
        }
        // 仅手持 GT 扳手时触发（服务端 BreakEvent 消费侧同款判定）
        ItemStack held = (mc.thePlayer == null) ? null : mc.thePlayer.inventory.getCurrentItem();
        if (!GTUtility.isStackInList(held, GregTechAPI.sWrenchList)) {
            return;
        }
        // 发送 Alt 拆机标记（disc 14）；故意不取消事件——挖掘必须继续，
        // 见类 javadoc【与 RevealTriggerHandler 的关键差异】
        GTSWNPacketHandler.NETWORK.sendToServer(new PacketAltDismantleMarker());
    }
}
