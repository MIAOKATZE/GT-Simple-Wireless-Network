package com.miaokatze.gtswn.network;

import com.miaokatze.gtswn.common.device.DeviceTerminalSettings;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Small independent settings packet, sent only when cost, privileges or interval change. */
public final class PacketSyncDeviceTerminalSettings implements IMessage {

    public int teleportCost;
    public boolean canEdit;
    public int samplingSeconds;
    public int teleportAnchors;

    public PacketSyncDeviceTerminalSettings() {}

    public PacketSyncDeviceTerminalSettings(int cost, boolean editable, int seconds) {
        this(cost, editable, seconds, 0);
    }

    public PacketSyncDeviceTerminalSettings(int cost, boolean editable, int seconds, int anchors) {
        teleportCost = cost;
        canEdit = editable;
        samplingSeconds = seconds;
        teleportAnchors = anchors;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeInt(teleportCost);
        buffer.writeBoolean(canEdit);
        buffer.writeInt(samplingSeconds);
        buffer.writeShort(teleportAnchors);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        try {
            teleportCost = Math.max(0, Math.min(DeviceTerminalSettings.MAX_TELEPORT_COST, buffer.readInt()));
            canEdit = buffer.readBoolean();
            samplingSeconds = Math.max(1, Math.min(3600, buffer.readInt()));
            teleportAnchors = buffer.readableBytes() >= 2
                ? Math.min(com.miaokatze.gtswn.common.gui.TerminalSupplyStore.CAPACITY, buffer.readUnsignedShort())
                : 0;
        } catch (RuntimeException invalid) {
            teleportCost = 0;
            canEdit = false;
            samplingSeconds = 5;
            teleportAnchors = 0;
        }
    }

    public static final class Handler implements IMessageHandler<PacketSyncDeviceTerminalSettings, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncDeviceTerminalSettings message, MessageContext context) {
            if (context.side.isClient()) GTSimpleWirelessNetwork.proxy.handleSyncDeviceTerminalSettings(message);
            return null;
        }
    }
}
