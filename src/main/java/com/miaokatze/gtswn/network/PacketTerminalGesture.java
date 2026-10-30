package com.miaokatze.gtswn.network;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.gui.GTSWNGuiHandler;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.TerminalAirInteraction;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** 客户端手势意图；所有状态验证与容器打开均在服务端主线程执行。 */
public final class PacketTerminalGesture implements IMessage {

    public static final int BEGIN_REVEAL = 0;
    public static final int CANCEL_REVEAL = 1;
    public static final int OPEN_LINK = 2;
    public static final int SCAN_DEVICES = 3;
    private static final Map<UUID, Request> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Charge> CHARGES = new HashMap<>();
    private int action = -1;

    public PacketTerminalGesture() {}

    public PacketTerminalGesture(int action) {
        this.action = action;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        action = buffer.isReadable() ? buffer.readUnsignedByte() : -1;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeByte(action);
    }

    public static class Handler implements IMessageHandler<PacketTerminalGesture, IMessage> {

        @Override
        public IMessage onMessage(PacketTerminalGesture message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            if (player != null && message.action >= BEGIN_REVEAL && message.action <= SCAN_DEVICES)
                enqueue(player, message.action);
            return null;
        }
    }

    static void enqueue(EntityPlayerMP player, int action) {
        PENDING.compute(
            player.getUniqueID(),
            (id, previous) -> new Request(
                player,
                action,
                action == CANCEL_REVEAL || previous != null && previous.player == player && previous.cancelPrevious));
    }

    static boolean cancellationPending(EntityPlayerMP player) {
        Request request = PENDING.get(player.getUniqueID());
        return request != null && request.player == player && request.cancelPrevious;
    }

    /** Pending actions belong to the old server, not the next integrated save. */
    public static void clearServerState() {
        PENDING.clear();
        CHARGES.clear();
    }

    public static class Drain {

        @SubscribeEvent
        public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            PENDING.forEach((id, request) -> { if (request.player == event.player) PENDING.remove(id, request); });
            CHARGES.values()
                .removeIf(charge -> charge.player == event.player);
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Iterator<Charge> iterator = CHARGES.values()
                .iterator();
            while (iterator.hasNext()) {
                Charge charge = iterator.next();
                if (!charge.valid() || tickNow() - charge.started > 60) iterator.remove();
            }
            for (Map.Entry<UUID, Request> entry : PENDING.entrySet()) {
                if (PENDING.remove(entry.getKey(), entry.getValue())) handle(entry.getValue());
            }
        }
    }

    private static void handle(Request request) {
        EntityPlayerMP player = request.player;
        if (!PlayerRequestQueue.isOnline(player)) return;
        if (request.cancelPrevious) CHARGES.remove(player.getUniqueID());
        if (request.action == CANCEL_REVEAL) {
            CHARGES.remove(player.getUniqueID());
            return;
        }
        ItemStack held = player.getHeldItem();
        if (held == null) return;
        if (held.getItem() instanceof ItemNetworkQuantumTerminal && !QuantumTerminalAccess.validate(player, held))
            return;
        boolean device = held.getItem() instanceof ItemDeviceInfoTerminal;
        // Device scans may begin and remain charged while looking at blocks. Other reveal gestures stay air-only.
        if (device ? player.isSneaking() : !TerminalAirInteraction.isAir(player)) return;
        if (request.action == SCAN_DEVICES) {
            if (held.getItem() instanceof ItemDeviceInfoTerminal && consumeReveal(player)) {
                ItemDeviceInfoTerminal.startChargedScan(player, held);
            }
            return;
        }
        if (request.action == OPEN_LINK) {
            if (held.getItem() instanceof WirelessEnergyTap && !player.isSneaking()) {
                CHARGES.remove(player.getUniqueID());
                GTSWNGuiHandler.openTerminal(player, false);
            }
        } else if (held.getItem() instanceof ItemDeviceInfoTerminal || (player.isSneaking()
            && (held.getItem() instanceof WirelessEnergyTap || held.getItem() instanceof ItemNetworkQuantumTerminal))) {
                CHARGES.putIfAbsent(player.getUniqueID(), new Charge(player, held));
            }
    }

    /** 一次性消费蓄力凭据；旧显形包本身不再足以触发显形。 */
    public static boolean consumeReveal(EntityPlayerMP player) {
        // Other reveal queues can drain before this listener. A received cancellation is already a barrier,
        // and cannot be overwritten by a later BEGIN/SCAN in the same pending window. A following BEGIN
        // may start a new charge after the old one is cleared, but cannot reuse its elapsed time.
        if (cancellationPending(player)) {
            CHARGES.remove(player.getUniqueID());
            return false;
        }
        Charge charge = CHARGES.remove(player.getUniqueID());
        // 客户端20 tick蓄力，服务器END排水相位允许一tick边界差。
        return charge != null && charge.player == player && charge.valid() && validRevealAge(tickNow(), charge.started);
    }

    static boolean validRevealAge(long now, long started) {
        return now >= started && now - started >= 19 && now - started <= 60;
    }

    private static long tickNow() {
        return MinecraftServer.getServer()
            .getTickCounter();
    }

    private static final class Request {

        private final EntityPlayerMP player;
        private final int action;
        private final boolean cancelPrevious;

        private Request(EntityPlayerMP player, int action) {
            this(player, action, action == CANCEL_REVEAL);
        }

        private Request(EntityPlayerMP player, int action, boolean cancelPrevious) {
            this.player = player;
            this.action = action;
            this.cancelPrevious = cancelPrevious;
        }
    }

    private static final class Charge {

        private final EntityPlayerMP player;
        private final ItemStack stack;
        private final World world;
        private final int slot;
        private final int dimension;
        private final long started;

        private Charge(EntityPlayerMP player, ItemStack stack) {
            this.player = player;
            this.stack = stack;
            world = player.worldObj;
            slot = player.inventory.currentItem;
            dimension = player.dimension;
            started = tickNow();
        }

        private boolean valid() {
            return PlayerRequestQueue.isOnline(player)
                && (stack.getItem() instanceof ItemDeviceInfoTerminal ? !player.isSneaking() : player.isSneaking())
                && player.dimension == dimension
                && player.worldObj == world
                && player.inventory.currentItem == slot
                && player.getHeldItem() == stack
                && (stack.getItem() instanceof ItemDeviceInfoTerminal || TerminalAirInteraction.isAir(player));
        }
    }
}
