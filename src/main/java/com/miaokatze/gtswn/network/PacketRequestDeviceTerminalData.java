package com.miaokatze.gtswn.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtswn.common.device.DeviceTerminalRequestQueue;
import com.miaokatze.gtswn.common.performance.PerformanceAudit;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端→服务端 请求包：请求设备信息终端的绑定机器数据（discriminator = 8，阶段 D1）。
 * <p>
 * 无字段：服务端从发送者物品栏解析首台设备信息终端（手持优先）→ terminalId →
 * 版本校验（已收版本 == 当前版本则跳过重发）→ 经 {@link PacketSyncDeviceTerminalData}
 * 分页回发。无终端持有（GUI 关闭后残留轮询）时静默丢弃不回包。
 * <p>
 * 线程模式与包 5（v1.6.1 问题 4b）一致：Handler 运行在 Netty 网络线程，不得读世界 /
 * WorldSavedData，此处仅入队 {@link DeviceTerminalRequestQueue}，装配回包由
 * {@code DeviceSampleScheduler} 的 ServerTickEvent（END）主线程 drain 完成。
 * 发送方：{@code GuiDeviceInfoTerminal.updateScreen()} 周期轮询（阶段 E）。
 */
public class PacketRequestDeviceTerminalData implements IMessage {

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketRequestDeviceTerminalData() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        // 无字段（服务端取 ctx 玩家物品栏）
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // 无字段
    }

    /**
     * 服务端处理：仅入队，不在 Netty 线程装配。
     * <p>
     * 与 {@code PacketRequestQuantumTerminalData.Handler} 同款「队列 + ServerTickEvent 排水」
     * 线程切换模式：此处只把玩家引用入队 {@link DeviceTerminalRequestQueue}，
     * 版本跳过判定与分页回包由主线程 drain 完成（D5 防轮询压力）。
     */
    public static class Handler implements IMessageHandler<PacketRequestDeviceTerminalData, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestDeviceTerminalData msg, MessageContext ctx) {
            // 性能审计——C→S 包计数（discriminator 8）
            if (PerformanceAudit.enabled()) PerformanceAudit.recordPacketReceived(8);
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player != null) {
                DeviceTerminalRequestQueue.enqueue(player);
            }
            return null;
        }
    }
}
