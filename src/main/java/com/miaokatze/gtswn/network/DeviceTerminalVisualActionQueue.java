package com.miaokatze.gtswn.network;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.server.MinecraftServer;

import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.device.DeviceTerminalRequestQueue;
import com.miaokatze.gtswn.common.device.DeviceTerminalTeleport;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Server-authorized visual sessions. Network intent is bounded, coalesced and applied only on the server thread. */
public final class DeviceTerminalVisualActionQueue {

    public static final int MAX_PENDING = 1024;
    public static final int MAX_PER_PLAYER = 16;
    public static final int MAX_SESSIONS = 512;
    public static final long SESSION_TTL_TICKS = 200;
    private static final LinkedHashMap<String, Pending> PENDING = new LinkedHashMap<>();
    private static final Map<UUID, Integer> COUNTS = new HashMap<>();
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Rate> RATES = new HashMap<>();
    private static long lastErrorNanos;

    private static final class Pending {

        final UUID playerId;
        final String playerName;
        final PacketDeviceTerminalVisualAction message;

        Pending(EntityPlayerMP player, PacketDeviceTerminalVisualAction message) {
            playerId = player.getUniqueID();
            playerName = player.getCommandSenderName();
            this.message = message;
        }
    }

    private static final class Session {

        final UUID terminalId;
        final String playerName;
        final int dimension;
        final int slot;
        long expires;

        Session(EntityPlayerMP player, UUID id, long now) {
            terminalId = id;
            playerName = player.getCommandSenderName();
            dimension = player.dimension;
            slot = player.inventory.currentItem;
            expires = now + SESSION_TTL_TICKS;
        }
    }

    private static final class Rate {

        final long[] actions = { Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE };
        long expires;
    }

