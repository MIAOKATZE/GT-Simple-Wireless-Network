package com.miaokatze.gtswn.network;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** A result acknowledges exactly one client attempt; stale results cannot stop another charge. */
public final class PacketQuantumMigrationResult implements IMessage {

    public static final int REJECTED = 0;
    public static final int CANCELLED = 1;
    public static final int SUCCESS = 2;
    public long session;
    public int result = -1;

    public PacketQuantumMigrationResult() {}

    public PacketQuantumMigrationResult(long session, int result) {
        this.session = session;
        this.result = result;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        result = -1;
        if (buffer.readableBytes() != 9) return;
        session = buffer.readLong();
        int candidate = buffer.readUnsignedByte();
        if (session > 0 && candidate <= SUCCESS) result = candidate;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeLong(session);
        buffer.writeByte(result);
    }

    public static final class Handler implements IMessageHandler<PacketQuantumMigrationResult, IMessage> {

        @Override
        public IMessage onMessage(PacketQuantumMigrationResult message, MessageContext context) {
            if (context.side.isClient() && message.result >= REJECTED && message.result <= SUCCESS)
                GTSimpleWirelessNetwork.proxy.handleQuantumMigrationResult(message.session, message.result);
            return null;
        }
    }
}
