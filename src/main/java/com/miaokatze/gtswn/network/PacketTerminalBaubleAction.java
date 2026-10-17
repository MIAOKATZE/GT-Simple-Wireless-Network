package com.miaokatze.gtswn.network;

import com.miaokatze.gtswn.common.baubles.TerminalBaubles;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class PacketTerminalBaubleAction implements IMessage {

    public static final int EQUIP = 0, SELECT = 1, RETURN = 2, TAKE = 3;
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
        if (isValid(candidate, selected)) {
            action = candidate;
            selection = selected;
        }
    }

    public static boolean isValid(int action, int selection) {
        if (action < EQUIP || action > TAKE) return false;
        if (action == SELECT || action == TAKE) return selection >= 0 && selection < TerminalBaubles.SIZE;
        return selection >= -1 && selection < TerminalBaubles.SIZE;
    }

    public static final class Handler implements IMessageHandler<PacketTerminalBaubleAction, IMessage> {

        public IMessage onMessage(PacketTerminalBaubleAction message, MessageContext context) {
            TerminalBaubles.enqueue(context.getServerHandler().playerEntity, message.action, message.selection);
            return null;
        }
    }
}
