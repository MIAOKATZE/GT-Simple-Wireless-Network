package com.miaokatze.gtswn.network;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;

import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.device.DeviceScanManager;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.device.DeviceTerminalRequestQueue;
import com.miaokatze.gtswn.common.device.DeviceTerminalTeleport;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;
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
        final PacketEngineeringDeviceAction factory;

        Pending(EntityPlayerMP player, PacketDeviceTerminalVisualAction message) {
            playerId = player.getUniqueID();
            playerName = player.getCommandSenderName();
            this.message = message;
            factory = null;
        }

        Pending(EntityPlayerMP player, PacketEngineeringDeviceAction factory) {
            playerId = player.getUniqueID();
            playerName = player.getCommandSenderName();
            message = PacketDeviceTerminalVisualAction.toggle(factory.terminalId);
            this.factory = factory;
        }
    }

    private static final class Session {

        final UUID terminalId;
        final String terminalToken;
        final String playerName;
        int dimension;
        int slot;
        int pendingAcks = 2;
        int lastAckTick = Integer.MIN_VALUE;

        Session(EntityPlayerMP player, UUID id, int inventorySlot, long now) {
            terminalId = id;
            terminalToken = id.toString();
            playerName = player.getCommandSenderName();
            dimension = player.dimension;
            slot = inventorySlot;
        }
    }

    private static final class Rate {

        final long[] actions = { Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE };
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
        offer(key, action);
    }

    public static void enqueue(EntityPlayerMP player, PacketEngineeringDeviceAction message) {
        if (player == null || message == null || !message.valid()) return;
        Pending action = new Pending(player, message);
        // A player has only two factory intents, even when stale UUIDs are spammed.
        offer(action.playerId + ":factory:" + message.action, action);
    }

    private static void offer(String key, Pending action) {
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

    /** Cached-slot checks stop lost terminals immediately; restoration/selection scans run once a second. */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.worldObj.isRemote
            || !(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        Session session = SESSIONS.get(player.getUniqueID());
        if (session != null && !validInventory(player, session)) {
            SESSIONS.remove(player.getUniqueID());
            if (player.playerNetServerHandler != null) acknowledge(player, session.terminalId, false);
        }
        if (!player.isDead && player.getHealth() > 0 && player.playerNetServerHandler != null) {
            session = SESSIONS.get(player.getUniqueID());
            if (session != null && session.dimension != player.dimension) {
                session.dimension = player.dimension;
                session.pendingAcks = 2;
                acknowledge(player, session.terminalId, true);
                DeviceTerminalRequestQueue.invalidate(player);
            }
            if (player.ticksExisted % 20 == 0) {
                selectSession(player, player.worldObj.getTotalWorldTime());
                session = SESSIONS.get(player.getUniqueID());
                // Bounded retries cover login/dimension packet ordering without continuous mode heartbeats.
                if (session != null && session.pendingAcks > 0 && session.lastAckTick != player.ticksExisted) {
                    session.pendingAcks--;
                    acknowledge(player, session.terminalId, true);
                }
            }
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
                if (pending.factory == null) apply(player, pending.message, now);
                else applyEngineering(player, pending.factory, now);
            } catch (RuntimeException invalid) {
                long clock = System.nanoTime();
                if (lastErrorNanos == 0 || clock - lastErrorNanos >= 30_000_000_000L) {
                    lastErrorNanos = clock;
                    GTSimpleWirelessNetwork.LOG.error(
                        "[DeviceVisual] action failed for " + pending.playerId + " target " + pending.message.key,
                        invalid);
                }
                if (pending.factory != null)
                    player.addChatMessage(new ChatComponentTranslation("gtswn.engineering.device.unavailable"));
                if (pending.message.action == PacketDeviceTerminalVisualAction.ACTION_VISUAL_TELEPORT) report(
                    player,
                    pending.message.terminalId,
                    pending.message.key,
                    new DeviceTerminalTeleport.Outcome(DeviceTerminalTeleport.Status.ERROR, 0));
            }
        }
    }

    private static void applyEngineering(EntityPlayerMP player, PacketEngineeringDeviceAction message, long now) {
        Source source = EngineeringTerminalSources.resolve(player, 2);
        if (player.isDead || player.getHealth() <= 0 || !message.matches(source)) {
            player.addChatMessage(new ChatComponentTranslation("gtswn.engineering.device.source_unavailable"));
            acknowledge(player, message.terminalId, isEnabled(player, message.terminalId));
            return;
        }
        if (message.action == PacketEngineeringDeviceAction.SCAN) {
            if (!rateAllowed(player.getUniqueID(), (byte) 3, now)) {
                player.addChatMessage(new ChatComponentTranslation("gtswn.engineering.device.unavailable"));
                return;
            }
            DeviceTerminalDataStore.ensureActiveTerminal(player.getUniqueID(), message.terminalId);
            DeviceScanManager.startScan(player, message.terminalId);
        } else apply(player, PacketDeviceTerminalVisualAction.toggle(message.terminalId), now, source);
    }

    private static void apply(EntityPlayerMP player, PacketDeviceTerminalVisualAction message, long now) {
        apply(player, message, now, null);
    }

    private static void apply(EntityPlayerMP player, PacketDeviceTerminalVisualAction message, long now,
        Source source) {
        if (!message.valid()) return;
        if (player.isDead || player.getHealth() <= 0) {
            rejectTravel(player, message, DeviceTerminalTeleport.Status.UNAVAILABLE_PLAYER);
            return;
        }
        ItemStack held = source == null ? player.getHeldItem() : source.stack;
        // The device list can be opened directly from a carried slot. Only visualization toggles
        // accept that carried identity; teleport/rename retain the existing held-terminal checks.
        boolean accessory = source != null && source.origin == SourceOrigin.BAUBLE;
        int targetSlot = player.inventory.currentItem;
        if (source != null && !accessory) {
            targetSlot = -1;
            for (int i = 0; i < player.inventory.mainInventory.length; i++)
                if (player.inventory.mainInventory[i] == held) {
                    targetSlot = i;
                    break;
                }
            if (targetSlot < 0) return;
        }
        if (source == null && message.action == PacketDeviceTerminalVisualAction.ACTION_TOGGLE
            && !PacketDeviceTerminalVisualAction.NEW_TERMINAL.equals(message.terminalId)
            && !message.terminalId.equals(ItemDeviceInfoTerminal.readTerminalId(held))) {
            IInventory baubles = TerminalBaubles.inventory(player);
            if (baubles != null) for (int i = 0; i < baubles.getSizeInventory(); i++) {
                ItemStack candidate = baubles.getStackInSlot(i);
                if (candidate != null && candidate.getItem() instanceof ItemDeviceInfoTerminal
                    && message.terminalId.equals(ItemDeviceInfoTerminal.readTerminalId(candidate))) {
                    held = candidate;
                    accessory = true;
                    break;
                }
            }
            if (!accessory) for (int i = 0; i < player.inventory.mainInventory.length; i++) {
                ItemStack candidate = player.inventory.mainInventory[i];
                if (candidate != null && candidate.getItem() instanceof ItemDeviceInfoTerminal
                    && message.terminalId.equals(ItemDeviceInfoTerminal.readTerminalId(candidate))) {
                    held = candidate;
                    targetSlot = i;
                    break;
                }
            }
        }
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
            boolean enabled = !ItemDeviceInfoTerminal.isVisualizationEnabled(held);
            if (enabled && !SESSIONS.containsKey(player.getUniqueID()) && SESSIONS.size() >= MAX_SESSIONS) {
                if (!accessory) player.playerNetServerHandler
                    .sendPacket(new S2FPacketSetSlot(0, targetSlot < 9 ? 36 + targetSlot : targetSlot, held));
                acknowledge(player, actual, false);
                if (source != null)
                    player.addChatMessage(new ChatComponentTranslation("gtswn.engineering.device.unavailable"));
                return;
            }
            ItemDeviceInfoTerminal.setVisualizationEnabled(held, enabled);
            player.inventory.markDirty();
            if (accessory) TerminalBaubles.syncChangedStack(player, held);
            else player.playerNetServerHandler
                .sendPacket(new S2FPacketSetSlot(0, targetSlot < 9 ? 36 + targetSlot : targetSlot, held));
            if (!enabled) {
                Session current = SESSIONS.get(player.getUniqueID());
                if (current != null && current.terminalId.equals(actual)) {
                    SESSIONS.remove(player.getUniqueID());
                    DeviceTerminalRequestQueue.invalidate(player);
                }
                acknowledge(player, actual, false);
            }
            selectSession(player, now);
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
        if (last != Long.MIN_VALUE && now >= last && now - last < (action == 3 ? 20 : 5)) return false;
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
                .equals(player.getUniqueID()) || !validInventory(player, session)) {
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

    private static boolean matches(ItemStack stack, Session session) {
        return ItemDeviceInfoTerminal.isVisualizationEnabled(stack)
            && session.terminalToken.equals(stack.stackTagCompound.getString(ItemDeviceInfoTerminal.NBT_UUID));
    }

    private static boolean validInventory(EntityPlayerMP player, Session session) {
        if (player.isDead || player.getHealth() <= 0) return false;
        ItemStack[] inventory = player.inventory.mainInventory;
        IInventory baubles = TerminalBaubles.inventory(player);
        int baubleSlot = -session.slot - 2;
        if (session.slot < -1 && baubles != null
            && baubleSlot < baubles.getSizeInventory()
            && matches(baubles.getStackInSlot(baubleSlot), session)) return true;
        if (session.slot >= 0 && session.slot < inventory.length && matches(inventory[session.slot], session))
            return true;
        // A moved stack is found once and cached; an actually missing stack removes the session immediately.
        if (baubles != null)
            for (int i = 0; i < baubles.getSizeInventory(); i++) if (matches(baubles.getStackInSlot(i), session)) {
                session.slot = -i - 2;
                return true;
            }
        for (int i = 0; i < inventory.length; i++) if (matches(inventory[i], session)) {
            session.slot = i;
            return true;
        }
        return false;
    }

    private static void selectSession(EntityPlayerMP player, long now) {
        Session current = SESSIONS.get(player.getUniqueID());
        int selected = -1;
        UUID id = null;
        IInventory baubles = TerminalBaubles.inventory(player);
        if (baubles != null) for (int i = 0; i < baubles.getSizeInventory(); i++) {
            ItemStack candidate = baubles.getStackInSlot(i);
            if (ItemDeviceInfoTerminal.isVisualizationEnabled(candidate)
                && (id = ItemDeviceInfoTerminal.readTerminalId(candidate)) != null) {
                selected = -i - 2;
                break;
            }
        }
        if (id == null) for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            ItemStack candidate = player.inventory.mainInventory[i];
            if (ItemDeviceInfoTerminal.isVisualizationEnabled(candidate)
                && (id = ItemDeviceInfoTerminal.readTerminalId(candidate)) != null) {
                selected = i;
                break;
            }
        }
        if (id == null) return;
        if (current != null && current.terminalId.equals(id)) return;
        if (current == null && SESSIONS.size() >= MAX_SESSIONS) return;
        if (current != null) acknowledge(player, current.terminalId, false);
        SESSIONS.put(player.getUniqueID(), new Session(player, id, selected, now));
        DeviceTerminalDataStore.ensureActiveTerminal(player.getUniqueID(), id);
        DeviceTerminalRequestQueue.invalidate(player);
        acknowledge(player, id, true);
    }

    /** Vanilla C08 is never a second travel intent. Only packet24 selects a bound visual target. */
    public static boolean shouldConsumeVanillaUse(EntityPlayerMP player, ItemStack stack) {
        return !player.isSneaking() && ItemDeviceInfoTerminal.isVisualizationEnabled(stack)
            && ItemDeviceInfoTerminal.readTerminalId(stack) != null;
    }

    public static boolean isEnabled(EntityPlayerMP player, UUID id) {
        Session session = SESSIONS.get(player.getUniqueID());
        MinecraftServer server = MinecraftServer.getServer();
        if (session == null || id == null
            || !id.equals(session.terminalId)
            || !validInventory(player, session)
            || server == null
            || server.worldServerForDimension(0) == null
            || player.playerNetServerHandler == null
            || server.getConfigurationManager()
                .func_152612_a(player.getCommandSenderName()) != player)
            return false;
        return true;
    }

    /** Retained for data callers; an enabled inventory terminal now owns the persistent session. */
    public static void refresh(EntityPlayerMP player, UUID id) {
        // An enabled held terminal can request an immediate selection ACK through the existing data protocol.
        ItemStack held = player.getHeldItem();
        if (id != null && ItemDeviceInfoTerminal.isVisualizationEnabled(held)
            && id.equals(ItemDeviceInfoTerminal.readTerminalId(held)))
            selectSession(player, player.worldObj.getTotalWorldTime());
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
        Session session = SESSIONS.get(player.getUniqueID());
        if (enabled && session != null && session.terminalId.equals(id)) session.lastAckTick = player.ticksExisted;
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
