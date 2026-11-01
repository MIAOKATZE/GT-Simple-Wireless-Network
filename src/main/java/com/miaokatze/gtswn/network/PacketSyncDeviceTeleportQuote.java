package com.miaokatze.gtswn.network;

import java.util.UUID;

import com.miaokatze.gtswn.common.device.DeviceTerminalSettings;
import com.miaokatze.gtswn.common.device.DeviceTerminalTeleport.PaymentSource;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** The server's expiring quote, not a client-selected payment method or authorization. */
public final class PacketSyncDeviceTeleportQuote implements IMessage {

    public long token;
    public UUID terminalId;
    public String targetKey = "";
    public PaymentSource source;
    public int amount;
    public boolean changed;
    public boolean finished;

    public PacketSyncDeviceTeleportQuote() {}

    public PacketSyncDeviceTeleportQuote(long token, UUID terminalId, String targetKey, PaymentSource source,
        int amount, boolean changed) {
        this.token = token;
        this.terminalId = terminalId;
        this.targetKey = targetKey;
        this.source = source;
        this.amount = amount;
        this.changed = changed;
    }

    public static PacketSyncDeviceTeleportQuote finished(long token, UUID terminalId, String targetKey,
        PaymentSource source, int amount) {
        PacketSyncDeviceTeleportQuote result = new PacketSyncDeviceTeleportQuote(
            token,
            terminalId,
            targetKey,
            source,
            amount,
            false);
        result.finished = true;
        return result;
    }

    public boolean valid() {
        return token != 0 && terminalId != null
            && source != null
            && PacketSyncDeviceTerminalData.KEY_PATTERN.matcher(targetKey)
                .matches()
            && (source == PaymentSource.FREE ? amount == 0
                : source == PaymentSource.EXPERIENCE ? amount > 0 && amount <= DeviceTerminalSettings.MAX_TELEPORT_COST
                    : amount == 1);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeLong(token);
        buffer.writeLong(terminalId.getMostSignificantBits());
        buffer.writeLong(terminalId.getLeastSignificantBits());
        ByteBufUtils.writeUTF8String(buffer, targetKey);
        buffer.writeByte(source.ordinal());
        buffer.writeInt(amount);
        buffer.writeByte((changed ? 1 : 0) | (finished ? 2 : 0));
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        token = 0;
        source = null;
        try {
            if (buffer.readableBytes() < 32 || buffer.readableBytes() > 224) return;
            long readToken = buffer.readLong();
            terminalId = new UUID(buffer.readLong(), buffer.readLong());
            targetKey = PacketDeviceTerminalVisualAction.readBoundedUtf8(buffer, 64);
            int ordinal = buffer.readUnsignedByte();
            if (ordinal >= PaymentSource.values().length) return;
            source = PaymentSource.values()[ordinal];
            amount = buffer.readInt();
            int flags = buffer.readUnsignedByte();
            if (flags > 3 || buffer.isReadable()) return;
            changed = (flags & 1) != 0;
            finished = (flags & 2) != 0;
            token = readToken;
            if (!valid()) token = 0;
        } catch (Exception invalid) {
            token = 0;
        }
    }

    public static final class Handler implements IMessageHandler<PacketSyncDeviceTeleportQuote, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncDeviceTeleportQuote message, MessageContext context) {
            if (context.side.isClient() && message.valid())
                GTSimpleWirelessNetwork.proxy.handleDeviceTeleportQuote(message);
            return null;
        }
    }
}
