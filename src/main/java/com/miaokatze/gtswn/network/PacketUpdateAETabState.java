package com.miaokatze.gtswn.network;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

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
            EntityPlayer player = ctx.getServerHandler().playerEntity;
            World world = player.worldObj;
            TileEntity te = world.getTileEntity(msg.panelX, msg.panelY, msg.panelZ);
            if (!(te instanceof TileEntityNetworkInfoPanel)) return null;
            TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) te;

            switch (msg.actionType) {
                case 0: // 切换标签页
                    panel.setCurrentTab(msg.tabIndex);
                    break;
                case 1: // 走势图绑定物品
                    if (msg.stackData != null) {
                        ItemStack stack = ItemStack.loadItemStackFromNBT(msg.stackData);
                        panel.setChartItem(stack);
                    }
                    break;
                case 2: // 走势图绑定流体
                    if (msg.stackData != null) {
                        FluidStack fluid = FluidStack.loadFluidStackFromNBT(msg.stackData);
                        panel.setChartFluid(fluid);
                    }
                    break;
                case 3: // 监控列表切换物品
                    if (msg.stackData != null) {
                        ItemStack stack = ItemStack.loadItemStackFromNBT(msg.stackData);
                        panel.toggleItemMonitor(stack);
                    }
                    break;
                case 4: // 监控列表切换流体
                    if (msg.stackData != null) {
                        FluidStack fluid = FluidStack.loadFluidStackFromNBT(msg.stackData);
                        panel.toggleFluidMonitor(fluid);
                    }
                    break;
                case 5: // 清除走势图绑定
                    panel.clearAEBinding();
                    break;
                default:
                    break;
            }
            return null;
        }
    }
}
