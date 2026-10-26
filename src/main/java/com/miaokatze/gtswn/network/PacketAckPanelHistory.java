package com.miaokatze.gtswn.network;

import java.util.UUID;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** ACK identifies an actually sent physical panel version. Zero requests a bounded full reset. */
public final class PacketAckPanelHistory implements IMessage {

    public int x, y, z;
    public UUID panelId, sessionId;
    public long sequence;
    public boolean valid;

    public PacketAckPanelHistory() {}

    public PacketAckPanelHistory(PacketSyncPanelHistory source, long sequence) {
        x = source.x;
        y = source.y;
        z = source.z;
        panelId = source.panelId;
        sessionId = source.sessionId;
        this.sequence = sequence;
        valid = true;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeLong(panelId.getMostSignificantBits());
        buf.writeLong(panelId.getLeastSignificantBits());
        buf.writeLong(sessionId.getMostSignificantBits());
        buf.writeLong(sessionId.getLeastSignificantBits());
        buf.writeLong(sequence);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        valid = false;
        if (buf.readableBytes() != 52) return;
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        panelId = new UUID(buf.readLong(), buf.readLong());
        sessionId = new UUID(buf.readLong(), buf.readLong());
        sequence = buf.readLong();
        valid = sequence >= 0;
    }

    public static final class Handler implements IMessageHandler<PacketAckPanelHistory, IMessage> {

        @Override
        public IMessage onMessage(PacketAckPanelHistory message, MessageContext context) {
            if (message.valid) NetworkPanelBroadcastPort.acknowledge(context.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
