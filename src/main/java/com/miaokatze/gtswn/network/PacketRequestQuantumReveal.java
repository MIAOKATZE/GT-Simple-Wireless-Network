package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColorRegistry;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.tile.networking.TileController;
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
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(held);
        World anchorWorld = anchor == null ? null : DimensionManager.getWorld(anchor[0]);
        if (anchorWorld == null || !anchorWorld.blockExists(anchor[1], anchor[2], anchor[3])
            || !(anchorWorld.getTileEntity(anchor[1], anchor[2], anchor[3]) instanceof TileController)
            || !QuantumControllerRegistry.get(anchorWorld)
                .isQuantized(anchor[1], anchor[2], anchor[3])) {
            player.addChatMessage(new ChatComponentTranslation("gtswn.reveal.quantum.unbound"));
            return;
        }
        Set<Long> members = QuantumNetworkColorRegistry.get(anchorWorld)
            .structure(anchorWorld, anchor[1], anchor[2], anchor[3]);
        NBTTagCompound state = player.getEntityData();
        String identity = anchor[0] + ":" + anchor[1] + ":" + anchor[2] + ":" + anchor[3];
        long wallTime = System.currentTimeMillis();
        boolean all = wallTime < state.getLong("GTSWN_QuantumRevealExpires")
            && identity.equals(state.getString("GTSWN_QuantumRevealAnchor"))
            && state.getInteger("GTSWN_QuantumRevealDimension") == world.provider.dimensionId;
        List<PacketSyncNodeReveal.RevealedNode> nodes = new ArrayList<>();
        QuantumControllerRegistry controllers = QuantumControllerRegistry.get(world);
        QuantumIncorporationRegistry incorporations = QuantumIncorporationRegistry.get(world);
        // A 64-block radius touches at most 9x9 loaded chunk maps; never load chunks for a scan.
        int minX = ((int) Math.floor(player.posX) - 64) >> 4, maxX = ((int) Math.floor(player.posX) + 64) >> 4;
        int minZ = ((int) Math.floor(player.posZ) - 64) >> 4, maxZ = ((int) Math.floor(player.posZ) + 64) >> 4;
        for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) {
            if (!world.getChunkProvider()
                .chunkExists(cx, cz)) continue;
            Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
            for (Object object : chunk.chunkTileEntityMap.values()) {
                TileEntity tile = (TileEntity) object;
                if (tile instanceof TileEntityNetworkQuantumNode) {
                    TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) tile;
                    if (all || node.hasAnchor() && belongsToNetwork(
                        anchor[0],
                        members,
                        node.getAnchorDim(),
                        node.getAnchorX(),
                        node.getAnchorY(),
                        node.getAnchorZ())) {
                        add(
                            nodes,
                            player,
                            tile.xCoord,
                            tile.yCoord,
                            tile.zCoord,
                            (byte) 2,
                            node.hasAnchor() ? QuantumNetworkColor
                                .resolve(node.getAnchorDim(), node.getAnchorX(), node.getAnchorY(), node.getAnchorZ())
                                : node.getColorIndex());
                    }
                } else
                    if (tile instanceof TileController && controllers.isQuantized(tile.xCoord, tile.yCoord, tile.zCoord)
                        && (all || belongsToNetwork(
                            anchor[0],
                            members,
                            world.provider.dimensionId,
                            tile.xCoord,
                            tile.yCoord,
                            tile.zCoord))) {
                                add(
                                    nodes,
                                    player,
                                    tile.xCoord,
                                    tile.yCoord,
                                    tile.zCoord,
                                    (byte) 4,
                                    QuantumNetworkColorRegistry.get(world)
                                        .color(tile.xCoord, tile.yCoord, tile.zCoord));
                            }
            }
            for (QuantumIncorporationRegistry.Entry entry : incorporations.snapshotChunk(cx, cz)) {
                int x = QuantumControllerRegistry.unpackX(entry.position);
                int y = QuantumControllerRegistry.unpackY(entry.position);
                int z = QuantumControllerRegistry.unpackZ(entry.position);
                if (QuantumIncorporationRegistry.isIncorporated(world, x, y, z)
                    && (all || belongsToNetwork(anchor[0], members, entry.dim, entry.ax, entry.ay, entry.az))) {
                    add(nodes, player, x, y, z, (byte) 3, entry.getColorIndex());
                }
            }
        }
        Collections.sort(nodes, Comparator.comparingDouble(n -> player.getDistanceSq(n.x + 0.5, n.y + 0.5, n.z + 0.5)));
        if (nodes.size() > PacketSyncNodeReveal.MAX_NODES) nodes.subList(PacketSyncNodeReveal.MAX_NODES, nodes.size())
            .clear();
        GTSWNPacketHandler.NETWORK
            .sendTo(new PacketSyncNodeReveal(nodes, now, NodeRevealRequestQueue.REVEAL_DURATION_TICKS), player);
        state.setLong("GTSWN_QuantumRevealExpires", wallTime + NodeRevealRequestQueue.REVEAL_DURATION_TICKS * 50L);
        state.setString("GTSWN_QuantumRevealAnchor", identity);
        state.setInteger("GTSWN_QuantumRevealDimension", world.provider.dimensionId);
        player.addChatMessage(
            new ChatComponentTranslation(
                all ? "gtswn.reveal.quantum.all" : "gtswn.reveal.quantum.configured",
                nodes.size()));
    }

    /** Controller structure membership, independent of palette and choice of binding controller. */
    public static boolean belongsToNetwork(int anchorDimension, Set<Long> members, int dimension, int x, int y, int z) {
        return anchorDimension == dimension && members.contains(QuantumControllerRegistry.pack(x, y, z));
    }

    private static void add(List<PacketSyncNodeReveal.RevealedNode> nodes, EntityPlayerMP player, int x, int y, int z,
        byte type, int colorIndex) {
        if (player.getDistanceSq(x + 0.5, y + 0.5, z + 0.5) <= 4096)
            nodes.add(new PacketSyncNodeReveal.RevealedNode(x, y, z, type, colorIndex));
    }
}
