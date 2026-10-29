package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.gui.ContainerTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColorRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColorSync;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;
import com.miaokatze.gtswn.common.util.QuantumTerminalSecurityHints;

import appeng.tile.networking.TileController;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** C2S palette selection. Network identity and permissions are resolved exclusively from the held item. */
public final class PacketSetQuantumNetworkColor implements IMessage {

    private int color = -1;
    private static final int MAX_PENDING_PLAYERS = 256;
    private static final int REQUESTS_PER_TICK = 8;
    private static final Map<UUID, Job> PENDING = new LinkedHashMap<>();

    public PacketSetQuantumNetworkColor() {}

    public PacketSetQuantumNetworkColor(int color) {
        this.color = color;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeByte(color);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        color = buffer.readableBytes() == 1 ? buffer.readUnsignedByte() : -1;
    }

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new Drain());
    }

    public static final class Handler implements IMessageHandler<PacketSetQuantumNetworkColor, IMessage> {

        @Override
        public IMessage onMessage(PacketSetQuantumNetworkColor message, MessageContext context) {
            if (context.side.isServer() && QuantumNetworkColor.isValid(message.color)) {
                EntityPlayerMP player = context.getServerHandler().playerEntity;
                if (player != null && player.openContainer instanceof ContainerTerminal terminal) {
                    UUID id = player.getUniqueID();
                    synchronized (PENDING) {
                        if (PENDING.containsKey(id) || PENDING.size() < MAX_PENDING_PLAYERS) {
                            PENDING.put(id, new Job(player, message.color, terminal));
                        }
                    }
                }
            }
            return null;
        }
    }

    /** Pending actions belong to the old server, not the next integrated save. */
    public static void clearServerState() {
        synchronized (PENDING) {
            PENDING.clear();
        }
    }

    public static final class Drain {

        @SubscribeEvent
        public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            synchronized (PENDING) {
                PENDING.values().removeIf(job -> job.player == event.player);
            }
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            ArrayList<Job> jobs = new ArrayList<>(REQUESTS_PER_TICK);
            synchronized (PENDING) {
                Iterator<Job> iterator = PENDING.values()
                    .iterator();
                while (jobs.size() < REQUESTS_PER_TICK && iterator.hasNext()) {
                    jobs.add(iterator.next());
                    iterator.remove();
                }
            }
            for (Job job : jobs) apply(job);
        }
    }

    private static void apply(Job job) {
        EntityPlayerMP player = job.player;
        if (!PlayerRequestQueue.isOnline(player) || !job.context.matchesColorContext(player)) return;
        ItemStack stack = player.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemNetworkQuantumTerminal)) return;
        if (!QuantumTerminalAccess.canManage(player, stack)) {
            player.addChatMessage(new ChatComponentTranslation("gtswn.chat.quantum.manage_permission_required"));
            return;
        }
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
        if (anchor == null) {
            QuantumNetworkColor.set(stack, job.color);
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
            return;
        }
        WorldServer world = DimensionManager.getWorld(anchor[0]);
        if (world == null || !world.blockExists(anchor[1], anchor[2], anchor[3])) return;
        TileEntity tile = world.getTileEntity(anchor[1], anchor[2], anchor[3]);
        if (!(tile instanceof TileController) || !world.canMineBlock(player, anchor[1], anchor[2], anchor[3])) return;
        QuantumNetworkColorRegistry registry = QuantumNetworkColorRegistry.get(world);
        Set<Long> members = registry.structure(world, anchor[1], anchor[2], anchor[3]);
        if (members.isEmpty()) return;
        boolean wasOpen = QuantumTerminalSecurityHints.isOpen(world, anchor[1], anchor[2], anchor[3]);
        if (registry.color(anchor[1], anchor[2], anchor[3]) != job.color) {
            registry.recolor(members, job.color);
            QuantumNetworkColorSync.changed(anchor[0], members, job.color);
        }
        QuantumNetworkColor.set(stack, job.color);
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
        QuantumTerminalSecurityHints.show(player, wasOpen);
    }

    private static final class Job {

        final EntityPlayerMP player;
        final int color;
        final ContainerTerminal context;

        Job(EntityPlayerMP player, int color, ContainerTerminal context) {
            this.player = player;
            this.color = color;
            this.context = context;
        }
    }
}
