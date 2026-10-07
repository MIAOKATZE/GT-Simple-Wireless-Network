package com.miaokatze.gtswn.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityGrid;
import appeng.tile.networking.TileController;
import appeng.util.LookDirection;
import appeng.util.Platform;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Client intent only; all world access and authorization run on the server tick thread. */
public class PacketQuantumIncorporation implements IMessage {

    private int x, y, z;
    private boolean remove;
    private static final Map<UUID, Job> PENDING = new ConcurrentHashMap<>();

    public PacketQuantumIncorporation() {}

    public PacketQuantumIncorporation(int x, int y, int z, boolean remove) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.remove = remove;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        x = buffer.readInt();
        y = buffer.readInt();
        z = buffer.readInt();
        remove = buffer.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
        buffer.writeBoolean(remove);
    }

    public static class Handler implements IMessageHandler<PacketQuantumIncorporation, IMessage> {

        @Override
        public IMessage onMessage(PacketQuantumIncorporation message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            if (player != null) PENDING.putIfAbsent(player.getUniqueID(), new Job(player, message));
            return null;
        }
    }

    public static class Drain {

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            for (Map.Entry<UUID, Job> pending : PENDING.entrySet()) {
                if (PENDING.remove(pending.getKey(), pending.getValue())) {
                    try {
                        handle(pending.getValue());
                    } catch (RuntimeException failure) {
                        com.miaokatze.gtswn.main.GTSimpleWirelessNetwork.LOG.error("[量子并入] 请求失败", failure);
                    }
                }
            }
        }
    }

    private static void handle(Job job) {
        EntityPlayerMP player = job.player;
        PacketQuantumIncorporation message = job.message;
        if (player.isDead || player.playerNetServerHandler == null) return;
        World world = player.worldObj;
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemNetworkQuantumTerminal)) return;
        if (!world.blockExists(message.x, message.y, message.z)
            || player.getDistanceSq(message.x + 0.5, message.y + 0.5, message.z + 0.5) > 64) return;
        LookDirection look = Platform.getPlayerRay(player, Platform.getEyeOffset(player));
        MovingObjectPosition hit = world.rayTraceBlocks(look.getA(), look.getB(), false);
        if (hit == null || hit.blockX != message.x || hit.blockY != message.y || hit.blockZ != message.z) return;
        if (!world.canMineBlock(player, message.x, message.y, message.z)
            || !player.canPlayerEdit(message.x, message.y, message.z, hit.sideHit, held)) {
            tell(player, "gtswn.chat.quantum.no_permission");
            return;
        }
        QuantumIncorporationRegistry registry = QuantumIncorporationRegistry.get(world);
        if (message.remove) {
            if (registry.remove(world, message.x, message.y, message.z, player))
                tell(player, "gtswn.chat.quantum.incorporation_removed");
            else tell(player, "gtswn.chat.quantum.no_permission");
            return;
        }
        if (!ItemNetworkQuantumTerminal.isBound(held)) {
            tell(player, "gtswn.chat.quantum.need_bind");
            return;
        }
        if (!QuantumIncorporationRegistry.isEligible(world, message.x, message.y, message.z)) return;
        WorldServer anchor = DimensionManager.getWorld(
            held.getTagCompound()
                .getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM));
        int ax = held.getTagCompound()
            .getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X);
        int ay = held.getTagCompound()
            .getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y);
        int az = held.getTagCompound()
            .getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z);
        if (anchor == null || !anchor.blockExists(ax, ay, az)
            || !QuantumControllerRegistry.get(anchor)
                .isQuantized(ax, ay, az)) {
            tell(player, "gtswn.chat.quantum.incorporation_offline");
            return;
        }
        TileEntity controller = anchor.getTileEntity(ax, ay, az);
        TileEntity tile = world.getTileEntity(message.x, message.y, message.z);
        if (!(controller instanceof TileController) || !anchor.canMineBlock(player, ax, ay, az)
            || !canBuild((IGridHost) controller, player)
            || !canBuild((IGridHost) tile, player)) {
            tell(player, "gtswn.chat.quantum.no_permission");
            return;
        }
        if (registry.add(world, message.x, message.y, message.z, held.getTagCompound(), player)) {
            tell(player, "gtswn.chat.quantum.incorporation_added");
        }
    }

    private static boolean canBuild(IGridHost host, EntityPlayerMP player) {
        boolean found = false;
        for (ForgeDirection direction : ForgeDirection.values()) {
            IGridNode node = host.getGridNode(direction);
            if (node == null) continue;
            if (node.getGrid() == null) return false;
            found = true;
            ISecurityGrid security = node.getGrid()
                .getCache(ISecurityGrid.class);
            if (security != null && !security.hasPermission(player, SecurityPermissions.BUILD)) return false;
        }
        return found;
    }

    private static void tell(EntityPlayerMP player, String key) {
        player.addChatMessage(new ChatComponentTranslation(key));
    }

    private static final class Job {

        private final EntityPlayerMP player;
        private final PacketQuantumIncorporation message;

        private Job(EntityPlayerMP player, PacketQuantumIncorporation message) {
            this.player = player;
            this.message = message;
        }
    }
}
