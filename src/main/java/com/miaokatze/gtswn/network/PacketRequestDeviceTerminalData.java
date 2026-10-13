package com.miaokatze.gtswn.network;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtswn.common.device.DeviceTerminalRequestQueue;
import com.miaokatze.gtswn.common.performance.PerformanceAudit;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Client cache metadata assists retransmission only; the server independently verifies terminal possession. */
public class PacketRequestDeviceTerminalData implements IMessage {

    private UUID terminalId;
    private long knownVersion = -1;
    private boolean complete;
    private boolean hasClientState;
    private boolean valid = true;

    public PacketRequestDeviceTerminalData() {}

    public PacketRequestDeviceTerminalData(UUID id, long knownVersion, boolean complete) {
        terminalId = id;
        this.knownVersion = Math.max(-1, knownVersion);
        this.complete = complete;
        hasClientState = true;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        terminalId = null;
        knownVersion = -1;
        complete = false;
        hasClientState = false;
        valid = true;
        if (!buffer.isReadable()) return;
        if (buffer.readableBytes() != 25) {
            valid = false;
            return;
        }
        try {
            UUID id = new UUID(buffer.readLong(), buffer.readLong());
            knownVersion = buffer.readLong();
            int flag = buffer.readUnsignedByte();
            if (knownVersion < -1 || flag > 1) {
                valid = false;
                return;
            }
            terminalId = id.equals(new UUID(0, 0)) ? null : id;
            complete = flag == 1;
            hasClientState = true;
            if (complete && knownVersion < 0) valid = false;
        } catch (RuntimeException invalid) {
            valid = false;
        }
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        if (!hasClientState) return;
        UUID id = terminalId == null ? new UUID(0, 0) : terminalId;
        buffer.writeLong(id.getMostSignificantBits());
        buffer.writeLong(id.getLeastSignificantBits());
        buffer.writeLong(knownVersion);
        buffer.writeBoolean(complete);
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public long getKnownVersion() {
        return knownVersion;
    }

    public boolean isComplete() {
        return complete;
    }

    public boolean hasClientState() {
        return hasClientState;
    }

    public boolean isValid() {
        return valid;
    }

    public static class Handler implements IMessageHandler<PacketRequestDeviceTerminalData, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestDeviceTerminalData message, MessageContext context) {
            if (!context.side.isServer() || !message.valid) return null;
            if (PerformanceAudit.enabled()) PerformanceAudit.recordPacketReceived(8);
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            if (player != null) DeviceTerminalRequestQueue
                .enqueue(player, message.terminalId, message.knownVersion, message.complete, message.hasClientState);
            return null;
        }
    }
}
