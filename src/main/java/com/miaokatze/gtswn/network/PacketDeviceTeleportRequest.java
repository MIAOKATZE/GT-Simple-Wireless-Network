package com.miaokatze.gtswn.network;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.device.DeviceTerminalSource;
import com.miaokatze.gtswn.common.device.DeviceTerminalTeleport;
import com.miaokatze.gtswn.common.gui.DeviceTerminalSettingsGuiFactory;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Independent carried-settings / payment-confirmation protocol. Netty only queues the sender's intent. */
public final class PacketDeviceTeleportRequest implements IMessage {

    private static final byte OPEN_SETTINGS = 0, CONFIRM = 1, CANCEL = 2;
    private static volatile Queue queue = new Queue();
    private byte action = -1;
    private UUID terminalId;
    private int sourceSlot;
    private long token;

    public PacketDeviceTeleportRequest() {}

    public static PacketDeviceTeleportRequest openSettings(UUID id, int sourceSlot) {
        PacketDeviceTeleportRequest packet = new PacketDeviceTeleportRequest();
        packet.action = OPEN_SETTINGS;
        packet.terminalId = id;
        packet.sourceSlot = sourceSlot;
        return packet;
    }

    public static PacketDeviceTeleportRequest confirm(long token, boolean accepted) {
        PacketDeviceTeleportRequest packet = new PacketDeviceTeleportRequest();
        packet.action = accepted ? CONFIRM : CANCEL;
        packet.token = token;
        return packet;
    }

    boolean valid() {
        return action == OPEN_SETTINGS ? terminalId != null && DeviceTerminalSource.validSlot(sourceSlot)
            : (action == CONFIRM || action == CANCEL) && token != 0;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeByte(action);
        if (action == OPEN_SETTINGS) {
            buffer.writeLong(terminalId.getMostSignificantBits());
            buffer.writeLong(terminalId.getLeastSignificantBits());
            buffer.writeInt(sourceSlot);
        } else buffer.writeLong(token);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        action = -1;
        if (buffer.readableBytes() != 9 && buffer.readableBytes() != 21) return;
        byte readAction = buffer.readByte();
        if (readAction == OPEN_SETTINGS && buffer.readableBytes() == 20) {
            terminalId = new UUID(buffer.readLong(), buffer.readLong());
            sourceSlot = buffer.readInt();
        } else if ((readAction == CONFIRM || readAction == CANCEL) && buffer.readableBytes() == 8)
            token = buffer.readLong();
        else return;
        action = readAction;
        if (!valid()) action = -1;
    }

    public static final class Handler implements IMessageHandler<PacketDeviceTeleportRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketDeviceTeleportRequest message, MessageContext context) {
            if (context.side.isServer() && message.valid()) {
                EntityPlayerMP player = context.getServerHandler().playerEntity;
                if (player != null) queue.offerRequest(new Job(player, message));
            }
            return null;
        }
    }

    public static final class Drain {

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) queue.drain();
        }

        @SubscribeEvent
        public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.player instanceof EntityPlayerMP player) {
                queue.discardPlayer(player);
                DeviceTerminalTeleport.onLogout(player.getUniqueID());
            }
        }
    }

    public static void clearPending() {
        queue = new Queue();
        DeviceTerminalTeleport.clear();
    }

    private static final class Queue extends PlayerRequestQueue<Job> {

        void offerRequest(Job job) {
            offer(job.player, job);
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
            if (!isOnline(player) || player.worldObj != job.world || player.dimension != job.dimension) return;
            PacketDeviceTeleportRequest message = job.message;
            if (message.action == OPEN_SETTINGS)
                DeviceTerminalSettingsGuiFactory.open(player, message.terminalId, message.sourceSlot);
            else DeviceTerminalTeleport.confirm(player, message.token, message.action == CONFIRM);
        }
    }

    private static final class Job {

        final EntityPlayerMP player;
        final World world;
        final int dimension;
        final PacketDeviceTeleportRequest message;

        Job(EntityPlayerMP player, PacketDeviceTeleportRequest message) {
            this.player = player;
            world = player.worldObj;
            dimension = player.dimension;
            this.message = message;
        }
    }
}
