package com.miaokatze.gtswn.network;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumControllerIdentity;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkMigration;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import appeng.tile.networking.TileController;
import appeng.util.LookDirection;
import appeng.util.Platform;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** All state and world access stay on the server END tick, including cancellation. */
public final class PacketQuantumMigration implements IMessage {

    public static final int REQUIRED_TICKS = 600;
    public static final int BEGIN = 0;
    public static final int HOLD = 1;
    public static final int CANCEL = 2;
    public static final int COMPLETE = 3;
    private static final Map<UUID, Inbox> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Charge> CHARGES = new HashMap<>();
    private static final Map<UUID, Long> LAST_SESSION = new HashMap<>();
    private static final QuantumMigrationAnnouncements ANNOUNCEMENTS = new QuantumMigrationAnnouncements();
    private static long serverTick;
    int action = -1;
    long session;
    int x, y, z;

    public PacketQuantumMigration() {}

    public PacketQuantumMigration(int action, long session, int x, int y, int z) {
        this.action = action;
        this.session = session;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Server stop also runs for integrated worlds, whose static fields otherwise outlive the save. */
    public static void clearServerState() {
        PENDING.clear();
        CHARGES.clear();
        LAST_SESSION.clear();
        ANNOUNCEMENTS.clear();
        serverTick = 0;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        action = -1;
        if (buffer.readableBytes() != 21) return;
        int candidate = buffer.readUnsignedByte();
        session = buffer.readLong();
        x = buffer.readInt();
        y = buffer.readInt();
        z = buffer.readInt();
        if (candidate <= COMPLETE && session > 0) action = candidate;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeByte(action);
        buffer.writeLong(session);
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
    }

    public static class Handler implements IMessageHandler<PacketQuantumMigration, IMessage> {

        @Override
        public IMessage onMessage(PacketQuantumMigration message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            if (player != null && message.action >= BEGIN && message.action <= COMPLETE && message.session > 0) {
                Inbox inbox = PENDING.computeIfAbsent(player.getUniqueID(), ignored -> new Inbox());
                inbox.add(new Request(player, message));
            }
            return null;
        }
    }

    public static class Drain {

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            serverTick++;
            if (serverTick % 20 == 0) ANNOUNCEMENTS.expire(System.nanoTime());
            // Drain intents before validating/committing, so a same-tick CANCEL always wins.
            for (Inbox inbox : PENDING.values()) {
                Request cancellation = inbox.pollCancellation();
                if (cancellation != null) handle(cancellation);
                Request request;
                for (int processed = 0; processed < 64 && (request = inbox.poll()) != null; processed++) {
                    try {
                        handle(request);
                    } catch (RuntimeException failure) {
                        stop(request.player.getUniqueID());
                        result(request.player, request.message.session, PacketQuantumMigrationResult.REJECTED);
                        GTSimpleWirelessNetwork.LOG.error("[Quantum migration] Request failed", failure);
                    }
                }
            }
            Iterator<Map.Entry<UUID, Charge>> iterator = CHARGES.entrySet()
                .iterator();
            while (iterator.hasNext()) {
                Charge charge = iterator.next()
                    .getValue();
                try {
                    if (!charge.progress.validate(serverTick, charge.valid())) {
                        charge.clearUse();
                        iterator.remove();
                        result(charge.player, charge.session, PacketQuantumMigrationResult.CANCELLED);
                    } else if (charge.progress.consume(serverTick)) {
                        // Consume and remove before mutation: failure and duplicate packets cannot replay it.
                        iterator.remove();
                        charge.clearUse();
                        boolean migrated = QuantumNetworkMigration
                            .migrate(charge.player, charge.stack, charge.world, charge.x, charge.y, charge.z);
                        result(
                            charge.player,
                            charge.session,
                            migrated ? PacketQuantumMigrationResult.SUCCESS : PacketQuantumMigrationResult.REJECTED);
                    }
                } catch (RuntimeException failure) {
                    charge.clearUse();
                    // Removal above can precede a backend exception.
                    if (CHARGES.get(charge.player.getUniqueID()) == charge) iterator.remove();
                    result(charge.player, charge.session, PacketQuantumMigrationResult.REJECTED);
                    GTSimpleWirelessNetwork.LOG.error("[Quantum migration] Charge failed", failure);
                }
            }
        }

        @SubscribeEvent
        public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            UUID id = event.player.getUniqueID();
            stop(id);
            LAST_SESSION.remove(id);
            PENDING.remove(id);
        }
    }

    private static void handle(Request request) {
        EntityPlayerMP player = request.player;
        PacketQuantumMigration message = request.message;
        UUID id = player.getUniqueID();
        Charge charge = CHARGES.get(id);
        if (message.action == CANCEL) {
            // IDs increase per connection. A cancellation is also a barrier against queued BEGINs.
            Long previous = LAST_SESSION.get(id);
            if (previous == null || message.session > previous) LAST_SESSION.put(id, message.session);
            if (charge != null && charge.player == player && charge.session <= message.session) stop(id);
            return;
        }
        if (message.action == BEGIN) {
            Long previous = LAST_SESSION.get(id);
            if (previous != null && message.session <= previous) return;
            // A new BEGIN cannot replace an active credential or reset its timer.
            if (charge != null) return;
            LAST_SESSION.put(id, message.session);
            ItemStack stack = player.getHeldItem();
            if (!basicValid(player, stack) || !pointing(player, message.x, message.y, message.z)
                || !QuantumNetworkMigration.canStart(player, stack, player.worldObj, message.x, message.y, message.z)) {
                result(player, message.session, PacketQuantumMigrationResult.REJECTED);
                return;
            }
            // canStart synchronizes terminal migrations; snapshot the resulting anchor, never the stale packet state.
            charge = new Charge(player, stack, message);
            player.setItemInUse(stack, stack.getMaxItemUseDuration());
            CHARGES.put(id, charge);
            announceStart(charge);
            return;
        }
        if (charge == null || charge.player != player || charge.session != message.session) return;
        if (charge.x != message.x || charge.y != message.y || charge.z != message.z) {
            stop(id);
        } else if (message.action == HOLD) {
            charge.progress.heartbeat(serverTick);
        } else if (message.action == COMPLETE) {
            charge.progress.complete();
        }
    }

    private static void stop(UUID id) {
        Charge charge = CHARGES.remove(id);
        if (charge != null) {
            charge.clearUse();
            result(charge.player, charge.session, PacketQuantumMigrationResult.CANCELLED);
        }
    }

    private static void announceStart(Charge charge) {
        if (!Config.quantumMigrationStartAnnouncement
            || !ANNOUNCEMENTS.reserve(charge.player.getUniqueID(), System.nanoTime())) return;
        MinecraftServer.getServer()
            .getConfigurationManager()
            .sendChatMsg(
                new ChatComponentTranslation(
                    "gtswn.chat.quantum.migration_started",
                    charge.player.getCommandSenderName(),
                    charge.sourceWorld.provider.getDimensionName(),
                    charge.source[0],
                    charge.source[1],
                    charge.source[2],
                    charge.source[3],
                    charge.world.provider.getDimensionName(),
                    charge.dimension,
                    charge.x,
                    charge.y,
                    charge.z));
    }

    private static void result(EntityPlayerMP player, long session, int result) {
        if (player.playerNetServerHandler != null && player.playerNetServerHandler.netManager.isChannelOpen())
            GTSWNPacketHandler.NETWORK.sendTo(new PacketQuantumMigrationResult(session, result), player);
    }

    private static boolean basicValid(EntityPlayerMP player, ItemStack stack) {
        return player.isEntityAlive() && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager.isChannelOpen()
            && !player.isSneaking()
            && player.openContainer == player.inventoryContainer
            && stack != null
            && stack.stackSize > 0
            && stack.getItem() instanceof ItemNetworkQuantumTerminal
            && ItemNetworkQuantumTerminal.isBound(stack);
    }

    private static boolean pointing(EntityPlayerMP player, int x, int y, int z) {
        if (!player.worldObj.blockExists(x, y, z) || player.getDistanceSq(x + 0.5, y + 0.5, z + 0.5) > 64) return false;
        LookDirection ray = Platform.getPlayerRay(player, (float) (player.posY + player.getEyeHeight()));
        MovingObjectPosition hit = player.worldObj.rayTraceBlocks(ray.getA(), ray.getB(), false);
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
            && hit.blockX == x
            && hit.blockY == y
            && hit.blockZ == z;
    }

    private static final class Charge {

        final EntityPlayerMP player;
        final ItemStack stack;
        final World world;
        final TileEntity target;
        final int slot, dimension, x, y, z;
        final int[] source;
        final World sourceWorld;
        final TileEntity sourceController;
        final String sourceIdentity;
        final long session;
        final QuantumMigrationSession progress = new QuantumMigrationSession(serverTick);

        Charge(EntityPlayerMP player, ItemStack stack, PacketQuantumMigration message) {
            this.player = player;
            this.stack = stack;
            world = player.worldObj;
            x = message.x;
            y = message.y;
            z = message.z;
            session = message.session;
            target = world.getTileEntity(x, y, z);
            slot = player.inventory.currentItem;
            dimension = player.dimension;
            source = ItemNetworkQuantumTerminal.getAnchor(stack);
            sourceWorld = DimensionManager.getWorld(source[0]);
            sourceController = sourceWorld != null && sourceWorld.blockExists(source[1], source[2], source[3])
                ? sourceWorld.getTileEntity(source[1], source[2], source[3])
                : null;
            sourceIdentity = sourceController instanceof QuantumControllerIdentity
                ? ((QuantumControllerIdentity) sourceController).gtswn$getControllerIdentity()
                : "";
        }

        boolean valid() {
            return basicValid(player, stack) && player.worldObj == world
                && player.dimension == dimension
                && player.inventory.currentItem == slot
                && player.getHeldItem() == stack
                // Native player updates clear use when the held stack changes. getItemInUse is client-only.
                && player.isUsingItem()
                && target instanceof TileController
                && pointing(player, x, y, z)
                && world.getTileEntity(x, y, z) == target
                // Permission loss ends this credential immediately. Synchronization can also change its anchor.
                && QuantumTerminalAccess.canManage(player, stack)
                && Arrays.equals(source, ItemNetworkQuantumTerminal.getAnchor(stack))
                && sourceCurrent();
        }

        boolean sourceCurrent() {
            return sourceWorld != null && DimensionManager.getWorld(source[0]) == sourceWorld
                && sourceWorld.blockExists(source[1], source[2], source[3])
                && sourceWorld.getTileEntity(source[1], source[2], source[3]) == sourceController
                && sourceController instanceof TileController
                && !sourceIdentity.isEmpty()
                && sourceIdentity.equals(((QuantumControllerIdentity) sourceController).gtswn$getControllerIdentity())
                && QuantumControllerRegistry.get(sourceWorld)
                    .isQuantized(source[1], source[2], source[3]);
        }

        void clearUse() {
            if (player.worldObj == world && player.dimension == dimension
                && player.inventory.currentItem == slot
                && player.getHeldItem() == stack
                && player.isUsingItem()) player.clearItemInUse();
        }
    }

    static final class Request {

        final EntityPlayerMP player;
        final PacketQuantumMigration message;

        Request(EntityPlayerMP player, PacketQuantumMigration message) {
            this.player = player;
            this.message = message;
        }
    }

    static final class Inbox {

        private final ConcurrentLinkedQueue<Request> queue = new ConcurrentLinkedQueue<>();
        private Request cancellation;

        synchronized void add(Request request) {
            if (request.message.action == CANCEL) {
                if (cancellation == null || request.message.session > cancellation.message.session)
                    cancellation = request;
                return;
            }
            if (queue.size() < 64) queue.add(request);
        }

        synchronized Request pollCancellation() {
            Request request = cancellation;
            cancellation = null;
            return request;
        }

        synchronized Request poll() {
            return queue.poll();
        }
    }
}
