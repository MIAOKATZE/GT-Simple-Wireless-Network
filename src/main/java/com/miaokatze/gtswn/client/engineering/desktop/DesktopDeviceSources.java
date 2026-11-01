package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.screen.ModularScreen;
import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestDeviceTerminalData;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Shared, bounded read demand. Widgets retain references and preferences, never authority or snapshots. */
public final class DesktopDeviceSources {

    private static final Map<UUID, Demand> DEMANDS = new LinkedHashMap<>();
    private static final Set<UUID> VISIBLE = new LinkedHashSet<>();
    private static UUID factorySource, selectionSource, nativeSource, cursor;
    private static EntityPlayer player;
    private static Object world, connection;
    private static int lastRequest = Integer.MIN_VALUE;

    private DesktopDeviceSources() {}

    public static void setVisibleBindings(Collection<DeviceBinding> bindings) {
        VISIBLE.clear();
        if (bindings != null) for (DeviceBinding binding : bindings) {
            if (binding != null) VISIBLE.add(binding.source);
        }
    }

    /** One open selector shares the same bounded polling budget as all visible cards. */
    public static void setSelectionSource(UUID source) {
        selectionSource = source;
    }

    /** The native device window participates in this same facade, while keeping its existing poll owner. */
    public static void nativeViewer(UUID source) {
        UUID previous = nativeSource;
        nativeSource = source;
        if (source != null) tick();
        else if (previous != null && !previous.equals(factorySource)
            && !previous.equals(selectionSource)
            && !VISIBLE.contains(previous)) DEMANDS.remove(previous);
    }

    /** Only terminals currently carried by this local player; baubles appear first. */
    public static List<Source> sources() {
        EntityPlayer owner = Minecraft.getMinecraft().thePlayer;
        if (owner == null || owner.isDead || owner.getHealth() <= 0) return Collections.emptyList();
        Map<UUID, Source> sources = new LinkedHashMap<>();
        IInventory baubles = TerminalBaubles.inventory(owner);
        if (baubles != null) for (int index = 0; index < baubles.getSizeInventory(); index++)
            addSource(sources, baubles.getStackInSlot(index));
        for (ItemStack stack : owner.inventory.mainInventory) addSource(sources, stack);
        return Collections.unmodifiableList(new ArrayList<>(sources.values()));
    }

