package com.miaokatze.gtswn.network;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtswn.common.performance.PerformanceAudit;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端→服务端 请求包：请求指定玩家 UUID 的无线电网 EU 余额。
 * <p>
 * 背景：GlobalEnergy 数据仅存在于服务端，客户端直接调用 {@code WirelessNetworkManager.getUserEU} 恒返 0。
 * 故由客户端（HUD 每 100 ticks，即 5 秒）发送此请求包，服务端查询后通过 {@link PacketResponseWirelessEU} 回包。
 * <p>
 * discriminator = 0（见 {@link GTSWNPacketHandler#register()}）。
 */
public class PacketRequestWirelessEU implements IMessage {

    /** 拥有者 UUID 字符串（来自便携监测终端 NBT 的 OwnerUUID 字段） */
    private String ownerUUID;

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketRequestWirelessEU() {}

    public PacketRequestWirelessEU(String ownerUUID) {
        this.ownerUUID = ownerUUID;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // 1.7.10 用 ByteBufUtils.readUTF8String 读写字符串
        ownerUUID = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, ownerUUID);
    }

    /**
     * 服务端处理：仅入队，不在 Netty 线程直读无线电网。
     * <p>
     * v1.6.19：查询切主线程执行——{@code WirelessNetworkManager} 是服务端主线程维护的
     * HashMap，Netty 网络线程直读存在并发风险。此处仅把 (玩家, UUID) 入队
     * {@link WirelessEURequestQueue}，由 ServerTickEvent（END phase）在主线程 drain 查询并回包。
     */
    public static class Handler implements IMessageHandler<PacketRequestWirelessEU, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestWirelessEU message, MessageContext ctx) {
            // v1.6.19：性能审计——C→S 包计数（discriminator 0）
            if (PerformanceAudit.enabled()) PerformanceAudit.recordPacketReceived(0);
            String uuidStr = message.ownerUUID;
            // 空 UUID 静默丢弃，客户端等下个周期重试
            if (uuidStr == null || uuidStr.isEmpty()) {
                return null;
            }

            // 解析 UUID；非法格式静默丢弃，避免服务端日志被刷屏
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                return null;
            }

            // 查询移交主线程：仅入队，由 ServerTickEvent 在主线程 drain 查询并回包
            WirelessEURequestQueue.enqueue((EntityPlayerMP) ctx.getServerHandler().playerEntity, uuid.toString());
            return null;
        }
    }
}
