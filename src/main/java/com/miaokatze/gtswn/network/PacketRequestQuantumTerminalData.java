package com.miaokatze.gtswn.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端→服务端 请求包：请求刷新 ME 网络量子终端的显示数据（discriminator = 5）。
 * <p>
 * 无字段：服务端直接从 {@code ctx.getServerHandler().playerEntity.getHeldItem()} 取玩家手持物品，
 * 校验为「已绑定的 ME 网络量子终端」后按终端 NBT 锚点装配 {@link QuantumNetworkData}，
 * 经 {@link PacketSyncQuantumTerminalData} 回发给请求方。
 * <p>
 * 发送方：{@code GuiQuantumTerminal.updateScreen()} 每 10 tick 轮询一次（规划 §6 客户端刷新模式）。
 * 手持校验失败（不是终端 / 未绑定）时静默丢弃不回包，客户端等下个周期重试。
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
     * 服务端处理：校验手持 → 装配 → 回包。
     * <p>
     * 线程模型照项目既有 C→S 包（PacketUpdateAETabState/PacketRequestWirelessEU）：
     * 直接在 onMessage 内访问世界数据，不做额外线程切换。
     */
    public static class Handler implements IMessageHandler<PacketRequestQuantumTerminalData, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestQuantumTerminalData msg, MessageContext ctx) {
            // 1.7.10 API：经 ctx.getServerHandler().playerEntity 取得请求方玩家
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) {
                return null;
            }
            ItemStack held = player.getHeldItem();
            // 装配内含手持校验：非已绑定量子终端返回 null，静默丢弃（客户端等下轮重试）
            QuantumNetworkData data = QuantumNetworkData.assemble(player, held);
            if (data == null) {
                return null;
            }
            // 回发数据同步包给请求方客户端
            GTSWNPacketHandler.NETWORK.sendTo(new PacketSyncQuantumTerminalData(data), player);
            return null;
        }
    }
}
