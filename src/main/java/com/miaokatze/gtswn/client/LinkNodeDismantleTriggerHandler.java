package com.miaokatze.gtswn.client;

import net.minecraft.client.Minecraft;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketAltDismantleMarker;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.util.GTUtility;

/**
 * Alt+扳手左键拆机标记触发处理器（v1.8.23，持续标记版）。
 * <p>
 * v1.8.22 的鼠标左键按下沿监听方案实机失效：精确模式扳手拆机是即时破坏，
 * 同一 tick 内 {@code BlockEvent.BreakEvent} 早于 {@code ServerTickEvent(END)} 的标记
 * drain 写入，{@code isMarked} 恒为 false；且 Forge 客户端鼠标事件对左键的发布行为
 * 无实证。
 * v1.8.23 改为<b>持续标记</b>：监听 FML 总线 {@link TickEvent.ClientTickEvent}（1.7.10
 * 的 Tick 事件挂 {@code FMLCommonHandler.instance().bus()}，不在 Forge 总线），按住
 * Alt + 手持 GT 扳手（{@code GregTechAPI.sWrenchList}）期间每 5 client tick 向服务端
 * 重发 {@link PacketAltDismantleMarker}（disc 14），服务端为该玩家维持 20t TTL 的拆机
 * 标记；随后任何 tick 触发的 BreakEvent 命中标记即静默清除被拆机器上的全部链路节点
 * 覆盖板。本处理器只发包不碰任何事件——左键挖掘照常进行，由服务端按标记分流。
 * <p>
 * 【持续标记的两个设计动机】
 * <ol>
 * <li><b>消除同 tick 竞态</b>：标记在玩家按下左键<b>之前</b>就已持续就位并每 5t 刷新、
 * 服务端 TTL 20t——无论扳手即时破坏发生在按下后的哪个 tick，{@code isMarked} 必然命中，
 * 标记写入与破坏事件不再有次序耦合。</li>
 * <li><b>摆脱鼠标事件依赖</b>：ClientTickEvent 在 FML 总线每 tick 稳定发布，
 * 不依赖 Forge 对鼠标左键的发布路径与时机。</li>
 * </ol>
 * <p>
 * 【Alt 释放后的残留窗口】服务端 TTL 20t 意味着松开 Alt 后最多 1 秒标记仍有效：此期间
 * 无 Alt 的扳手拆机也会走静默清除路径（节点出册 + 缓冲 EU 回网）。视为快速连续拆除
 * 工作流的可接受行为——连拆多台机器本就保持 Alt 按住，松手即收工，残留 1 秒内误拆的
 * 代价（EU 回网可重挂、覆盖板可重新附着）很低。
 * <p>
 * 【类加载安全】本类仅在 {@code ClientProxy#init} 注册（client-only），服务端不会加载，
 * 可安全引用 {@code Minecraft} / {@code org.lwjgl.input.Keyboard} 等客户端类。
 * <p>
 * 【无 GUI 守卫含义】与鼠标事件版本的差异：tick 事件没有原版输入门控，GUI 打开时
 * 照常发布，故 {@code currentScreen == null} 守卫从「额外保险」升格为唯一防线——任何
 * GUI（含 {@code allowUserInput} 类屏幕）打开期间不判定、不发包。
 */
public class LinkNodeDismantleTriggerHandler {

    /** 持续标记重发间隔（client tick）：服务端 TTL 20t 的 1/4，留足刷新裕量 */
    private static final int SEND_INTERVAL_TICKS = 5;

    /**
     * 距上次发包的 client tick 计数。节流基准选本地 int 计数器：每 END phase 自增、
     * 发包清零、封顶 {@link #SEND_INTERVAL_TICKS}——Alt 未按住时计数停在顶值，再次
     * 按住的第一 tick 即发包，无需等待预热。
     */
    private int ticksSinceLastSend = SEND_INTERVAL_TICKS;

    /**
     * ClientTickEvent(END) 持续标记：Alt 按住 + 无 GUI + 手持 GT 扳手期间，每
     * {@link #SEND_INTERVAL_TICKS} tick 重发一次标记包（不取消任何事件，挖掘照常）。
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 节流计数（封顶自增：见字段 javadoc）
        if (ticksSinceLastSend < SEND_INTERVAL_TICKS) {
            ticksSinceLastSend++;
        }
        Minecraft mc = Minecraft.getMinecraft();
        // 无玩家/无 GUI 守卫：主菜单与任何打开的 GUI 期间不判定（见类 javadoc【无 GUI 守卫含义】）
        if (mc.thePlayer == null || mc.currentScreen != null) {
            return;
        }
        // Alt 按住（左 Alt=56 / 右 Alt=184 任一）
        if (!Keyboard.isKeyDown(Keyboard.KEY_LMENU) && !Keyboard.isKeyDown(Keyboard.KEY_RMENU)) {
            return;
        }
        // 仅手持 GT 扳手时触发（服务端 BreakEvent 消费侧同款判定）
        if (!GTUtility.isStackInList(mc.thePlayer.inventory.getCurrentItem(), GregTechAPI.sWrenchList)) {
            return;
        }
        // 节流：距上次发送 ≥5 client tick 才重发（持续标记的刷新频率）
        if (ticksSinceLastSend < SEND_INTERVAL_TICKS) {
            return;
        }
        ticksSinceLastSend = 0;
        // 发送 Alt 拆机标记（disc 14）：只携带修饰键意图，左键挖掘照常进行
        GTSWNPacketHandler.NETWORK.sendToServer(new PacketAltDismantleMarker());
    }
}