    private DeviceTerminalVisualActionQueue() {}

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new DeviceTerminalVisualActionQueue());
    }

    public static void enqueue(EntityPlayerMP player, PacketDeviceTerminalVisualAction message) {
        if (player == null || message == null || !message.valid()) return;
        Pending action = new Pending(player, message);
        String key = action.playerId + ":" + message.terminalId + ":" + message.action + ":" + message.key;
        synchronized (PENDING) {
            int count = COUNTS.getOrDefault(action.playerId, 0);
            if (!PENDING.containsKey(key)) {
                if (PENDING.size() >= MAX_PENDING || count >= MAX_PER_PLAYER) return;
                COUNTS.put(action.playerId, count + 1);
            }
            PENDING.put(key, action);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        drain();
    }

    /** Per-player O(1) lifecycle check keeps hand changes immediate without scanning the whole session table. */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.worldObj.isRemote
            || !(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        Session session = SESSIONS.get(player.getUniqueID());
        if (session != null && !validHeld(player, session)) {
            SESSIONS.remove(player.getUniqueID());
            if (player.playerNetServerHandler != null) acknowledge(player, session.terminalId, false);
        }
    }

    public static void drain() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.worldServerForDimension(0) == null) return;
        long now = server.worldServerForDimension(0)
            .getTotalWorldTime();
        if (now % 20 == 0) maintain(server, now);
        for (int i = 0; i < 64; i++) {
            Pending pending;
            synchronized (PENDING) {
                Iterator<Pending> it = PENDING.values()
                    .iterator();
                if (!it.hasNext()) break;
                pending = it.next();
                it.remove();
                int count = COUNTS.getOrDefault(pending.playerId, 1) - 1;
                if (count <= 0) COUNTS.remove(pending.playerId);
                else COUNTS.put(pending.playerId, count);
            }
            EntityPlayerMP player = server.getConfigurationManager()
                .func_152612_a(pending.playerName);
            if (player == null || !pending.playerId.equals(player.getUniqueID())
                || player.playerNetServerHandler == null) continue;
            try {
                apply(player, pending.message, now);
            } catch (RuntimeException invalid) {
                long clock = System.nanoTime();
                if (lastErrorNanos == 0 || clock - lastErrorNanos >= 30_000_000_000L) {
                    lastErrorNanos = clock;
                    GTSimpleWirelessNetwork.LOG.error(
                        "[DeviceVisual] action failed for " + pending.playerId + " target " + pending.message.key,
                        invalid);
                }
                if (pending.message.action == PacketDeviceTerminalVisualAction.ACTION_VISUAL_TELEPORT) report(
                    player,
                    pending.message.terminalId,
                    pending.message.key,
                    new DeviceTerminalTeleport.Outcome(DeviceTerminalTeleport.Status.ERROR, 0));
            }
        }
    }

    private static void apply(EntityPlayerMP player, PacketDeviceTerminalVisualAction message, long now) {
        if (!message.valid()) return;
        if (player.isDead || player.getHealth() <= 0) {
            rejectTravel(player, message, DeviceTerminalTeleport.Status.UNAVAILABLE_PLAYER);
            return;
        }
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemDeviceInfoTerminal)) {
            rejectTravel(player, message, DeviceTerminalTeleport.Status.INACTIVE);
            return;
        }
        UUID actual = ItemDeviceInfoTerminal.readTerminalId(held);
        if (message.action == PacketDeviceTerminalVisualAction.ACTION_TOGGLE
            && PacketDeviceTerminalVisualAction.NEW_TERMINAL.equals(message.terminalId)) {
            if (actual != null) return;
            if (actual == null) {
                if (held.stackTagCompound != null) held.stackTagCompound.removeTag(ItemDeviceInfoTerminal.NBT_UUID);
                actual = ItemDeviceInfoTerminal.getOrCreateTerminalId(held);
                player.playerNetServerHandler
                    .sendPacket(new S2FPacketSetSlot(0, 36 + player.inventory.currentItem, held));
            }
        } else if (actual == null || !actual.equals(message.terminalId)) {
            rejectTravel(player, message, DeviceTerminalTeleport.Status.INVALID_TARGET);
            return;
        }
        if (actual == null) return;
        if (!rateAllowed(player.getUniqueID(), message.action, now)) {
            if (message.action == PacketDeviceTerminalVisualAction.ACTION_VISUAL_TELEPORT) report(
                player,
                actual,
                message.key,
                new DeviceTerminalTeleport.Outcome(DeviceTerminalTeleport.Status.COOLDOWN, 5));
            else if (message.action == PacketDeviceTerminalVisualAction.ACTION_TOGGLE)
                acknowledge(player, actual, isEnabled(player, actual));
            return;
        }
        DeviceTerminalDataStore.ensureActiveTerminal(player.getUniqueID(), actual);
        if (message.action == PacketDeviceTerminalVisualAction.ACTION_TOGGLE) {
            Session current = SESSIONS.remove(player.getUniqueID());
            if (current != null && current.terminalId.equals(actual)) {
                acknowledge(player, actual, false);
            } else if (SESSIONS.size() < MAX_SESSIONS) {
                SESSIONS.put(player.getUniqueID(), new Session(player, actual, now));
                acknowledge(player, actual, true);
                DeviceTerminalRequestQueue.invalidate(player);
            } else acknowledge(player, actual, false);
        } else if (message.action == PacketDeviceTerminalVisualAction.ACTION_VISUAL_TELEPORT) {
            report(
                player,
                actual,
                message.key,
                DeviceTerminalTeleport.attemptDetailed(player, actual, message.key, true));
        } else {
            DeviceTerminalDataStore store = DeviceTerminalDataStore.get(player.worldObj);
            DeviceTerminalDataStore.TerminalData data = store.getTerminal(actual);
            long before = data == null ? -1 : data.version;
            if (store.renameBinding(actual, message.key, message.name, message.color, message.bold) && data != null
                && data.version != before) DeviceTerminalRequestQueue.invalidate(player);
        }
    }

    private static boolean rateAllowed(UUID id, byte action, long now) {
        Rate rate = RATES.get(id);
        if (rate == null) {
            if (RATES.size() >= MAX_SESSIONS) return false;
            rate = new Rate();
            RATES.put(id, rate);
        }
        long last = rate.actions[action];
        if (last != Long.MIN_VALUE && now >= last && now - last < 5) return false;
        rate.actions[action] = now;
        rate.expires = now + 1200;
        return true;
    }

    private static void maintain(MinecraftServer server, long now) {
        Iterator<Map.Entry<UUID, Session>> sessions = SESSIONS.entrySet()
            .iterator();
        while (sessions.hasNext()) {
            Map.Entry<UUID, Session> entry = sessions.next();
            Session session = entry.getValue();
            EntityPlayerMP player = server.getConfigurationManager()
                .func_152612_a(session.playerName);
            if (player == null || !entry.getKey()
                .equals(player.getUniqueID()) || !validHeld(player, session) || now > session.expires) {
                sessions.remove();
                if (player != null && entry.getKey()
                    .equals(player.getUniqueID()) && player.playerNetServerHandler != null)
                    acknowledge(player, session.terminalId, false);
            }
        }
        if (now % 20 == 0) {
            Iterator<Rate> rates = RATES.values()
                .iterator();
            while (rates.hasNext()) if (rates.next().expires < now) rates.remove();
        }
    }

    private static boolean validHeld(EntityPlayerMP player, Session session) {
        ItemStack held = player.getHeldItem();
        return !player.isDead && player.getHealth() > 0
            && player.dimension == session.dimension
            && player.inventory.currentItem == session.slot
            && held != null
            && held.getItem() instanceof ItemDeviceInfoTerminal
            && session.terminalId.equals(ItemDeviceInfoTerminal.readTerminalId(held));
    }

    /** Vanilla C08 is never a second travel intent. Only packet24 selects a bound visual target. */
    public static boolean shouldConsumeVanillaUse(EntityPlayerMP player, ItemStack stack) {
        return !player.isSneaking() && stack != null
            && stack.getItem() instanceof ItemDeviceInfoTerminal
            && isEnabled(player, ItemDeviceInfoTerminal.readTerminalId(stack));
    }

    public static boolean isEnabled(EntityPlayerMP player, UUID id) {
        Session session = SESSIONS.get(player.getUniqueID());
        MinecraftServer server = MinecraftServer.getServer();
        if (session == null || id == null
            || !id.equals(session.terminalId)
            || !validHeld(player, session)
            || server == null
            || server.worldServerForDimension(0) == null
            || player.playerNetServerHandler == null
            || server.getConfigurationManager()
                .func_152612_a(player.getCommandSenderName()) != player)
            return false;
        long now = server.worldServerForDimension(0)
            .getTotalWorldTime();
        return now <= session.expires;
    }

    /** Existing data polling refreshes the lease; no new heartbeat packets are introduced. */
    public static void refresh(EntityPlayerMP player, UUID id) {
        if (!isEnabled(player, id)) return;
        Session session = SESSIONS.get(player.getUniqueID());
        session.expires = MinecraftServer.getServer()
            .worldServerForDimension(0)
            .getTotalWorldTime() + SESSION_TTL_TICKS;
    }

    private static void rejectTravel(EntityPlayerMP player, PacketDeviceTerminalVisualAction message,
        DeviceTerminalTeleport.Status status) {
        if (message.action == PacketDeviceTerminalVisualAction.ACTION_VISUAL_TELEPORT)
            report(player, message.terminalId, message.key, new DeviceTerminalTeleport.Outcome(status, 0));
    }

    private static void report(EntityPlayerMP player, UUID terminal, String key,
        DeviceTerminalTeleport.Outcome result) {
        DeviceTerminalTeleport.feedback(player, result);
        GTSWNPacketHandler.NETWORK.sendTo(
            new PacketSyncDeviceTerminalVisualState(
                terminal,
                result.status.code,
                key,
                result.cooldownTicks,
                result.succeeded() ? player.posX : 0,
                result.succeeded() ? player.posY : 0,
                result.succeeded() ? player.posZ : 0),
            player);
    }

    private static void acknowledge(EntityPlayerMP player, UUID id, boolean enabled) {
        GTSWNPacketHandler.NETWORK.sendTo(new PacketSyncDeviceTerminalVisualState(id, enabled), player);
    }

    public static void onLogout(UUID id) {
        SESSIONS.remove(id);
        RATES.remove(id);
        synchronized (PENDING) {
            Iterator<Pending> it = PENDING.values()
                .iterator();
            while (it.hasNext()) if (it.next().playerId.equals(id)) it.remove();
            COUNTS.remove(id);
        }
    }

    public static void clear() {
        SESSIONS.clear();
        RATES.clear();
        synchronized (PENDING) {
            PENDING.clear();
            COUNTS.clear();
        }
    }
}
