package com.miaokatze.gtswn.network;

import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class PacketTerminalBaubleState implements IMessage {

    public static final int OPEN_MONITOR = 0, OPEN_DEVICE = 1, EQUIPPED_HINT = 2, SESSION = 3;
    public int action = -1, selection, heldSlot = -1;
    public ItemStack stack;

    public PacketTerminalBaubleState() {}

    public PacketTerminalBaubleState(int action, int selection, int heldSlot, ItemStack stack) {
        this.action = action;
        this.selection = selection;
        this.heldSlot = heldSlot;
        this.stack = stack == null ? null : stack.copy();
    }

    public void toBytes(ByteBuf buffer) {
        buffer.writeByte(action);
        buffer.writeByte(selection);
        buffer.writeByte(heldSlot);
        ByteBufUtils.writeItemStack(buffer, stack);
    }

    public void fromBytes(ByteBuf buffer) {
        action = -1;
        stack = null;
        try {
            if (buffer.readableBytes() < 3) return;
            int candidate = buffer.readUnsignedByte(), selected = buffer.readUnsignedByte(), held = buffer.readByte();
            if (candidate > SESSION || selected >= 8 || held < -1 || held > 8) return;
            ItemStack received = ByteBufUtils.readItemStack(buffer);
            if (candidate <= OPEN_DEVICE && received == null) return;
            selection = selected;
            heldSlot = held;
            stack = received;
            action = candidate;
        } catch (RuntimeException ignored) {
            action = -1;
            stack = null;
        }
    }

    public static final class Handler implements IMessageHandler<PacketTerminalBaubleState, IMessage> {

        public IMessage onMessage(PacketTerminalBaubleState message, MessageContext context) {
            if (message.action >= 0) GTSimpleWirelessNetwork.proxy
                .receiveTerminalBaubleState(message.action, message.selection, message.heldSlot, message.stack);
            return null;
        }
    }
}
