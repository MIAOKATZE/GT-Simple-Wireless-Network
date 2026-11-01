package com.miaokatze.gtswn.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Client intent: get or set the sender's own shared link preference. */
public final class PacketTerminalLinkSettings implements IMessage {

    public boolean valid, update, enabled;

    public PacketTerminalLinkSettings() {}

    public PacketTerminalLinkSettings(boolean update, boolean enabled) {
        this.valid = true;
        this.update = update;
        this.enabled = enabled;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(update ? 1 : 0);
        buf.writeByte(enabled ? 1 : 0);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        valid = false;
        if (buf.readableBytes() != 2) return;
        int action = buf.readUnsignedByte(), value = buf.readUnsignedByte();
        if (action > 1 || value > 1) return;
        update = action == 1;
        enabled = value == 1;
        valid = true;
    }

    public static final class Handler implements IMessageHandler<PacketTerminalLinkSettings, IMessage> {

        @Override
        public IMessage onMessage(PacketTerminalLinkSettings message, MessageContext context) {
            if (context.side.isServer() && message.valid) DeviceLinkActionQueue
                .enqueueSettings(context.getServerHandler().playerEntity, message.update, message.enabled);
            return null;
        }
    }
}
