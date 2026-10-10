package com.miaokatze.gtswn.network;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;

import com.miaokatze.gtswn.common.gui.GTSWNGuiHandler;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.TerminalAirInteraction;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
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
                PENDING.put(player.getUniqueID(), new Request(player, message.action));
            return null;
        }
    }

    public static class Drain {

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
        if (request.action == CANCEL_REVEAL) {
            CHARGES.remove(player.getUniqueID());
            return;
        }
        if (player.isDead || player.playerNetServerHandler == null || !TerminalAirInteraction.isAir(player)) return;
        ItemStack held = player.getHeldItem();
        if (held == null) return;
        if (request.action == SCAN_DEVICES) {
            if (held.getItem() instanceof ItemDeviceInfoTerminal && consumeReveal(player)) {
                ItemDeviceInfoTerminal.startChargedScan(player, held);
            }
            return;
        }
        if (request.action == OPEN_LINK) {
            if (held.getItem() instanceof WirelessEnergyTap && !player.isSneaking()) {
                CHARGES.remove(player.getUniqueID());
                player
                    .openGui(GTSimpleWirelessNetwork.instance, GTSWNGuiHandler.LINK_TERMINAL, player.worldObj, 0, 0, 0);
            }
        } else if (player.isSneaking()
            && (held.getItem() instanceof WirelessEnergyTap || held.getItem() instanceof ItemNetworkQuantumTerminal
                || held.getItem() instanceof ItemDeviceInfoTerminal)) {
                    CHARGES.putIfAbsent(player.getUniqueID(), new Charge(player, held));
                }
    }

    /** 一次性消费蓄力凭据；旧显形包本身不再足以触发显形。 */
    public static boolean consumeReveal(EntityPlayerMP player) {
        Charge charge = CHARGES.remove(player.getUniqueID());
        // 客户端20 tick蓄力，服务器END排水相位允许一tick边界差。
        return charge != null && charge.player == player && charge.valid() && tickNow() - charge.started >= 19;
    }

    private static long tickNow() {
        return MinecraftServer.getServer()
            .getTickCounter();
    }

    private static final class Request {

        private final EntityPlayerMP player;
        private final int action;

        private Request(EntityPlayerMP player, int action) {
            this.player = player;
            this.action = action;
        }
    }

    private static final class Charge {

        private final EntityPlayerMP player;
        private final ItemStack stack;
        private final int slot;
        private final int dimension;
        private final long started;

        private Charge(EntityPlayerMP player, ItemStack stack) {
            this.player = player;
            this.stack = stack;
            slot = player.inventory.currentItem;
            dimension = player.dimension;
            started = tickNow();
        }

        private boolean valid() {
            return !player.isDead && player.playerNetServerHandler != null
                && player.isSneaking()
                && player.dimension == dimension
                && player.inventory.currentItem == slot
                && player.getHeldItem() == stack
                && TerminalAirInteraction.isAir(player);
        }
    }
}