    private static void addSource(Map<UUID, Source> sources, ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemDeviceInfoTerminal)) return;
        UUID id = ItemDeviceInfoTerminal.readTerminalId(stack);
        if (id != null && !sources.containsKey(id)) sources.put(id, new Source(id, stack.getDisplayName()));
    }

    /** Complete live candidates only. No separate request or per-card copy of machine data. */
    public static List<Entry> choices(UUID source) {
        if (!selectionReason(source).isEmpty()) return Collections.emptyList();
        DeviceTerminalClientCache.Snapshot snapshot = DeviceTerminalClientCache.getSnapshot(source);
        return snapshot == null ? Collections.<Entry>emptyList() : snapshot.entries;
    }

    public static String selectionReason(UUID source) {
        Minecraft mc = Minecraft.getMinecraft();
        if (source == null || player == null
            || player != mc.thePlayer
            || world != mc.theWorld
            || connection != mc.getNetHandler()
            || player.isDead
            || player.getHealth() <= 0) return "source";
        Demand demand = DEMANDS.get(source);
        if (demand == null || !demand.active) return "inactive";
        if (demand.held == null || !demand.held.available(source)) return "source";
        return mc.isGamePaused() || DeviceTerminalClientCache.receiptRevision(source) <= demand.initialReceipt
            || !DeviceTerminalClientCache.isFresh(source, System.currentTimeMillis())
            || DeviceTerminalClientCache.hasMorePending(source) ? "waiting" : "";
    }

    /** The existing factory owner shares its request with cards using the same terminal. */
    public static void tickFactory(UUID source) {
        factorySource = source;
        tick();
    }

    public static void clearFactory() {
        factorySource = null;
        if (VISIBLE.isEmpty() && nativeSource == null) releaseDemands();
    }

    /** Hiding the canvas releases only its extra sources. */
    public static void release() {
        VISIBLE.clear();
        selectionSource = null;
        for (Map.Entry<UUID, Demand> entry : DEMANDS.entrySet()) if (!entry.getKey()
            .equals(factorySource)
            && !entry.getKey()
                .equals(nativeSource))
            entry.getValue().active = false;
        if (factorySource == null && nativeSource == null) releaseDemands();
    }

    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null
            || mc.getNetHandler() == null
            || mc.thePlayer.isDead
            || mc.thePlayer.getHealth() <= 0) {
            reset();
            return;
        }
        if (player != mc.thePlayer || world != mc.theWorld || connection != mc.getNetHandler()) {
            DEMANDS.clear();
            cursor = null;
            lastRequest = Integer.MIN_VALUE;
            player = mc.thePlayer;
            world = mc.theWorld;
            connection = mc.getNetHandler();
        }
        Set<UUID> selected = DesktopDevicePolling
            .sources(factorySource, selectionSource, VISIBLE, id -> find(player, id) != null);
        if (nativeSource != null && find(player, nativeSource) != null) selected.add(nativeSource);
        DEMANDS.keySet()
            .retainAll(selected);
        for (UUID id : selected) {
            Demand demand = DEMANDS.computeIfAbsent(id, Demand::new);
            if (!demand.active) {
                demand.initialReceipt = DeviceTerminalClientCache.receiptRevision(id);
                demand.lastRequest = Integer.MIN_VALUE;
                demand.active = true;
            }
            demand.held = find(player, id);
        }
        if (mc.isGamePaused()) return;
        int now = player.ticksExisted;
        if (lastRequest != Integer.MIN_VALUE && now >= lastRequest && now - lastRequest < 5) return;
        // Resolve MUI's current facade through its real GuiScreen wrapper. No sticky open flag survives closure.
        boolean nativeViewer = nativeSource != null || ModularScreen.isActive("gtswn", "device_info_terminal");
        UUID next = DesktopDevicePolling.next(selected, cursor, nativeViewer, id -> {
            Demand demand = DEMANDS.get(id);
            int interval = DeviceTerminalClientCache.hasMorePending(id) ? 5 : 20;
            return demand.lastRequest == Integer.MIN_VALUE || now < demand.lastRequest
                || now - demand.lastRequest >= interval;
        });
        if (next == null) return;
        Demand demand = DEMANDS.get(next);
        GTSWNPacketHandler.NETWORK.sendToServer(
            demand.lastRequest == Integer.MIN_VALUE ? new PacketRequestDeviceTerminalData(next, -1, false)
                : DeviceTerminalClientCache.requestMetadata(next));
        demand.lastRequest = lastRequest = now;
        cursor = next;
    }

    /** Validate the exact GUI source; never select an inventory fallback. */
    public static DeviceBinding binding(UUID source, Entry entry) {
        Minecraft mc = Minecraft.getMinecraft();
        if (source == null || entry == null
            || !selectionReason(source).isEmpty()
            || find(mc.thePlayer, source) == null
            || !DeviceTerminalClientCache.isFresh(source, System.currentTimeMillis())
            || DeviceTerminalClientCache.hasMorePending(source)) return null;
        DeviceTerminalClientCache.Snapshot snapshot = DeviceTerminalClientCache.getSnapshot(source);
        if (snapshot == null) return null;
        DeviceBinding binding = new DeviceBinding(
            source,
            entry.key,
            entry.dim,
            entry.x,
            entry.y,
            entry.z,
            entry.metaTileId);
        for (Entry current : snapshot.entries) if (matches(binding, current)) return binding;
        return null;
    }

    /** Pure cache read. A changed source/lifecycle must earn a new complete receipt. */
    public static Reading read(DeviceBinding binding) {
        Minecraft mc = Minecraft.getMinecraft();
        if (binding == null || player == null
            || player != mc.thePlayer
            || world != mc.theWorld
            || connection != mc.getNetHandler()
            || player.isDead
            || player.getHealth() <= 0) return new Reading(null, "source");
        Demand demand = DEMANDS.get(binding.source);
        if (demand == null || !demand.active) return new Reading(null, "inactive");
        if (demand.held == null || !demand.held.available(binding.source)) {
            DEMANDS.remove(binding.source);
            return new Reading(null, "source");
        }
        updateDisplay(binding.source, demand);
        boolean currentReceipt = DeviceTerminalClientCache.receiptRevision(binding.source) > demand.initialReceipt;
        boolean pending = DeviceTerminalClientCache.hasMorePending(binding.source);
        Entry entry = demand.entries.get(binding.key);
        boolean live = currentReceipt && !pending
            && !mc.isGamePaused()
            && DeviceTerminalClientCache.isFresh(binding.source, System.currentTimeMillis());
        if (matches(binding, entry)) return new Reading(entry, live ? "" : mc.isGamePaused() ? "paused" : "stale");
        if (!currentReceipt || pending) return new Reading(null, "waiting");
        return new Reading(null, "binding");
    }

    /** Last complete received list, not current authority; no world reads or additional request. */
    public static DeviceTerminalClientCache.Snapshot displaySnapshot(UUID id) {
        Minecraft mc = Minecraft.getMinecraft();
        Demand demand = DEMANDS.get(id);
        if (demand == null || !demand.active
            || player == null
            || player != mc.thePlayer
            || world != mc.theWorld
            || connection != mc.getNetHandler()
            || player.isDead
            || player.getHealth() <= 0
            || demand.held == null
            || !demand.held.available(id)) return null;
        updateDisplay(id, demand);
        return demand.snapshot;
    }

    public static long displayReceivedAt(UUID id) {
        Demand demand = DEMANDS.get(id);
        return demand == null || demand.snapshot == null ? 0 : demand.receivedAt;
    }

    private static void updateDisplay(UUID id, Demand demand) {
        DeviceTerminalClientCache.Snapshot snapshot = DeviceTerminalClientCache.getSnapshot(id);
        // Revocation/source epoch changes clear the raw cache: never let our display reference outlive it.
        if (snapshot == null) {
            demand.snapshot = null;
            demand.entries.clear();
            return;
        }
        long epoch = DeviceTerminalClientCache.authorizationEpoch(id);
        if (demand.epoch != epoch) {
            demand.entries.clear();
            demand.snapshot = null;
            demand.epoch = epoch;
        }
        boolean currentReceipt = DeviceTerminalClientCache.receiptRevision(id) > demand.initialReceipt;
        boolean pending = DeviceTerminalClientCache.hasMorePending(id);
        if (currentReceipt && !pending) demand.receivedAt = DeviceTerminalClientCache.receivedAtMillis(id);
        if (currentReceipt && !pending && demand.snapshot != snapshot) {
            demand.entries.clear();
            for (Entry entry : snapshot.entries) demand.entries.put(entry.key, entry);
            demand.snapshot = snapshot;
        }
    }

    static boolean matches(DeviceBinding binding, Entry entry) {
        return binding != null && entry != null
            && binding.key.equals(entry.key)
            && binding.dimension == entry.dim
            && binding.x == entry.x
            && binding.y == entry.y
            && binding.z == entry.z
            && binding.meta == entry.metaTileId;
    }

    private static Held find(EntityPlayer owner, UUID id) {
        if (owner == null || id == null || owner.isDead || owner.getHealth() <= 0) return null;
        ItemStack[] inventory = owner.inventory.mainInventory;
        for (int index = 0; index < inventory.length; index++)
            if (hasId(inventory[index], id)) return new Held(inventory, null, index);
        IInventory baubles = TerminalBaubles.inventory(owner);
        if (baubles != null) for (int index = 0; index < baubles.getSizeInventory(); index++)
            if (hasId(baubles.getStackInSlot(index), id)) return new Held(null, baubles, index);
        return null;
    }

    private static boolean hasId(ItemStack stack, UUID id) {
        return stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal
            && id.equals(ItemDeviceInfoTerminal.readTerminalId(stack));
    }

    /** Drop interest, not the last display. No hidden request is scheduled. */
    private static void releaseDemands() {
        for (Demand demand : DEMANDS.values()) demand.active = false;
        cursor = null;
        lastRequest = Integer.MIN_VALUE;
    }

    public static void revoke(UUID id) {
        Demand demand = DEMANDS.get(id);
        if (demand != null) {
            demand.snapshot = null;
            demand.entries.clear();
            demand.receivedAt = 0;
        }
        com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot.invalidateDisplay();
    }

    public static void resetSession() {
        factorySource = selectionSource = nativeSource = null;
        VISIBLE.clear();
        reset();
    }

    private static void reset() {
        DEMANDS.clear();
        player = null;
        world = connection = null;
        cursor = null;
        lastRequest = Integer.MIN_VALUE;
    }

    private static final class Demand {

        long initialReceipt;
        boolean active;
        long epoch = -1, receivedAt;
        Held held;
        DeviceTerminalClientCache.Snapshot snapshot;
        final Map<String, Entry> entries = new java.util.HashMap<>();
        int lastRequest = Integer.MIN_VALUE;

        Demand(UUID id) {
            initialReceipt = DeviceTerminalClientCache.receiptRevision(id);
        }
    }

    /** A direct real slot reference lets any number of cards validate possession without rescanning inventory. */
    private static final class Held {

        final ItemStack[] inventory;
        final IInventory baubles;
        final int index;

        Held(ItemStack[] inventory, IInventory baubles, int index) {
            this.inventory = inventory;
            this.baubles = baubles;
            this.index = index;
        }

        boolean available(UUID id) {
            return hasId(inventory == null ? baubles.getStackInSlot(index) : inventory[index], id);
        }
    }

    public static final class Reading {

        public final Entry entry;
        /** Empty only when live; historical entries always carry stale/paused and confer no action authority. */
        public final String reason;

        public boolean live() {
            return entry != null && reason.isEmpty();
        }

        private Reading(Entry entry, String reason) {
            this.entry = entry;
            this.reason = reason;
        }
    }

    public static final class Source {

        public final UUID id;
        public final String label;

        private Source(UUID id, String label) {
            this.id = id;
            this.label = label;
        }
    }
}
