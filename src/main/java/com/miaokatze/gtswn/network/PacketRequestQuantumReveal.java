package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Bounded nearby loaded-chunk scan, only on an explicit terminal request. */
public class PacketRequestQuantumReveal implements IMessage {

    private static final Map<UUID, EntityPlayerMP> PENDING = new ConcurrentHashMap<>();

    @Override
    public void fromBytes(ByteBuf buffer) {}

    @Override
    public void toBytes(ByteBuf buffer) {}

    public static class Handler implements IMessageHandler<PacketRequestQuantumReveal, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestQuantumReveal message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            if (player != null) PENDING.putIfAbsent(player.getUniqueID(), player);
            return null;
        }
    }

    public static class Drain {

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            for (Map.Entry<UUID, EntityPlayerMP> entry : PENDING.entrySet()) {
                if (PENDING.remove(entry.getKey(), entry.getValue())) {
                    try {
                        handle(entry.getValue());
                    } catch (RuntimeException failure) {
                        com.miaokatze.gtswn.main.GTSimpleWirelessNetwork.LOG.error("[量子检索] 请求失败", failure);
                    }
                }
            }
        }
    }

    private static void handle(EntityPlayerMP player) {
        if (player.isDead || player.playerNetServerHandler == null) return;
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemNetworkQuantumTerminal)) return;
        World world = player.worldObj;
        long now = world.getTotalWorldTime();
        long requestTick = net.minecraft.server.MinecraftServer.getServer()
            .getTickCounter();
        String key = "GTSWN_LastQuantumReveal";
        if (player.getEntityData()
            .hasKey(key)
            && requestTick >= player.getEntityData()
                .getLong(key)
            && requestTick - player.getEntityData()
                .getLong(key) < 20)
            return;
        player.getEntityData()
            .setLong(key, requestTick);
        List<PacketSyncNodeReveal.RevealedNode> nodes = new ArrayList<>();
        // A 64-block radius touches at most 9x9 chunk tile maps; no global world/TE sweep.
        int minX = ((int) Math.floor(player.posX) - 64) >> 4, maxX = ((int) Math.floor(player.posX) + 64) >> 4;
        int minZ = ((int) Math.floor(player.posZ) - 64) >> 4, maxZ = ((int) Math.floor(player.posZ) + 64) >> 4;
        for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) {
            if (!world.getChunkProvider()
                .chunkExists(cx, cz)) continue;
            Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
            for (Object object : chunk.chunkTileEntityMap.values()) {
                TileEntity tile = (TileEntity) object;
                if (tile instanceof TileEntityNetworkQuantumNode)
                    add(nodes, player, tile.xCoord, tile.yCoord, tile.zCoord, (byte) 2);
                else if (QuantumIncorporationRegistry.isIncorporated(world, tile.xCoord, tile.yCoord, tile.zCoord)) {
                    add(nodes, player, tile.xCoord, tile.yCoord, tile.zCoord, (byte) 3);
                }
            }
        }
        if (ItemNetworkQuantumTerminal.isBound(held) && held.getTagCompound()
            .getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM) == world.provider.dimensionId) {
            int x = held.getTagCompound()
                .getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X);
            int y = held.getTagCompound()
                .getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y);
            int z = held.getTagCompound()
                .getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z);
            if (world.blockExists(x, y, z) && QuantumControllerRegistry.get(world)
                .isQuantized(x, y, z)) {
                for (long position : QuantumControllerRegistry.floodControllers(world, x, y, z)) {
                    add(
                        nodes,
                        player,
                        QuantumControllerRegistry.unpackX(position),
                        QuantumControllerRegistry.unpackY(position),
                        QuantumControllerRegistry.unpackZ(position),
                        (byte) 4);
                }
            }
        }
        Collections.sort(nodes, Comparator.comparingDouble(n -> player.getDistanceSq(n.x + 0.5, n.y + 0.5, n.z + 0.5)));
        if (nodes.size() > PacketSyncNodeReveal.MAX_NODES) nodes.subList(PacketSyncNodeReveal.MAX_NODES, nodes.size())
            .clear();
        GTSWNPacketHandler.NETWORK
            .sendTo(new PacketSyncNodeReveal(nodes, now, NodeRevealRequestQueue.REVEAL_DURATION_TICKS), player);
        player.addChatMessage(
            new ChatComponentTranslation(
                nodes.isEmpty() ? "gtswn.reveal.scan.empty" : "gtswn.reveal.scan.result",
                nodes.size()));
    }

    private static void add(List<PacketSyncNodeReveal.RevealedNode> nodes, EntityPlayerMP player, int x, int y, int z,
        byte type) {
        if (player.getDistanceSq(x + 0.5, y + 0.5, z + 0.5) <= 4096)
            nodes.add(new PacketSyncNodeReveal.RevealedNode(x, y, z, type));
    }
}
