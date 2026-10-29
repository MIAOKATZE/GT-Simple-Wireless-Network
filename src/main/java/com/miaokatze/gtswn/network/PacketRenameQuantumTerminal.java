package com.miaokatze.gtswn.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtswn.common.gui.ContainerTerminal;
import com.miaokatze.gtswn.common.gui.TerminalNameRules;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;

/** C2S item-name intent; Netty never reads or mutates a terminal/container. */
public final class PacketRenameQuantumTerminal implements IMessage {

    private static final int MAX_OLD_NAME_LENGTH = 1024;
    private static volatile Queue queue = new Queue();
    private int window;
    private int slot;
    private long token;
    private String expectedName = "";
    private String name = "";
    private boolean valid;

    public PacketRenameQuantumTerminal() {}

    public PacketRenameQuantumTerminal(int window, int slot, long token, String expectedName, String name) {
        this.window = window;
        this.slot = slot;
        this.token = token;
        this.expectedName = expectedName;
        this.name = name;
        valid = validFields();
    }

    private boolean validFields() {
        return window > 0 && window <= 100
            && slot >= 0
            && slot < 9
            && token != 0
            && expectedName != null
            && expectedName.length() <= MAX_OLD_NAME_LENGTH
            && TerminalNameRules.normalize(name) != null;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeInt(window);
        buffer.writeByte(slot);
        buffer.writeLong(token);
        writeString(buffer, expectedName);
        writeString(buffer, name);
    }

    private static void writeString(ByteBuf buffer, String value) {
        byte[] bytes = value.getBytes(CharsetUtil.UTF_8);
        buffer.writeShort(bytes.length);
        buffer.writeBytes(bytes);
    }

    private static String readString(ByteBuf buffer, int maxLength) {
        if (buffer.readableBytes() < 2) return null;
        int length = buffer.readUnsignedShort();
        if (length > maxLength * 3 || length > buffer.readableBytes()) return null;
        String value = buffer.toString(buffer.readerIndex(), length, CharsetUtil.UTF_8);
        buffer.skipBytes(length);
        return value.length() <= maxLength ? value : null;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        valid = false;
        if (buffer.readableBytes() < 17
            || buffer.readableBytes() > 17 + 3 * (MAX_OLD_NAME_LENGTH + TerminalNameRules.MAX_LENGTH)) return;
        window = buffer.readInt();
        slot = buffer.readUnsignedByte();
        token = buffer.readLong();
        expectedName = readString(buffer, MAX_OLD_NAME_LENGTH);
        if (expectedName == null) return;
        name = readString(buffer, TerminalNameRules.MAX_LENGTH);
        valid = buffer.readableBytes() == 0 && validFields();
    }

    public static final class Handler implements IMessageHandler<PacketRenameQuantumTerminal, IMessage> {

        @Override
        public IMessage onMessage(PacketRenameQuantumTerminal message, MessageContext context) {
            if (context.side.isServer() && message.valid) {
                EntityPlayerMP player = context.getServerHandler().playerEntity;
                if (player != null) queue.enqueue(player, message);
            }
            return null;
        }
    }

    public static final class Drain {

        @SubscribeEvent
        public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.player instanceof EntityPlayerMP) queue.discardPlayer((EntityPlayerMP) event.player);
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) queue.drain();
        }
    }

    /** Drop retained players and pending intents when a server stops. */
    public static void clearPending() {
        queue = new Queue();
    }

    private static final class Queue extends PlayerRequestQueue<Job> {

        void enqueue(EntityPlayerMP player, PacketRenameQuantumTerminal message) {
            offer(player, new Job(player, message));
        }

        void drain() {
            drainAtMost(8);
        }

        @Override
        protected EntityPlayerMP playerOf(Job job) {
            return job.player;
        }

        @Override
        protected void process(Job job) {
            EntityPlayerMP player = job.player;
            if (!isOnline(player) || !(player.openContainer instanceof ContainerTerminal container)) return;
            PacketRenameQuantumTerminal message = job.message;
            container.renameTerminal(
                player,
                message.window,
                message.slot,
                message.token,
                message.expectedName,
                message.name);
        }
    }

    private static final class Job {

        final EntityPlayerMP player;
        final PacketRenameQuantumTerminal message;

        Job(EntityPlayerMP player, PacketRenameQuantumTerminal message) {
            this.player = player;
            this.message = message;
        }
    }
}
