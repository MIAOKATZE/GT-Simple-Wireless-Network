package com.miaokatze.gtswn.common.quantum;

import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketSyncQuantumTerminalData;

/**
 * 量子终端数据请求的待处理队列（v1.6.1 问题 4b）。
 * <p>
 * SimpleNetworkWrapper 的 Handler 运行在 Netty 网络线程，不能直接读世界装配
 * {@link QuantumNetworkData}。Handler 仅入队 (player)，由
 * {@code QuantumControllerEventHandler} 的 ServerTickEvent（END phase）在主线程
 * 逐条 drain：装配快照 → 回发同步包；装配异常时回发离线快照兜底，保证 GUI 不卡在「...」。
 * <p>
 * 1.7.10 无 ServerThreadUtil / MinecraftServer.addScheduledTask（1.8+ 才有），
 * 故采用「队列 + ServerTickEvent 排水」模式实现网络线程 → 主线程的切换。
 */
public final class QuantumTerminalRequestQueue {

    /** 待处理请求队列：仅缓存玩家引用，主线程 drain 时再校验在线/手持 */
    private static final ConcurrentLinkedQueue<EntityPlayerMP> PENDING = new ConcurrentLinkedQueue<>();

    private QuantumTerminalRequestQueue() {}

    /** Netty 线程入队（仅缓存玩家引用，主线程 drain 时再校验在线/手持） */
    public static void enqueue(EntityPlayerMP player) {
        if (player != null) {
            PENDING.add(player);
        }
    }

    /** 主线程逐条处理；本 tick 内排空当前快照 */
    public static void drain() {
        EntityPlayerMP player;
        while ((player = PENDING.poll()) != null) {
            process(player);
        }
    }

    /** 主线程执行：校验玩家仍在线，再装配/回发；任何异常都回发离线快照兜底 */
    private static void process(EntityPlayerMP player) {
        GTSimpleWirelessNetwork.LOG.info("[量子终端][队列] 开始处理玩家请求");
        try {
            if (player.playerNetServerHandler == null) {
                // 已掉线：静默丢弃
                return;
            }
            ItemStack held = player.getHeldItem();
            QuantumNetworkData data = QuantumNetworkData.assemble(player, held);
            if (data == null) {
                // 手持不是已绑定量子终端 → 回发全零离线快照，保证 GUI 不卡在「...」
                data = QuantumNetworkData.offlineFromStack(held);
                if (data == null) {
                    // 空手或非绑定终端：回发一个全零快照（online=false，无锚点坐标）
                    data = new QuantumNetworkData();
                }
            }
            GTSimpleWirelessNetwork.LOG.info("[量子终端][队列] 装配返回 online=" + data.online + " 准备回发包6");
            // v1.6.2 诊断躍点 3/4【回发同步包】：确认包 6 已发出及其在线语义（v1.6.5 起保留 3/4 与 4/4 闭环日志）
            GTSimpleWirelessNetwork.LOG.info(
                "[量子终端][3/4 回发同步包] online=" + data.online
                    + "，频道="
                    + data.usedChannels
                    + "/"
                    + data.totalChannels
                    + "，设备数="
                    + data.totalMachines);
            GTSWNPacketHandler.NETWORK.sendTo(new PacketSyncQuantumTerminalData(data), player);
        } catch (Throwable t) {
            // 装配读世界/网格可能抛异常（网格解体、区块竞争等）：回发离线快照兜底，保证 GUI 不卡在「...」
            GTSimpleWirelessNetwork.LOG.error("[量子终端] 装配网络数据异常，回发离线快照", t);
            try {
                QuantumNetworkData fallback = QuantumNetworkData.offlineFromStack(player.getHeldItem());
                if (fallback == null) {
                    fallback = new QuantumNetworkData();
                }
                GTSWNPacketHandler.NETWORK.sendTo(new PacketSyncQuantumTerminalData(fallback), player);
            } catch (Throwable ignored) {
                // 兜底回发也失败（玩家掉线等）：放弃，客户端等下轮轮询
            }
        }
    }
}
