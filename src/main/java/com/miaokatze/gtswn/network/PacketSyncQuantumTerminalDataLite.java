package com.miaokatze.gtswn.network;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端→客户端 短回包：量子终端轮询专用的 9 字段轻量同步（discriminator = 7，O2-17）。
 * <p>
 * 【为什么需要】v1.6.9 GUI 紧凑化后，{@link com.miaokatze.gtswn.client.gui.GuiQuantumTerminal}
 * 实际只消费 9 个字段（online / anchorDim+xyz / usedChannels / totalChannels /
 * quantumNodeCount / channelsInfinite），其余 15 字段 + entries 列表为死负载——
 * 全量包 6 每包 119B + ~10N（N=设备条目数，满配 128 条约 1.4KB），短包固定 30B：
 * N=0 时 -74.8%，满配 -97.9%，每开 GUI 玩家下行 2 包/s 负载同比例降。
 * <p>
 * 【协议约定】与包 6 同 jar 双端发布，无版本偏斜窗口；包 6 类保留作协议回退位与
 * 未来 GUI 回扩位（GUI 打开首包回全量、后续短包的回扩设计），本包不再被包 5 请求
 * 路径之外的场景使用。序列化顺序：
 * online(1B) + anchorDim/X/Y/Z(4×4B) + usedChannels(4B) + totalChannels(4B)
 * + quantumNodeCount(4B) + channelsInfinite(1B) = 30B。
 * <p>
 * 【客户端重建】fromBytes 构造 {@link QuantumNetworkData} 仅填 9 字段，其余字段保持
 * 类默认值（0 / false / 空 entries）——GUI 零改动，未消费字段读到默认值与全量包
 * 中真实值的表现一致（GUI 不读它们）。
 * <p>
 * 【hotfix v1.5.14 类加载安全模式】Handler 方法体只引用 CommonProxy（双端类型），
 * 不引用任何 @SideOnly(Side.CLIENT) 客户端类，也不加 @SideOnly 注解——
 * 与 {@link PacketSyncQuantumTerminalData.Handler} 同模式，实际客户端逻辑在
 * ClientProxy 中实现（func_152344_a 切主线程写 GUI 缓存）。
 */
public class PacketSyncQuantumTerminalDataLite implements IMessage {

    /** 网络快照数据（fromBytes 重建仅 9 字段；客户端经 getter 读取） */
    private QuantumNetworkData data;

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketSyncQuantumTerminalDataLite() {}

    public PacketSyncQuantumTerminalDataLite(QuantumNetworkData data) {
        this.data = data;
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
        this.data = d;
    }

    /** @return 网络快照数据（仅 9 字段有效，其余为类默认值） */
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
