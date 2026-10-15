package com.miaokatze.gtswn.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.quantum.QuantumConnectionInfo;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;

import appeng.util.LookDirection;
import appeng.util.Platform;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Empty-hand information requests have no mutation or terminal-action path. */
public final class PacketRequestQuantumInfo implements IMessage {

    private static final Map<UUID, Job> PENDING = new ConcurrentHashMap<>();
    private int x, y, z;
    boolean valid;

    public PacketRequestQuantumInfo() {}

    public PacketRequestQuantumInfo(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        valid = true;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        valid = false;
        if (buffer.readableBytes() != 12) return;
        x = buffer.readInt();
        y = buffer.readInt();
        z = buffer.readInt();
        valid = true;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
    }

    public static final class Handler implements IMessageHandler<PacketRequestQuantumInfo, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestQuantumInfo message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            if (message.valid && connected(player)) PENDING.putIfAbsent(player.getUniqueID(), new Job(player, message));
            return null;
        }
    }

    public static final class Drain {

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            for (Map.Entry<UUID, Job> pending : PENDING.entrySet()) {
                if (!PENDING.remove(pending.getKey(), pending.getValue())) continue;
                try {
                    handle(pending.getValue());
                } catch (RuntimeException failure) {
                    com.miaokatze.gtswn.main.GTSimpleWirelessNetwork.LOG
                        .error("[Quantum information] Request failed", failure);
                }
            }
        }
    }

    private static boolean connected(EntityPlayerMP player) {
        return player != null && player.isEntityAlive()
            && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager.isChannelOpen();
    }

    private static void handle(Job job) {
        EntityPlayerMP player = job.player;
        PacketRequestQuantumInfo message = job.message;
        if (!connected(player) || player.getHeldItem() != null) return;
        World world = player.worldObj;
        if (!world.blockExists(message.x, message.y, message.z)
            || player.getDistanceSq(message.x + 0.5, message.y + 0.5, message.z + 0.5) > 64) return;
        LookDirection look = Platform.getPlayerRay(player, (float) (player.posY + player.getEyeHeight()));
        MovingObjectPosition hit = world.rayTraceBlocks(look.getA(), look.getB(), false);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
            || hit.blockX != message.x
            || hit.blockY != message.y
            || hit.blockZ != message.z) return;
        long now = world.getTotalWorldTime();
        String cooldown = "GTSWN_LastQuantumInfo";
        if (player.getEntityData()
            .hasKey(cooldown)
            && now >= player.getEntityData()
                .getLong(cooldown)
            && now - player.getEntityData()
                .getLong(cooldown) < 5)
            return;
        QuantumIncorporationRegistry.Entry entry = QuantumIncorporationRegistry.get(world)
            .find(world, message.x, message.y, message.z);
        if (entry == null) return;
        player.getEntityData()
            .setLong(cooldown, now);
        QuantumConnectionInfo.incorporation(player, entry, message.x, message.y, message.z);
    }

    private static final class Job {

        final EntityPlayerMP player;
        final PacketRequestQuantumInfo message;

        Job(EntityPlayerMP player, PacketRequestQuantumInfo message) {
            this.player = player;
            this.message = message;
        }
    }
}
