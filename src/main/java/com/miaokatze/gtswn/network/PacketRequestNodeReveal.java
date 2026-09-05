package com.miaokatze.gtswn.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtswn.common.performance.PerformanceAudit;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端→服务端 请求包：手持 {@code WirelessEnergyTap} 蓄力显形请求（discriminator = 11）。
 * <p>
 * 无字段：服务端从 {@code ctx} 解析发送者，主线程 drain 时复验在线/存活 + 手持
 * WirelessEnergyTap + 冷却消费，随后以玩家当前位置为中心查询 {@code WirelessNodeRegistry}
 * 并经 {@link PacketSyncNodeReveal} 回发（空列表同样回发，客户端语义 = 清缓存）。
 * <p>
 * 线程模式与包 8（{@link PacketRequestDeviceTerminalData}）一致：Handler 运行在 Netty
 * 网络线程，不得触世界 / WorldSavedData，此处仅入队 {@link NodeRevealRequestQueue}，
 * 全部校验与查询由 ServerTickEvent（END）主线程 drain 完成。
 * 发送方：客户端蓄力释放交互（后续切片接入）。
 */
public class PacketRequestNodeReveal implements IMessage {

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketRequestNodeReveal() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        // 无字段（服务端取 ctx 玩家）
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // 无字段
    }

    /**
     * 服务端处理：仅入队，不在 Netty 线程装配。
     * <p>
     * 照 {@code PacketRequestDeviceTerminalData.Handler} 同款「队列 + ServerTickEvent 排水」
     * 线程切换模式：此处只把玩家引用入队 {@link NodeRevealRequestQueue}，手持校验 / 冷却消费 /
     * 注册表查询 / 模式过滤 / 回包全部由主线程 drain 完成。
     */
    public static class Handler implements IMessageHandler<PacketRequestNodeReveal, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestNodeReveal msg, MessageContext ctx) {
            // 性能审计——C→S 包计数（discriminator 11）
            if (PerformanceAudit.enabled()) PerformanceAudit.recordPacketReceived(11);
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player != null) {
                NodeRevealRequestQueue.enqueue(player);
            }
            return null;
        }
    }
}
