package com.miaokatze.gtswn.network;

import net.minecraft.client.Minecraft;

import com.miaokatze.gtswn.common.hud.WirelessMonitorHUD;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端→客户端 响应包：携带玩家无线电网 EU 余额字符串。
 * <p>
 * 服务端 {@link PacketRequestWirelessEU.Handler} 查询后通过此包回传，
 * 客户端主线程接收后写入 {@link WirelessMonitorHUD} 缓存，供 HUD 渲染只读缓存。
 * <p>
 * discriminator = 1（见 {@link GTSWNPacketHandler#register()}）。
 */
public class PacketResponseWirelessEU implements IMessage {

    /** EU 字符串（{@code BigInteger.toString()}，自带符号与十进制） */
    private String euStr;

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketResponseWirelessEU() {}

    public PacketResponseWirelessEU(String euStr) {
        this.euStr = euStr;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // 1.7.10 用 ByteBufUtils.readUTF8String 读写字符串
        euStr = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, euStr);
    }

    /**
     * 客户端处理：切回客户端主线程后写入 HUD 缓存。
     * <p>
     * 【线程安全】1.7.10 的 SimpleChannelHandlerWrapper.channelRead0 直接在 Netty 网络线程调用 onMessage，
     * 而 HUD 渲染在客户端主线程读取 static 缓存，两者并发会竞争 static 字段。故必须用
     * {@link Minecraft#func_152344_a(Runnable)}（1.7.10 中 addScheduledTask 的 SRG 名）把写操作调度到主线程，
     * 避免渲染线程读到半更新状态。
     * <p>
     * 【注意】不要在此类上加 @SideOnly(Side.CLIENT)！registerMessage 需要在双端都传入 Handler 的 Class 对象，
     * 加 @SideOnly 会导致服务端 SideTransformer 剥离该类，抛出 NoClassDefFoundError 崩服。
     */
    public static class Handler implements IMessageHandler<PacketResponseWirelessEU, IMessage> {

        @Override
        public IMessage onMessage(PacketResponseWirelessEU message, MessageContext ctx) {
            final String euStr = message.euStr;
            // 1.7.10 API：func_152344_a 等价于 1.8+ 的 addScheduledTask，调度到客户端主线程
            Minecraft.getMinecraft()
                .func_152344_a(() -> WirelessMonitorHUD.receiveSyncedEU(euStr));
            return null;
        }
    }
}
