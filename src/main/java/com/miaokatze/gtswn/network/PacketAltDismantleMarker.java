package com.miaokatze.gtswn.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtswn.common.performance.PerformanceAudit;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端→服务端 请求包：Alt+扳手左键拆机标记（discriminator = 14，v1.8.22）。
 * <p>
 * 无字段：服务端从 {@code ctx} 解析发送者，主线程 drain 时为其登记一条短 TTL 的
 * 「Alt 拆机标记」，供服务端 {@code BlockEvent.BreakEvent} 消费方（覆盖板拆机事件
 * 处理器）区分「Alt+扳手拆除 = 静默清除全部链路节点覆盖板」与普通扳手拆除（提示路径）。
 * 挖掘由客户端照常发起，本包只携带修饰键意图。
 * <p>
 * 线程模式与包 11（{@link PacketRequestNodeReveal}）一致：Handler 运行在 Netty
 * 网络线程，不得触世界 / 注册表，此处仅入队 {@link LinkNodeDismantleMarkerQueue}，
 * Map 写入由 ServerTickEvent（END）主线程 drain 完成。方法体零客户端类引用
 * （SideTransformer 安全）。
 * 发送方：客户端 Alt+扳手左键按下沿触发器（仅 ClientProxy 注册，MouseEvent 处理器）。
 */
public class PacketAltDismantleMarker implements IMessage {

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketAltDismantleMarker() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        // 无字段（服务端取 ctx 玩家）
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // 无字段
    }

    /**
     * 服务端处理：仅入队，不在 Netty 线程写 Map。
     * <p>
     * 照 {@code PacketRequestNodeReveal.Handler} 同款「队列 + ServerTickEvent 排水」
     * 线程切换模式：此处只把玩家引用入队 {@link LinkNodeDismantleMarkerQueue}，
     * TTL 时间基准（overworld 世界 tick）读取与 Map 写入全部由主线程 drain 完成。
     */
    public static class Handler implements IMessageHandler<PacketAltDismantleMarker, IMessage> {

        @Override
        public IMessage onMessage(PacketAltDismantleMarker msg, MessageContext ctx) {
            // 性能审计——C→S 包计数（discriminator 14）
            if (PerformanceAudit.enabled()) PerformanceAudit.recordPacketReceived(14);
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player != null) {
                LinkNodeDismantleMarkerQueue.enqueue(player);
            }
            return null;
        }
    }
}
