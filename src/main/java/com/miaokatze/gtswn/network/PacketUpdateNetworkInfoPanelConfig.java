package com.miaokatze.gtswn.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketUpdateNetworkInfoPanelConfig implements IMessage {

    /** 常规 EU 图表配置（对应 TileEntity.applyChartConfig） */
    public static final int ACTION_CHART_CONFIG = -1;
    /** AE 图表配置（对应 TileEntity.applyAEChartConfig） */
    public static final int ACTION_AE_CHART_CONFIG = -2;

    private int x;
    private int y;
    private int z;
    private int action;
    private String chartConfig = "";

    public PacketUpdateNetworkInfoPanelConfig() {}

    public PacketUpdateNetworkInfoPanelConfig(int x, int y, int z, int action) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action;
    }

    public PacketUpdateNetworkInfoPanelConfig(int x, int y, int z, String chartConfig) {
        this(x, y, z, chartConfig, false);
    }

    public PacketUpdateNetworkInfoPanelConfig(int x, int y, int z, String chartConfig, boolean aeConfig) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = aeConfig ? ACTION_AE_CHART_CONFIG : ACTION_CHART_CONFIG;
        this.chartConfig = chartConfig == null ? "" : chartConfig;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        action = buf.readInt();
        chartConfig = buf.readableBytes() > 0 ? ByteBufUtils.readUTF8String(buf) : "";
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(action);
        ByteBufUtils.writeUTF8String(buf, chartConfig == null ? "" : chartConfig);
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
                TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
                if (message.action == ACTION_CHART_CONFIG) {
                    panel.applyChartConfig(message.chartConfig);
                } else if (message.action == ACTION_AE_CHART_CONFIG) {
                    panel.applyAEChartConfig(message.chartConfig);
                } else if (message.action >= 0) {
                    panel.applyConfigAction(message.action);
                }
            }
        }
    }
}
