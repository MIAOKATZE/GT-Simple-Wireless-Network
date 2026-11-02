package com.miaokatze.gtswn.network;

import java.util.UUID;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Whole-device desired state. The client sends no node coordinates, owner, counts or terminal mode. */
public final class PacketDeviceLinkAction implements IMessage {

    public boolean valid, desiredState;
    public UUID terminalId;
    public String key;
    public int meta;
    public long requestId;

    public PacketDeviceLinkAction() {}

    public PacketDeviceLinkAction(UUID terminalId, String key, int meta, long requestId, boolean desiredState) {
        this.terminalId = terminalId;
        this.key = key;
        this.meta = meta;
        this.requestId = requestId;
        this.desiredState = desiredState;
        this.valid = true;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(terminalId.getMostSignificantBits());
        buf.writeLong(terminalId.getLeastSignificantBits());
        ByteBufUtils.writeUTF8String(buf, key);
        buf.writeInt(meta);
        buf.writeLong(requestId);
        buf.writeByte(desiredState ? 1 : 0);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        valid = false;
        try {
            terminalId = new UUID(buf.readLong(), buf.readLong());
            key = ByteBufUtils.readUTF8String(buf);
            meta = buf.readInt();
            requestId = buf.readLong();
            int desired = buf.readUnsignedByte();
            valid = key.length() <= 64 && PacketSyncDeviceTerminalData.KEY_PATTERN.matcher(key)
                .matches() && meta >= 0 && requestId > 0 && desired <= 1 && !buf.isReadable();
            desiredState = desired == 1;
        } catch (RuntimeException malformed) {
            valid = false;
        }
    }

    public static final class Handler implements IMessageHandler<PacketDeviceLinkAction, IMessage> {

        @Override
        public IMessage onMessage(PacketDeviceLinkAction message, MessageContext context) {
            if (context.side.isServer() && message.valid)
                DeviceLinkActionQueue.enqueue(context.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
