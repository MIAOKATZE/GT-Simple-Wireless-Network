package com.miaokatze.gtswn.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtswn.common.performance.PerformanceAudit;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalRequestQueue;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端→服务端 请求包：请求刷新 ME 网络量子终端的显示数据（discriminator = 5）。
 * <p>
 * 无字段：服务端直接从 {@code ctx.getServerHandler().playerEntity.getHeldItem()} 取玩家手持物品，
 * 校验为「已绑定的 ME 网络量子终端」后按终端 NBT 锚点装配 {@code QuantumNetworkData}，
 * 经 {@link PacketSyncQuantumTerminalData} 回发给请求方。
 * <p>
 * 发送方：{@code GuiQuantumTerminal.updateScreen()} 每 10 tick 轮询一次（规划 §6 客户端刷新模式）。
 * 手持校验失败（不是终端 / 未绑定）时静默丢弃不回包，客户端等下个周期重试。
 * <p>
 * v1.6.1 问题 4b：本 Handler 运行在 Netty 网络线程，不得直接读世界/装配网络数据
 * （既线程不安全，异常时又会永不回包使 GUI 停在「...」）。改为仅入队
 * {@link QuantumTerminalRequestQueue}，由 ServerTickEvent 在主线程 drain 装配回包。
 */
public class PacketRequestQuantumTerminalData implements IMessage {

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketRequestQuantumTerminalData() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        // 无字段（规划 §6：服务端取 ctx 玩家手持）
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // 无字段
    }

    /**
     * 服务端处理：仅入队，不在 Netty 线程装配。
     * <p>
     * v1.6.1 问题 4b 修复：onMessage 由 Netty 网络线程执行（1.7.10 无 ServerThreadUtil），
     * 线程切换采用「队列 + ServerTickEvent 排水」模式——此处只把玩家引用入队
     * {@link QuantumTerminalRequestQueue}，装配与回包由主线程 drain 完成。
     */
    public static class Handler implements IMessageHandler<PacketRequestQuantumTerminalData, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestQuantumTerminalData msg, MessageContext ctx) {
            // v1.6.19：性能审计——C→S 包计数（discriminator 5）
            if (PerformanceAudit.enabled()) PerformanceAudit.recordPacketReceived(5);
            // 1.7.10 API：经 ctx.getServerHandler().playerEntity 取得请求方玩家
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            QuantumTerminalRequestQueue.enqueue(player);
            return null;
        }
    }
}
