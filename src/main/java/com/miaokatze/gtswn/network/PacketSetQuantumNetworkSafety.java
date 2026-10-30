package com.miaokatze.gtswn.network;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalRequestQueue;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** C2S safety intent. Only the server tick may resolve the held terminal or change saved data. */
public final class PacketSetQuantumNetworkSafety implements IMessage {

    private static final AtomicLong NEXT_TOKEN = new AtomicLong();
    private static final long CONTEXT_TTL_NANOS = 30_000_000_000L;
    /** Accessed exclusively on the server thread; bounded even when players disconnect. */
    private static final Map<UUID, Context> CONTEXTS = new LinkedHashMap<>();
    private static final Queue QUEUE = new Queue();
    private int slot = -1;
    private int[] anchor;
    private String identity = "";
    private long token;
    private boolean enabled;
    private boolean valid;

    public PacketSetQuantumNetworkSafety() {}

    public PacketSetQuantumNetworkSafety(int slot, QuantumNetworkData snapshot, boolean enabled) {
        this.slot = slot;
        anchor = new int[] { snapshot.anchorDim, snapshot.anchorX, snapshot.anchorY, snapshot.anchorZ };
        identity = snapshot.networkSafetyIdentity;
        token = snapshot.networkSafetyRequestToken;
        this.enabled = enabled;
        valid = slot >= 0 && slot < 9 && token != 0 && !identity.isEmpty() && identity.length() <= 64;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeByte(slot);
        for (int coordinate : anchor) buffer.writeInt(coordinate);
        ByteBufUtils.writeUTF8String(buffer, identity);
        buffer.writeLong(token);
        buffer.writeBoolean(enabled);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        valid = false;
        if (buffer.readableBytes() < 27 || buffer.readableBytes() > 94) return;
        slot = buffer.readUnsignedByte();
        anchor = new int[] { buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt() };
        identity = ByteBufUtils.readUTF8String(buffer);
        if (buffer.readableBytes() != 9 || identity.isEmpty() || identity.length() > 64) return;
        token = buffer.readLong();
        int mode = buffer.readUnsignedByte();
        enabled = mode == 1;
        valid = slot < 9 && token != 0 && mode <= 1;
    }

    /** Bind a stable response token to this exact server stack, slot, key, anchor and controller generation. */
    public static long issueToken(EntityPlayerMP player, ItemStack stack, QuantumNetworkData data, boolean allowed) {
        UUID id = player.getUniqueID();
        if (!allowed || stack == null || data.networkSafetyIdentity.isEmpty()) {
            CONTEXTS.remove(id);
            return 0L;
        }
        Context context = CONTEXTS.get(id);
        if (context != null && context.matches(player, stack, data.networkSafetyIdentity)) {
            context.lastSeenNanos = System.nanoTime();
            return context.token;
        }
        if (CONTEXTS.size() >= 256) CONTEXTS.remove(
            CONTEXTS.keySet()
                .iterator()
                .next());
        context = new Context(player, stack, data.networkSafetyIdentity, NEXT_TOKEN.incrementAndGet());
        CONTEXTS.put(id, context);
        return context.token;
    }

    public static final class Handler implements IMessageHandler<PacketSetQuantumNetworkSafety, IMessage> {

        @Override
        public IMessage onMessage(PacketSetQuantumNetworkSafety message, MessageContext context) {
            if (context.side.isServer() && message.valid) {
                EntityPlayerMP player = context.getServerHandler().playerEntity;
                if (player != null) QUEUE.enqueue(player, message);
            }
            return null;
        }
    }

    public static final class Drain {

        @SubscribeEvent
        public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            CONTEXTS.values()
                .removeIf(context -> context.player == event.player);
            if (event.player instanceof EntityPlayerMP) QUEUE.discardPlayer((EntityPlayerMP) event.player);
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) QUEUE.drain();
        }
    }

    public static void clearContexts() {
        CONTEXTS.clear();
        QUEUE.clear();
    }

    private static final class Queue extends PlayerRequestQueue<Job> {

        void drain() {
            drainAtMost(8);
        }

        void clear() {
            clearPending();
        }

        void enqueue(EntityPlayerMP player, PacketSetQuantumNetworkSafety message) {
            offer(player, new Job(player, message));
        }

        @Override
        protected EntityPlayerMP playerOf(Job job) {
            return job.player;
        }

        @Override
        protected void process(Job job) {
            EntityPlayerMP player = job.player;
            if (!isOnline(player)) return;
            try {
                PacketSetQuantumNetworkSafety message = job.message;
                ItemStack held = player.getHeldItem();
                Context context = CONTEXTS.get(player.getUniqueID());
                if (context == null || context.token != message.token
                    || context.slot != message.slot
                    || !Arrays.equals(context.anchor, message.anchor)
                    || !context.matches(player, held, message.identity)) return;
                World world = DimensionManager.getWorld(message.anchor[0]);
                if (world == null || !message.identity.equals(
                    QuantumTerminalAccess
                        .networkSafetyIdentity(world, message.anchor[1], message.anchor[2], message.anchor[3])))
                    return;
                // This API independently rechecks global mode, native actor permissions and a live quantized anchor.
                QuantumTerminalAccess.setNetworkSafety(player, held, message.enabled);
            } finally {
                QuantumTerminalRequestQueue.enqueue(player);
            }
        }
    }

    private static final class Job {

        final EntityPlayerMP player;
        final PacketSetQuantumNetworkSafety message;

        Job(EntityPlayerMP player, PacketSetQuantumNetworkSafety message) {
            this.player = player;
            this.message = message;
        }
    }

    private static final class Context {

        final EntityPlayerMP player;
        final ItemStack stack;
        final int slot;
        final int[] anchor;
        final String key, identity;
        final long token;
        long lastSeenNanos = System.nanoTime();

        Context(EntityPlayerMP player, ItemStack stack, String identity, long token) {
            this.player = player;
            this.stack = stack;
            slot = player.inventory.currentItem;
            anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
            key = stack.getTagCompound()
                .getString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY);
            this.identity = identity;
            this.token = token;
        }

        boolean matches(EntityPlayerMP currentPlayer, ItemStack held, String currentIdentity) {
            return System.nanoTime() - lastSeenNanos < CONTEXT_TTL_NANOS && player == currentPlayer
                && stack == held
                && slot == currentPlayer.inventory.currentItem
                && held != null
                && held.getTagCompound() != null
                && Arrays.equals(anchor, ItemNetworkQuantumTerminal.getAnchor(held))
                && key.equals(
                    held.getTagCompound()
                        .getString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY))
                && identity.equals(currentIdentity);
        }
    }
}
