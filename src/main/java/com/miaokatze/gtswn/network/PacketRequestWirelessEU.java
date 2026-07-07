package com.miaokatze.gtswn.network;

import java.math.BigInteger;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import gregtech.common.misc.WirelessNetworkManager;
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
     * 服务端处理：查询玩家无线电网 EU 并回包。
     * <p>
     * 运行在服务端网络线程，查询 {@link WirelessNetworkManager}（服务端数据）安全。
     */
    public static class Handler implements IMessageHandler<PacketRequestWirelessEU, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestWirelessEU message, MessageContext ctx) {
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

            // 服务端查询无线电网 EU（GlobalEnergy 数据仅在服务端存在）
            BigInteger eu = WirelessNetworkManager.getUserEU(uuid);

            // 1.7.10 API：通过 ctx.getServerHandler().playerEntity 取得请求方玩家
            EntityPlayerMP player = (EntityPlayerMP) ctx.getServerHandler().playerEntity;
            if (player == null) {
                return null;
            }

            // 回包给请求方客户端（BigInteger.toString 自带符号与十进制，无需额外编码）
            GTSWNPacketHandler.NETWORK.sendTo(new PacketResponseWirelessEU(eu.toString()), player);
            return null;
        }
    }
}
