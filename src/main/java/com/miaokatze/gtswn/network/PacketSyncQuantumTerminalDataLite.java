package com.miaokatze.gtswn.network;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * S2C terminal polling response (discriminator 7). The base network metrics occupy 38 bytes;
 * safety appends global/local/capability booleans, a UTF-8 controller identity, and a response token.
 * Old payloads retain local safety on and capability off. Actor capabilities and tokens are held
 * by the response, never written into the shared anchor snapshot. Client handling delegates through
 * the sided proxy, keeping dedicated-server class loading independent of client classes.
 */
public class PacketSyncQuantumTerminalDataLite implements IMessage {

    /** 网络快照数据（fromBytes 重建仅 16 字段；客户端经 getter 读取） */
    private QuantumNetworkData data;
    private boolean globalSafetyEnabled;
    private boolean canChangeNetworkSafety;
    private long networkSafetyRequestToken;

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketSyncQuantumTerminalDataLite() {}

    public PacketSyncQuantumTerminalDataLite(QuantumNetworkData data) {
        this(data, data.globalSafetyEnabled, data.canChangeNetworkSafety, data.networkSafetyRequestToken);
    }

    /** Player capabilities belong to this response, never to the shared anchor cache. */
    public PacketSyncQuantumTerminalDataLite(QuantumNetworkData data, boolean globalSafetyEnabled,
        boolean canChangeNetworkSafety, long networkSafetyRequestToken) {
        this.data = data;
        this.globalSafetyEnabled = globalSafetyEnabled;
        this.canChangeNetworkSafety = canChangeNetworkSafety;
        this.networkSafetyRequestToken = networkSafetyRequestToken;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(data.online);
        buf.writeInt(data.anchorDim);
        buf.writeInt(data.anchorX);
        buf.writeInt(data.anchorY);
        buf.writeInt(data.anchorZ);
        buf.writeInt(data.usedChannels);
        buf.writeInt(data.totalChannels);
        buf.writeInt(data.quantumNodeCount);
        buf.writeBoolean(data.channelsInfinite);
        buf.writeInt(data.incorporationCount);
        buf.writeInt(data.incorporationChannels);
        buf.writeBoolean(globalSafetyEnabled);
        buf.writeBoolean(data.networkSafetyEnabled);
        buf.writeBoolean(canChangeNetworkSafety);
        ByteBufUtils.writeUTF8String(buf, data.networkSafetyIdentity);
        buf.writeLong(networkSafetyRequestToken);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        QuantumNetworkData d = new QuantumNetworkData();
        d.online = buf.readBoolean();
        d.anchorDim = buf.readInt();
        d.anchorX = buf.readInt();
        d.anchorY = buf.readInt();
        d.anchorZ = buf.readInt();
        d.usedChannels = buf.readInt();
        d.totalChannels = buf.readInt();
        d.quantumNodeCount = buf.readInt();
        d.channelsInfinite = buf.readBoolean();
        if (buf.readableBytes() >= 8) {
            d.incorporationCount = buf.readInt();
            d.incorporationChannels = buf.readInt();
        }
        // Old payloads retain safety=true and capability=false.
        if (buf.readableBytes() >= 3) {
            d.globalSafetyEnabled = buf.readBoolean();
            d.networkSafetyEnabled = buf.readBoolean();
            d.canChangeNetworkSafety = buf.readBoolean();
            if (buf.isReadable()) d.networkSafetyIdentity = ByteBufUtils.readUTF8String(buf);
            if (buf.readableBytes() >= 8) d.networkSafetyRequestToken = buf.readLong();
        }
        this.data = d;
    }

    /** @return 网络快照数据（仅 16 字段有效，其余为类默认值） */
    public QuantumNetworkData getData() {
        return data;
    }

    /**
     * 客户端处理：委托给 {@link com.miaokatze.gtswn.main.CommonProxy#handleSyncQuantumTerminalDataLite}。
     * <p>
     * 类加载安全模式与 {@link PacketSyncQuantumTerminalData.Handler} 相同：
     * 方法体只引用 CommonProxy，实际客户端逻辑在 ClientProxy 中实现。
     */
    public static class Handler implements IMessageHandler<PacketSyncQuantumTerminalDataLite, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncQuantumTerminalDataLite msg, MessageContext ctx) {
            // 包注册在 CLIENT，但防御性校验 side 避免异常场景
            if (ctx.side.isServer()) {
                return null;
            }
            // 委托给 @SidedProxy：服务端调用 CommonProxy 空实现，客户端调用 ClientProxy 实际处理
            GTSimpleWirelessNetwork.proxy.handleSyncQuantumTerminalDataLite(msg);
            return null;
        }
    }
}
