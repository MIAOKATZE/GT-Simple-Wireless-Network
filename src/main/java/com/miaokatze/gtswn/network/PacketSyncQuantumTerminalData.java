package com.miaokatze.gtswn.network;

import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端→客户端 同步包：推送 ME 网络量子终端的网络快照（discriminator = 6）。
 * <p>
 * 字段与 {@link QuantumNetworkData} 一一对应（规划 plan_20260722152445.md §6）：
 * online / anchorDim+xyz / totalChannels / usedChannels / 能量四项 /
 * itemBytesUsed/Total / fluidBytesUsed/Total / essentiaBytesUsed/Total / powerInfinite /
 * totalMachines / entryCount + entries{ItemStack icon, int count} / quantumNodeCount /
 * channelsInfinite（末尾追加，兼容旧包）。
 * <p>
 * 序列化约定：toBytes/fromBytes 严格对称（风格仿 {@link PacketSyncAEMonitorData}）；
 * ItemStack 用 {@link ByteBufUtils#writeItemStack}（图标为 machineRepresentation 小对象，
 * 无大 NBT，直接写入即可）；online=false 时仍写全字段（值为默认 0/空列表），保持读写对称。
 * v1.6.9 末尾追加 quantumNodeCount 保持向后兼容。
 */
public class PacketSyncQuantumTerminalData implements IMessage {

    /** 网络快照数据（fromBytes 重建；客户端经 getter 读取） */
    private QuantumNetworkData data;

    /** Forge 反射无参构造（反序列化时必需） */
    public PacketSyncQuantumTerminalData() {}

    public PacketSyncQuantumTerminalData(QuantumNetworkData data) {
        this.data = data;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(data.online);
        buf.writeInt(data.anchorDim);
        buf.writeInt(data.anchorX);
        buf.writeInt(data.anchorY);
        buf.writeInt(data.anchorZ);
        buf.writeInt(data.totalChannels);
        buf.writeInt(data.usedChannels);
        buf.writeDouble(data.avgPowerUsage);
        buf.writeDouble(data.avgPowerInjection);
        buf.writeDouble(data.storedPower);
        buf.writeDouble(data.maxStoredPower);
        buf.writeDouble(data.itemBytesUsed);
        buf.writeDouble(data.itemBytesTotal);
        buf.writeDouble(data.fluidBytesUsed);
        buf.writeDouble(data.fluidBytesTotal);
        buf.writeDouble(data.essentiaBytesUsed);
        buf.writeDouble(data.essentiaBytesTotal);
        buf.writeBoolean(data.powerInfinite);
        buf.writeInt(data.totalMachines);
        // 设备条目：entryCount + entryCount × { ItemStack, int }
        buf.writeInt(data.entries.size());
        for (QuantumNetworkData.DeviceEntry entry : data.entries) {
            ByteBufUtils.writeItemStack(buf, entry.icon);
            buf.writeInt(entry.count);
        }
        // v1.6.9：末尾追加 quantumNodeCount（保持向后兼容，旧客户端读取时多出 4 字节被丢弃）
        buf.writeInt(data.quantumNodeCount);
        // v1.6.15：末尾追加无限频道标志，旧客户端会忽略多出的 1 字节
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
        d.totalChannels = buf.readInt();
        d.usedChannels = buf.readInt();
        d.avgPowerUsage = buf.readDouble();
        d.avgPowerInjection = buf.readDouble();
        d.storedPower = buf.readDouble();
        d.maxStoredPower = buf.readDouble();
        d.itemBytesUsed = buf.readDouble();
        d.itemBytesTotal = buf.readDouble();
        d.fluidBytesUsed = buf.readDouble();
        d.fluidBytesTotal = buf.readDouble();
        d.essentiaBytesUsed = buf.readDouble();
        d.essentiaBytesTotal = buf.readDouble();
        d.powerInfinite = buf.readBoolean();
        d.totalMachines = buf.readInt();
        int entryCount = buf.readInt();
        // 防御性上限：异常包体的 entryCount 不应导致内存暴涨（与装配端 MAX_ENTRIES 一致留余量）
        entryCount = Math.min(entryCount, QuantumNetworkData.MAX_ENTRIES);
        for (int i = 0; i < entryCount; i++) {
            ItemStack icon = ByteBufUtils.readItemStack(buf);
            int count = buf.readInt();
            if (icon != null && icon.getItem() != null) {
                d.entries.add(new QuantumNetworkData.DeviceEntry(icon, count));
            }
        }
        // v1.6.9：防御性读取 quantumNodeCount（防新客户端读旧服务端包越界崩溃）
        if (buf.readableBytes() >= 4) {
            d.quantumNodeCount = buf.readInt();
        }
        // v1.6.15：旧服务端没有该字段时保留默认 false
        if (buf.readableBytes() >= 1) {
            d.channelsInfinite = buf.readBoolean();
        }
        this.data = d;
    }

    /** @return 网络快照数据（客户端读取用） */
    public QuantumNetworkData getData() {
        return data;
    }

    /**
     * 客户端处理：委托给 {@link com.miaokatze.gtswn.main.CommonProxy#handleSyncQuantumTerminalData}。
     * <p>
     * 【hotfix v1.5.14 类加载安全模式】本 Handler 方法体只引用 CommonProxy（双端类型），
     * 不引用任何 @SideOnly(Side.CLIENT) 客户端类，也不加 @SideOnly 注解——
     * 否则 registerMessage 时 Handler.class.newInstance() 触发 getDeclaredConstructors0()
     * 解析方法体引用类型，会在服务端加载客户端类被 SideTransformer 拒绝崩服。
     * 实际客户端逻辑在 ClientProxy 中实现（func_152344_a 切主线程写 GUI 缓存）。
     */
    public static class Handler implements IMessageHandler<PacketSyncQuantumTerminalData, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncQuantumTerminalData msg, MessageContext ctx) {
            // 包注册在 CLIENT，但防御性校验 side 避免异常场景
            if (ctx.side.isServer()) {
                return null;
            }
            // 委托给 @SidedProxy：服务端调用 CommonProxy 空实现，客户端调用 ClientProxy 实际处理
            GTSimpleWirelessNetwork.proxy.handleSyncQuantumTerminalData(msg);
            return null;
        }
    }
}
