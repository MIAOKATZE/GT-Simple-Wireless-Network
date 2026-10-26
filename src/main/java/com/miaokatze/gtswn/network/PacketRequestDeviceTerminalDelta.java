package com.miaokatze.gtswn.network;

import java.util.UUID;

import com.miaokatze.gtswn.common.device.DeviceTerminalRequestQueue;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Negotiated-only request. ACK refers exclusively to a complete, atomically committed snapshot. */
public final class PacketRequestDeviceTerminalDelta extends PacketRequestDeviceTerminalData {

    public static final int WIRE_BYTES = 57;
    private UUID terminalId, session;
    private long epoch, acknowledged = -1, pending = -1;
    private boolean valid;

    public PacketRequestDeviceTerminalDelta() {}

    public PacketRequestDeviceTerminalDelta(UUID terminalId, UUID session, long epoch, long acknowledged,
        long pending) {
        this.terminalId = terminalId;
        this.session = session;
        this.epoch = epoch;
        this.acknowledged = acknowledged;
        this.pending = pending;
        valid = terminalId != null && session != null && epoch >= 0 && acknowledged >= -1 && pending >= -1;
    }

    @Override
    public void toBytes(ByteBuf out) {
        out.writeByte(1);
        out.writeLong(terminalId.getMostSignificantBits());
        out.writeLong(terminalId.getLeastSignificantBits());
        out.writeLong(session.getMostSignificantBits());
        out.writeLong(session.getLeastSignificantBits());
        out.writeLong(epoch);
        out.writeLong(acknowledged);
        out.writeLong(pending);
    }

    @Override
    public void fromBytes(ByteBuf in) {
        valid = false;
        if (in.readableBytes() != WIRE_BYTES) return;
        if (in.readUnsignedByte() != 1) return;
        terminalId = new UUID(in.readLong(), in.readLong());
        session = new UUID(in.readLong(), in.readLong());
        epoch = in.readLong();
        acknowledged = in.readLong();
        pending = in.readLong();
        valid = epoch >= 0 && acknowledged >= -1 && pending >= -1;
    }

    @Override
    public UUID getTerminalId() {
        return terminalId;
    }

    public UUID getSession() {
        return session;
    }

    public long getEpoch() {
        return epoch;
    }

    @Override
    public long getKnownVersion() {
        return acknowledged;
    }

    public long getPendingVersion() {
        return pending;
    }

    @Override
    public boolean isComplete() {
        return acknowledged >= 0;
    }

    @Override
    public boolean hasClientState() {
        return true;
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    public static final class Handler implements IMessageHandler<PacketRequestDeviceTerminalDelta, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestDeviceTerminalDelta msg, MessageContext context) {
            if (context.side.isServer() && msg.valid)
                DeviceTerminalRequestQueue.enqueueDelta(context.getServerHandler().playerEntity, msg);
            return null;
        }
    }
}
