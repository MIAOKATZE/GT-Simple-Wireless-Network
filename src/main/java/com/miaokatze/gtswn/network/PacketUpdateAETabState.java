package com.miaokatze.gtswn.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtswn.common.performance.PerformanceAudit;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * C→S 标签页切换 + 监视列表操作包（discriminator=3）。
 * <p>
 * actionType 约定：
 * <ul>
 * <li>0 = 切换标签页（tabIndex 有效）</li>
 * <li>1 = 走势图绑定物品（stackData 含 ItemStack）</li>
 * <li>2 = 走势图绑定流体（stackData 含 FluidStack）</li>
 * <li>3 = 监控列表切换物品（stackData 含 ItemStack）</li>
 * <li>4 = 监控列表切换流体（stackData 含 FluidStack）</li>
 * <li>5 = 清除走势图绑定</li>
 * <li>6 = 清除 AE 实时监控全部物品与流体</li>
 * </ul>
 */
public class PacketUpdateAETabState implements IMessage {

    private int panelX, panelY, panelZ;
    private byte actionType;
    private int tabIndex;
    private NBTTagCompound stackData;

    public PacketUpdateAETabState() {}

    public PacketUpdateAETabState(int x, int y, int z, byte action, int tab, NBTTagCompound data) {
        this.panelX = x;
        this.panelY = y;
        this.panelZ = z;
        this.actionType = action;
        this.tabIndex = tab;
        this.stackData = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        panelX = buf.readInt();
        panelY = buf.readInt();
        panelZ = buf.readInt();
        actionType = buf.readByte();
        tabIndex = buf.readInt();
        if (buf.readBoolean()) {
            stackData = ByteBufUtils.readTag(buf);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(panelX);
        buf.writeInt(panelY);
        buf.writeInt(panelZ);
        buf.writeByte(actionType);
        buf.writeInt(tabIndex);
        buf.writeBoolean(stackData != null);
        if (stackData != null) {
            ByteBufUtils.writeTag(buf, stackData);
        }
    }

    public static class Handler implements IMessageHandler<PacketUpdateAETabState, IMessage> {

        @Override
        public IMessage onMessage(PacketUpdateAETabState msg, MessageContext ctx) {
            // v1.6.19：性能审计——C→S 包计数（discriminator 3）
            if (PerformanceAudit.enabled()) PerformanceAudit.recordPacketReceived(3);
            // B07（吸收 B2-01）：补 player null 守卫（与包 2 同款，原 Netty 直改路径缺失）
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null || player.worldObj == null) {
                return null;
            }
            // SWN-BUG-03：与包 2（PacketUpdateNetworkInfoPanelConfig）同款 8 格距离拦截——
            // 防止恶意客户端携带任意坐标对他人信息屏越权切页/改绑定/清空监控
            // （ownerUUID 是数据集归属键而非权限键，且可能尚未绑定，故与包 2 范式一致仅做距离校验）
            if (player.getDistanceSq(msg.panelX + 0.5D, msg.panelY + 0.5D, msg.panelZ + 0.5D) > 64D) {
                return null;
            }
            // B07（吸收 B2-01）：Netty 线程只入队，switch 世界态修改由
            // PanelActionQueue 在主线程 drain 复验（在线/距离/TE 类型）后执行
            PanelActionQueue
                .enqueueAETab(player, msg.panelX, msg.panelY, msg.panelZ, msg.actionType, msg.tabIndex, msg.stackData);
            return null;
        }
    }
}
