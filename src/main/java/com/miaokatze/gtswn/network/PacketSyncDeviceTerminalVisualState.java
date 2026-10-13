package com.miaokatze.gtswn.network;

import java.util.UUID;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Server acknowledgement for a runtime mode, with the server's actual terminal UUID. */
public final class PacketSyncDeviceTerminalVisualState implements IMessage {

    public UUID terminalId;
    public boolean enabled;

    public PacketSyncDeviceTerminalVisualState() {}

    public PacketSyncDeviceTerminalVisualState(UUID id, boolean enabled) {
        terminalId = id;
        this.enabled = enabled;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeLong(terminalId.getMostSignificantBits());
        buffer.writeLong(terminalId.getLeastSignificantBits());
        buffer.writeBoolean(enabled);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        terminalId = null;
        enabled = false;
        try {
            if (buffer.readableBytes() != 17) return;
            UUID id = new UUID(buffer.readLong(), buffer.readLong());
            int state = buffer.readUnsignedByte();
            if (state > 1 || PacketDeviceTerminalVisualAction.NEW_TERMINAL.equals(id)) return;
            terminalId = id;
            enabled = state == 1;
        } catch (RuntimeException invalid) {}
    }

    public static final class Handler implements IMessageHandler<PacketSyncDeviceTerminalVisualState, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncDeviceTerminalVisualState message, MessageContext context) {
            if (context.side.isClient() && message.terminalId != null)
                GTSimpleWirelessNetwork.proxy.handleSyncDeviceTerminalVisualState(message);
            return null;
        }
    }
}
