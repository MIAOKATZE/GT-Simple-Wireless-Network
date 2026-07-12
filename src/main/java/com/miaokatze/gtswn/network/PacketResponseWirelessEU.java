package com.miaokatze.gtswn.network;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

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
     * 客户端处理：委托给 {@link com.miaokatze.gtswn.main.CommonProxy#handleResponseEU}。
     * <p>
     * 【hotfix v1.5.14】原实现直接调用 {@code Minecraft.getMinecraft().func_152344_a(...)}。
     * 虽然 Minecraft 类本身无 @SideOnly 注解，当前不崩溃，但为了一致性和健壮性，
     * 统一通过 @SidedProxy 委托，避免 Handler 类方法体引用客户端 API。
     * <p>
     * 【注意】不要在此类上加 @SideOnly(Side.CLIENT)！registerMessage 需要在双端都传入
     * Handler 的 Class 对象，加 @SideOnly 会导致服务端 SideTransformer 剥离该类，
     * 抛出 NoClassDefFoundError 崩服。
     */
    public static class Handler implements IMessageHandler<PacketResponseWirelessEU, IMessage> {

        @Override
        public IMessage onMessage(PacketResponseWirelessEU message, MessageContext ctx) {
            // 包注册在 CLIENT，但防御性校验 side 避免异常场景
            if (ctx.side.isServer()) {
                return null;
            }
            // 委托给 @SidedProxy：服务端调用 CommonProxy 空实现，客户端调用 ClientProxy 实际处理
            GTSimpleWirelessNetwork.proxy.handleResponseEU(message.euStr);
            return null;
        }
    }
}
