package com.miaokatze.gtswn.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketUpdateNetworkInfoPanelConfig implements IMessage {

    private int x;
    private int y;
    private int z;
    private int action;

    public PacketUpdateNetworkInfoPanelConfig() {}

    public PacketUpdateNetworkInfoPanelConfig(int x, int y, int z, int action) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        action = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(action);
    }

    public static class Handler implements IMessageHandler<PacketUpdateNetworkInfoPanelConfig, IMessage> {

        @Override
        public IMessage onMessage(PacketUpdateNetworkInfoPanelConfig message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) {
                return null;
            }
            apply(message, player);
            return null;
        }

        private void apply(PacketUpdateNetworkInfoPanelConfig message, EntityPlayerMP player) {
            World world = player.worldObj;
            if (world == null || player.getDistanceSq(message.x + 0.5D, message.y + 0.5D, message.z + 0.5D) > 64D) {
                return;
            }
            TileEntity tile = world.getTileEntity(message.x, message.y, message.z);
            if (tile instanceof TileEntityNetworkInfoPanel) {
                ((TileEntityNetworkInfoPanel) tile).applyConfigAction(message.action);
            }
        }
    }
}
