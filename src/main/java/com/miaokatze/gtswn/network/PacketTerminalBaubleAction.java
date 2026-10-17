package com.miaokatze.gtswn.network;

import com.miaokatze.gtswn.common.baubles.TerminalBaubles;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class PacketTerminalBaubleAction implements IMessage {

    public static final int EQUIP = 0, SELECT = 1, RETURN = 2;
    public int action = -1, selection = -1;

    public PacketTerminalBaubleAction() {}

    public PacketTerminalBaubleAction(int action, int selection) {
        this.action = action;
        this.selection = selection;
    }

    public void toBytes(ByteBuf buffer) {
        buffer.writeByte(action);
        buffer.writeByte(selection);
    }

    public void fromBytes(ByteBuf buffer) {
        action = -1;
        selection = -1;
        if (buffer.readableBytes() != 2) return;
        int candidate = buffer.readUnsignedByte(), selected = buffer.readByte();
        if (candidate <= RETURN && selected >= -1
            && selected < TerminalBaubles.SIZE
            && (candidate != SELECT || selected >= 0)) {
            action = candidate;
            selection = selected;
        }
    }

    public static final class Handler implements IMessageHandler<PacketTerminalBaubleAction, IMessage> {

        public IMessage onMessage(PacketTerminalBaubleAction message, MessageContext context) {
            TerminalBaubles.enqueue(context.getServerHandler().playerEntity, message.action, message.selection);
            return null;
        }
    }
}
