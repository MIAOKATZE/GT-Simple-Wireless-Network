package com.miaokatze.gtswn.client;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.miaokatze.gtswn.client.gui.GuiPortableMonitor;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.hud.PortableMonitorPins;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.panel.TrendLinePalette;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.common.util.GTTierUtil;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestPortableMonitor;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;
import com.miaokatze.gtswn.network.PortableMonitorIcon;

import baubles.api.BaublesApi;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Client-main-thread cache shared by the UI and HUD; polling remains independent of frame rate. */
public final class PortableMonitorClientCache {

    private static final Map<UUID, Cached> CACHE = new HashMap<>();
    private static EntityPlayer sessionPlayer;
    private static Object sessionWorld, sessionConnection;
    private static int nextSequence;
    private static boolean legacyChartServer;
    private static long nextGlobalRequest;
    private static KeyBinding monitorKey;

    public static void registerKey() {
        monitorKey = new KeyBinding("gtswn.monitor.key.open", Keyboard.KEY_H, "gtswn.monitor.key.category");
        ClientRegistry.registerKeyBinding(monitorKey);
    }

    public static int keyCode() {
        return monitorKey == null ? Keyboard.KEY_H : monitorKey.getKeyCode();
    }

    private PortableMonitorClientCache() {}

    private static void session() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        Object connection = mc.getNetHandler() == null ? null
            : mc.getNetHandler()
                .getNetworkManager();
        if (player != sessionPlayer || mc.theWorld != sessionWorld || connection != sessionConnection) {
            resetForHudSession();
            sessionPlayer = player;
            sessionWorld = mc.theWorld;
            sessionConnection = connection;
        }
    }

    /** Only transient HUD/UI snapshots; configured pins and terminal NBT remain untouched. */
    public static void resetForHudSession() {
        CACHE.clear();
        PanelHistoryClientCache.clear();
        nextGlobalRequest = 0;
        legacyChartServer = false;
        sessionPlayer = null;
        sessionWorld = null;
        sessionConnection = null;
    }

    public static void poll(ItemStack stack) {
        session();
        stack = currentStack(stack);
        UUID id = MonitorBindings.id(stack);
        if (id == null || sessionPlayer == null || !PortableWirelessNetworkMonitor.isMonitorBound(stack)) return;
        if (CACHE.size() >= 32 && !CACHE.containsKey(id)) CACHE.clear();
        Cached cache = CACHE.computeIfAbsent(id, ignored -> new Cached());
        cache.stack = stack;
        String owner = PortableWirelessNetworkMonitor.getOwnerUUID(stack);
        if (!cache.owner.isEmpty() && !owner.equals(cache.owner)) {
            cache.snapshot = cache.mutationResponse = null;
            cache.renderSnapshot = null;
            cache.views.clear();
            cache.viewVersions.clear();
            cache.viewSources.clear();
            cache.inFlight = false;
            cache.action = 0;
            cache.receivedAt = 0;
            cache.dirty = true;
        }
        cache.owner = owner;
        boolean dashboard = Minecraft.getMinecraft().currentScreen == null && cache.dashboardSelected >= 0;
        int selected = dashboard ? cache.dashboardSelected : cache.selected;
        long revision = MonitorBindings.revision(stack);
        if (revision != cache.bindingRevision) {
            cache.bindingRevision = revision;
            cache.dirty = true;
        }
        int pinsHash = Arrays.hashCode(Config.hudPinnedEntries);
        if (pinsHash != cache.pinsHash) {
            cache.pinsHash = pinsHash;
            cache.hasPins = false;
            for (String entry : Config.hudPinnedEntries) if (entry.startsWith(id + "/")) {
                cache.hasPins = true;
                break;
            }
        }
        if (!cache.inFlight && cache.action == 0 && selected < 0 && !cache.hasPins) return;
        long now = System.currentTimeMillis();
        if (now < nextGlobalRequest || cache.inFlight && now - cache.sentAt < 5000
            || !cache.inFlight && cache.action == 0
                && !cache.dirty
                && now - cache.sentAt < Math.max(1000, Config.hudUpdateInterval * 50L))
            return;
        if (!cache.inFlight) {
            PacketRequestPortableMonitor request = new PacketRequestPortableMonitor(
                id,
                ++nextSequence,
                cache.action,
                cache.value);
            request.targetPanel = cache.targetPanel;
            request.selected = selected;
            selectRequest(request, stack, cache.snapshot);
            configureChartReuse(request, cache.snapshot);
            request.rawHistory = com.miaokatze.gtswn.network.telemetry.MonitorCapabilities
                .clientSupports(com.miaokatze.gtswn.network.telemetry.MonitorCapabilities.PORTABLE);
            if (request.rawHistory && cache.snapshot != null)
                for (PacketSyncPortableMonitor.Panel panel : cache.snapshot.panels)
                    if (panel.binding.panelId.equals(request.selectedPanel) && completeBase(panel))
                        request.knownConfigVersion = panel.configVersion;
            request.charts = Config.hudRenderMode == 2;
            subscribeRequest(request, Config.hudPinnedEntries);
            if (request.rawHistory && request.action == 0 && cache.snapshot != null && cache.snapshot.rawHistory)
                for (PacketSyncPortableMonitor.Panel panel : cache.snapshot.panels) {
                    if (panel.metadataVersion > 0)
                        request.metadataVersions.put(panel.binding.panelId, panel.metadataVersion);
                    if (panel.state == 0 && panel.render != null
                        && panel.renderVersion > 0
                        && panel.configVersion > 0
                        && panel.historyFields > 0)
                        request.historyCursors.put(
                            panel.binding.panelId,
                            new PacketRequestPortableMonitor.HistoryCursor(
                                panel.renderVersion,
                                panel.configVersion,
                                panel.historyFields));
                }
            if (request.selected < 0 && request.pins.isEmpty() && request.action == 0) return;
            cache.sequence = request.sequence;
            cache.request = request;
            cache.requestOwner = owner;
            cache.dashboardRequest = dashboard;
            cache.inFlight = true;
            cache.dirty = false;
            cache.action = 0;
        }
        cache.sentAt = now;
        nextGlobalRequest = now + 1100;
        GTSWNPacketHandler.NETWORK.sendToServer(
            cache.request.rawHistory ? new com.miaokatze.gtswn.network.PacketRequestPortableHistory(cache.request)
                : cache.request);
    }

    public static boolean action(ItemStack stack, int action, int value) {
        session();
        stack = currentStack(stack);
        UUID id = MonitorBindings.id(stack);
        if (id == null) return false;
        Cached cache = CACHE.computeIfAbsent(id, ignored -> new Cached());
        if (busy(cache)) return false;
        if (action == 1) {
            PacketSyncPortableMonitor snapshot = snapshot(stack);
            if (snapshot == null || value < 0 || value >= snapshot.panels.size()) return false;
            cache.targetPanel = snapshot.panels.get(value).binding.panelId;
        } else cache.targetPanel = new UUID(0, 0);
        cache.action = action;
        cache.value = value;
        cache.result = 0;
        poll(stack);
        return true;
    }

    /** Inventory S2F and snapshot replies can arrive on different ticks. Select from the newest credentials. */
    public static void selectRequest(PacketRequestPortableMonitor request, ItemStack stack,
        PacketSyncPortableMonitor snapshot) {
        request.knownRenderVersion = 0;
        if (request.selected < 0) return;
        if (snapshot != null && !request.terminalId.equals(snapshot.terminalId)) snapshot = null;
        List<MonitorBindings.Binding> bindings = MonitorBindings.read(stack);
        if (snapshot != null && snapshot.bindingRevision >= MonitorBindings.revision(stack)) {
            bindings = new ArrayList<>();
            for (PacketSyncPortableMonitor.Panel panel : snapshot.panels) bindings.add(panel.binding);
        }
        if (bindings.isEmpty()) return;
        request.selected = Math.min(request.selected, bindings.size() - 1);
        request.selectedPanel = bindings.get(request.selected).panelId;
        if (snapshot != null) for (PacketSyncPortableMonitor.Panel panel : snapshot.panels) {
            if (sameSource(panel.binding, bindings.get(request.selected)) && panel.state == 0
                && panel.renderVersion > 0
                && panel.render != null) {
                request.knownRenderVersion = panel.renderVersion;
                break;
            }
        }
    }

    /** A reset or partial snapshot never acknowledges a chart base. Mutations always request full data. */
    static void configureChartReuse(PacketRequestPortableMonitor request, PacketSyncPortableMonitor snapshot) {
        request.reuseCharts = request.action == 0 && !legacyChartServer;
        if (request.action != 0) {
            request.knownRenderVersion = 0;
            return;
        }
        if (snapshot != null && request.terminalId.equals(snapshot.terminalId) && request.selected >= 0)
            for (PacketSyncPortableMonitor.Panel panel : snapshot.panels) {
                if (panel.binding.panelId.equals(request.selectedPanel) && completeBase(panel)
                    && panel.renderVersion == request.knownRenderVersion) return;
            }
        request.knownRenderVersion = 0;
    }

    /** Learn old-server fallback only from an acknowledged identical base, never from a changed generation. */
    static void observeChartReuse(PacketSyncPortableMonitor response, PacketSyncPortableMonitor previous,
        PacketRequestPortableMonitor request) {
        if (response.rawHistory || !request.reuseCharts
            || request.action != 0
            || request.knownRenderVersion <= 0
            || previous == null
            || request.sequence != response.sequence
            || !request.terminalId.equals(response.terminalId)
            || !response.terminalId.equals(previous.terminalId)) return;
        for (PacketSyncPortableMonitor.Panel panel : response.panels) {
            if (!completeBase(panel) || panel.renderVersion != request.knownRenderVersion
                || !request.selectedPanel.equals(panel.binding.panelId)) continue;
            for (PacketSyncPortableMonitor.Panel old : previous.panels) {
                if (completeBase(old) && old.renderVersion == panel.renderVersion
                    && sameSource(old.binding, panel.binding)
                    && old.chartKey.equals(panel.chartKey)) {
                    legacyChartServer = true;
                    return;
                }
            }
        }
    }

    private static boolean completeBase(PacketSyncPortableMonitor.Panel panel) {
        return panel.state == 0 && panel.detail
            && panel.render != null
            && panel.renderVersion > 0
            && !panel.chartsUnchanged;
    }

    private static boolean sameSource(MonitorBindings.Binding first, MonitorBindings.Binding second) {
        return first != null && second != null
            && first.panelId.equals(second.panelId)
            && first.dimension == second.dimension
            && first.x == second.x
            && first.y == second.y
            && first.z == second.z;
    }

    /** Called only after the live owner/session/sequence checks; incomplete replies never become fresh snapshots. */
    static boolean restoreSnapshot(PacketSyncPortableMonitor response, PacketSyncPortableMonitor previous,
        PacketRequestPortableMonitor request) {
        if (response.rawHistory && (!request.rawHistory || !request.terminalId.equals(response.terminalId)
            || request.sequence != response.sequence)) return false;
        for (PacketSyncPortableMonitor.Panel panel : response.panels) {
            if (response.rawHistory) {
                PacketSyncPortableMonitor.Panel metadataBase = null;
                if (previous != null && response.terminalId.equals(previous.terminalId))
                    for (PacketSyncPortableMonitor.Panel old : previous.panels)
                        if (sameSource(old.binding, panel.binding)) {
                            metadataBase = old;
                            break;
                        }
                if (panel.metadataUnchanged
                    && request.metadataVersions.getOrDefault(panel.binding.panelId, 0L) != panel.metadataVersion)
                    return false;
                if (!com.miaokatze.gtswn.network.PortablePanelMetadata.restore(panel, metadataBase)) return false;
            }
            if (response.rawHistory && panel.historyPatch != null) {
                PacketSyncPortableMonitor.Panel base = null;
                if (previous != null && response.terminalId.equals(previous.terminalId))
                    for (PacketSyncPortableMonitor.Panel old : previous.panels)
                        if (sameSource(old.binding, panel.binding)) {
                            base = old;
                            break;
                        }
                if (panel.state != 0 || !panel.historyPatch.apply(panel, base)) return false;
                panel.historyPatch = null;
                continue;
            }
            if (!panel.detail || panel.state != 0 || panel.render != null) continue;
            if (request.action != 0 || request.knownRenderVersion != panel.renderVersion
                || !request.terminalId.equals(response.terminalId)
                || request.sequence != response.sequence
                || !request.selectedPanel.equals(panel.binding.panelId)
                || panel.chartsUnchanged && !request.reuseCharts
                || previous == null
                || !response.terminalId.equals(previous.terminalId)) return false;
            PacketSyncPortableMonitor.Panel base = null;
            for (PacketSyncPortableMonitor.Panel old : previous.panels) {
                if (completeBase(old) && sameSource(old.binding, panel.binding)
                    && old.chartKey.equals(panel.chartKey)
                    && old.renderVersion == panel.renderVersion) {
                    base = old;
                    break;
                }
            }
            if (base == null) return false;
            panel.render = base.render.copy();
            if (panel.chartsUnchanged) {
                panel.euChart = base.euChart.clone();
                panel.aeChart = base.aeChart.clone();
                panel.euRateChart = base.euRateChart.clone();
                panel.aeRateChart = base.aeRateChart.clone();
                panel.chartsUnchanged = false;
            }
        }
        return true;
    }

    /** Collapse local AE field choices to one bounded native subscription per target. */
    public static void subscribeRequest(PacketRequestPortableMonitor request, String[] entries) {
        String prefix = request.terminalId + "/";
        int pinCount = 0;
        for (String entry : entries) {
            if (!entry.startsWith(prefix) || pinCount >= PacketRequestPortableMonitor.MAX_PINS) continue;
            int split = entry.indexOf('/', prefix.length());
            if (split < 0) continue;
            try {
                UUID panel = UUID.fromString(entry.substring(prefix.length(), split));
                String key = PortableMonitorPins.requestKey(entry.substring(split + 1));
                if (key.isEmpty() || key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256) continue;
                if (request.pins.computeIfAbsent(panel, ignored -> new HashSet<>())
                    .add(key)) pinCount++;
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private static boolean busy(Cached cache) {
        return cache.action != 0 || cache.inFlight && cache.request.action != 0;
    }

    public static boolean canMutate(ItemStack stack) {
        stack = currentStack(stack);
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        return player != null && PortableWirelessNetworkMonitor.isMonitorBound(stack)
            && player.getUniqueID()
                .toString()
                .equals(PortableWirelessNetworkMonitor.getOwnerUUID(stack));
    }

    public static int mutationStatus(ItemStack stack) {
        stack = currentStack(stack);
        Cached cache = CACHE.get(MonitorBindings.id(stack));
        return cache == null ? 0 : busy(cache) ? 4 : cache.result;
    }

    public static void viewing(ItemStack stack, int index) {
        session();
        UUID previous = MonitorBindings.id(stack);
        stack = currentStack(stack);
        UUID id = MonitorBindings.id(stack);
        if (id == null && index < 0) id = previous;
        if (id == null) return;
        Cached cache = CACHE.computeIfAbsent(id, ignored -> new Cached());
        if (cache.selected != index) {
            cache.selected = index;
            cache.dirty = true;
        }
    }

    /** Independent foreground demand; closing the engineering board never changes the ordinary viewer's selection. */
    public static void dashboardViewing(ItemStack stack, int index) {
        session();
        UUID previous = MonitorBindings.id(stack);
        UUID id = MonitorBindings.id(currentStack(stack));
        if (id == null && index < 0) id = previous;
        if (id == null) return;
        Cached cache = CACHE.computeIfAbsent(id, ignored -> new Cached());
        if (cache.dashboardSelected != index) {
            cache.dashboardSelected = index;
            cache.dirty = true;
            if (index < 0 && cache.inFlight && cache.dashboardRequest && cache.request.action == 0)
                cache.inFlight = false;
        }
    }

    /** Pending mutations survive closing the UI or disabling the HUD. Refreshes stay demand-driven. */
    public static final class TickListener {

        @SubscribeEvent
        public void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            session();
            Minecraft mc = Minecraft.getMinecraft();
            if (monitorKey != null && monitorKey.isPressed() && mc.currentScreen == null && mc.thePlayer != null) {
                ItemStack terminal = findMonitor(mc.thePlayer);
                if (terminal != null) ClientGUI.open(new GuiPortableMonitor(terminal));
            }
            for (Cached cache : CACHE.values()) {
                if (cache.action != 0 || cache.inFlight && cache.request.action != 0) poll(cache.stack);
            }
        }
    }

    public static void receive(PacketSyncPortableMonitor response) {
        session();
        Cached cache = CACHE.get(response.terminalId);
        if (cache != null && cache.sequence == response.sequence && response.result == 2 && response.panels.isEmpty()) {
            com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard.revoke(response.terminalId);
            cache.snapshot = cache.renderSnapshot = cache.mutationResponse = null;
            cache.views.clear();
            cache.viewVersions.clear();
            cache.viewSources.clear();
            cache.inFlight = false;
            cache.receivedAt = 0;
            cache.result = 2;
            cache.dirty = true;
            return;
        }
        if (cache == null || !cache.inFlight || cache.sequence != response.sequence) return;
        cache.stack = currentStack(cache.stack);
        if (cache.stack == null || !response.terminalId.equals(MonitorBindings.id(cache.stack))
            || !cache.requestOwner.equals(PortableWirelessNetworkMonitor.getOwnerUUID(cache.stack))) {
            cache.inFlight = false;
            cache.snapshot = null;
            return;
        }
        com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard.invalidateMetadata(response);
        observeChartReuse(response, cache.snapshot, cache.request);
        boolean complete = restoreSnapshot(response, cache.snapshot, cache.request);
        if (cache.request.action != 0) {
            cache.result = response.result;
            cache.mutationResponse = response;
            if (cache.stack != null && cache.stack.stackTagCompound != null)
                cache.stack.stackTagCompound.setInteger(PortableWirelessNetworkMonitor.NBT_HUD_MODE, response.hudMode);
            if (cache.request.action == 1 && (response.result == 1 || response.result == 3)) {
                String prefix = response.terminalId + "/" + cache.request.targetPanel + "/";
                List<String> retained = new ArrayList<>(Arrays.asList(Config.hudPinnedEntries));
                if (retained.removeIf(entry -> entry.startsWith(prefix))) {
                    Config.hudPinnedEntries = retained.toArray(new String[0]);
                    Config.saveHudConfiguration();
                }
                cache.views.remove(cache.request.targetPanel);
                cache.viewVersions.remove(cache.request.targetPanel);
                cache.viewSources.remove(cache.request.targetPanel);
            }
        }
        cache.inFlight = false;
        if (!complete) {
            cache.snapshot = cache.renderSnapshot = null;
            cache.views.clear();
            cache.viewVersions.clear();
            cache.viewSources.clear();
            cache.dirty = true;
            return;
        }
        if (response.bindingRevision < MonitorBindings.revision(cache.stack)) {
            cache.dirty = true;
            return;
        }
        cache.receivedAt = System.currentTimeMillis();
        cache.receiptRevision++;
        cache.snapshot = response;
        cache.owner = cache.requestOwner;
        Set<UUID> retainedPanels = new HashSet<>();
        for (PacketSyncPortableMonitor.Panel panel : response.panels) retainedPanels.add(panel.binding.panelId);
        cache.views.keySet()
            .retainAll(retainedPanels);
        cache.viewVersions.keySet()
            .retainAll(retainedPanels);
        cache.viewSources.keySet()
            .retainAll(retainedPanels);
        if (response.bindingRevision < cache.bindingRevision) cache.dirty = true;
        if (cache.request.selected >= 0 && !cache.request.selectedPanel.equals(new UUID(0, 0))) {
            boolean retained = false;
            for (PacketSyncPortableMonitor.Panel panel : response.panels)
                if (panel.binding.panelId.equals(cache.request.selectedPanel)) retained = true;
            if (!retained) cache.dirty = true;
        }
        if (cache.selected >= response.panels.size()) {
            cache.selected = response.panels.isEmpty() ? 0 : response.panels.size() - 1;
            cache.dirty = true;
        }
    }

    private static ItemStack findMonitor(EntityPlayer player) {
        if (PortableWirelessNetworkMonitor.isMonitorBound(player.getHeldItem())) return player.getHeldItem();
        try {
            IInventory baubles = BaublesApi.getBaubles(player);
            if (baubles != null) for (int i = 0; i < baubles.getSizeInventory(); i++) {
                ItemStack stack = baubles.getStackInSlot(i);
                if (PortableWirelessNetworkMonitor.isMonitorBound(stack)) return stack;
            }
        } catch (NoClassDefFoundError ignored) {}
        for (ItemStack stack : player.inventory.mainInventory)
            if (PortableWirelessNetworkMonitor.isMonitorBound(stack)) return stack;
        return null;
    }

    /** Inventory synchronization can replace an ItemStack while a screen still holds its old reference. */
    public static ItemStack currentStack(ItemStack previous) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return null;
        UUID id = MonitorBindings.id(previous);
        if (id == null) {
            ItemStack held = player.getHeldItem();
            return PortableWirelessNetworkMonitor.isMonitorBound(held)
                && PortableWirelessNetworkMonitor.isMonitorBound(previous)
                && PortableWirelessNetworkMonitor.getOwnerUUID(held)
                    .equals(PortableWirelessNetworkMonitor.getOwnerUUID(previous)) ? held : null;
        }
        ItemStack held = player.getHeldItem();
        if (id.equals(MonitorBindings.id(held)) && PortableWirelessNetworkMonitor.isMonitorBound(held)) return held;
        try {
            IInventory baubles = BaublesApi.getBaubles(player);
            if (baubles != null) for (int i = 0; i < baubles.getSizeInventory(); i++) {
                ItemStack stack = baubles.getStackInSlot(i);
                if (id.equals(MonitorBindings.id(stack)) && PortableWirelessNetworkMonitor.isMonitorBound(stack))
                    return stack;
            }
        } catch (NoClassDefFoundError ignored) {}
        for (ItemStack stack : player.inventory.mainInventory)
            if (id.equals(MonitorBindings.id(stack)) && PortableWirelessNetworkMonitor.isMonitorBound(stack))
                return stack;
        return null;
    }

    public static PortablePanelView view(ItemStack stack, int index) {
        PacketSyncPortableMonitor snapshot = snapshot(stack);
        if (snapshot == null || index < 0 || index >= snapshot.panels.size()) return null;
        PacketSyncPortableMonitor.Panel panel = snapshot.panels.get(index);
        if (!panel.detail || panel.render == null || panel.state != 0 || stale(stack)) return null;
        Cached cache = CACHE.get(snapshot.terminalId);
        PortablePanelView view = cache.views.get(panel.binding.panelId);
        Long version = cache.viewVersions.get(panel.binding.panelId);
        if (view == null || version == null || version != panel.renderVersion) {
            view = new PortablePanelView(panel);
            cache.views.put(panel.binding.panelId, view);
            cache.viewVersions.put(panel.binding.panelId, panel.renderVersion);
        }
        if (cache.viewSources.get(panel.binding.panelId) != panel) {
            view.updateLive(panel);
            cache.viewSources.put(panel.binding.panelId, panel);
        }
        return view;
    }

    public static PacketSyncPortableMonitor snapshot(ItemStack stack) {
        session();
        stack = currentStack(stack);
        Cached cache = CACHE.get(MonitorBindings.id(stack));
        return cache == null || cache.snapshot == null
            || !cache.owner.equals(PortableWirelessNetworkMonitor.getOwnerUUID(stack))
            || cache.snapshot.bindingRevision < MonitorBindings.revision(stack) ? null : cache.snapshot;
    }

    public static long receiptRevision(ItemStack stack) {
        Cached cache = CACHE.get(MonitorBindings.id(currentStack(stack)));
        return cache == null ? 0 : cache.receiptRevision;
    }

    public static long receivedAtMillis(ItemStack stack) {
        Cached cache = CACHE.get(MonitorBindings.id(currentStack(stack)));
        return cache == null ? 0 : cache.receivedAt;
    }

    /** The actual mutation ACK, including its binding count even if a newer S2F already arrived. */
    public static PacketSyncPortableMonitor mutationResponse(ItemStack stack) {
        stack = currentStack(stack);
        Cached cache = CACHE.get(MonitorBindings.id(stack));
        return cache == null ? null : cache.mutationResponse;
    }

    public static boolean stale(ItemStack stack) {
        stack = currentStack(stack);
        Cached cache = CACHE.get(MonitorBindings.id(stack));
        return cache == null || cache.snapshot == null
            || !cache.owner.equals(PortableWirelessNetworkMonitor.getOwnerUUID(stack))
            || cache.snapshot.bindingRevision < MonitorBindings.revision(stack)
            || System.currentTimeMillis() - cache.receivedAt > Math.max(10000, Config.hudUpdateInterval * 150L);
    }

    public static String pinKey(UUID terminal, PacketSyncPortableMonitor.Panel panel,
        PacketSyncPortableMonitor.Row row) {
        return terminal + "/" + panel.binding.panelId + "/" + row.key;
    }

    public static boolean pinned(UUID terminal, PacketSyncPortableMonitor.Panel panel,
        PacketSyncPortableMonitor.Row row) {
        return PortableMonitorPins
            .selected(Config.hudPinnedEntries, terminal + "/" + panel.binding.panelId + "/", row.key);
    }

    public static boolean togglePin(UUID terminal, PacketSyncPortableMonitor.Panel panel,
        PacketSyncPortableMonitor.Row row) {
        return togglePin(terminal, panel, row.key);
    }

    public static boolean stockPinned(UUID terminal, PacketSyncPortableMonitor.Panel panel,
        PacketSyncPortableMonitor.Row row, boolean average) {
        return PortableMonitorPins.selected(
            Config.hudPinnedEntries,
            terminal + "/" + panel.binding.panelId + "/",
            PortableMonitorPins.stock(row.key, average));
    }

    public static boolean toggleStockPin(UUID terminal, PacketSyncPortableMonitor.Panel panel,
        PacketSyncPortableMonitor.Row row, boolean average) {
        return togglePin(terminal, panel, PortableMonitorPins.stock(row.key, average));
    }

    private static boolean togglePin(UUID terminal, PacketSyncPortableMonitor.Panel panel, String key) {
        String[] entries = PortableMonitorPins.toggle(
            Config.hudPinnedEntries,
            terminal + "/" + panel.binding.panelId + "/",
            key,
            PacketRequestPortableMonitor.MAX_PINS);
        if (entries == null) return false;
        Config.hudPinnedEntries = entries;
        Cached cache = CACHE.get(terminal);
        if (cache != null) cache.dirty = true;
        return Config.saveHudConfiguration();
    }

    public static boolean graphPinned(UUID terminal, PacketSyncPortableMonitor.Panel panel, int tab) {
        return PortableMonitorPins.selected(
            Config.hudPinnedEntries,
            terminal + "/" + panel.binding.panelId + "/",
            PortableMonitorPins.plot(tab));
    }

    public static boolean toggleGraph(UUID terminal, PacketSyncPortableMonitor.Panel panel, int tab) {
        PacketSyncPortableMonitor.Row row = new PacketSyncPortableMonitor.Row();
        row.key = PortableMonitorPins.plot(tab);
        return togglePin(terminal, panel, row);
    }

    /** Local subscriptions remain editable when remote live content is unavailable. */
    public static List<PacketSyncPortableMonitor.Row> managementRows(UUID terminal,
        PacketSyncPortableMonitor.Panel panel, String[] pins, boolean fresh, String unavailable) {
        return managementRows(terminal, panel, pins, fresh, unavailable, 2);
    }

    public static List<PacketSyncPortableMonitor.Row> managementRows(UUID terminal,
        PacketSyncPortableMonitor.Panel panel, String[] pins, boolean fresh, String unavailable, int tab) {
        Map<String, PacketSyncPortableMonitor.Row> rows = new java.util.LinkedHashMap<>();
        Map<String, PacketSyncPortableMonitor.Row> metadata = new HashMap<>();
        if (tab < 2) {
            for (String key : new String[] { PortableMonitorPins.title(tab), PortableMonitorPins.plot(tab) }) {
                PacketSyncPortableMonitor.Row row = new PacketSyncPortableMonitor.Row();
                row.key = key;
                row.kind = 3;
                row.name = StatCollector
                    .translateToLocal("gtswn.monitor.pin." + (key.endsWith(":title") ? "title" : "plot"));
                rows.put(key, row);
            }
            if (tab == 1) return new ArrayList<>(rows.values());
        }
        for (PacketSyncPortableMonitor.Row row : panel.rows) if (tab == 0 ? row.kind == 0 : row.kind != 0) {
            metadata.put(row.key, row);
            if (fresh && panel.detail && panel.state == 0) rows.put(row.key, row);
        }
        String prefix = terminal + "/" + panel.binding.panelId + "/";
        for (String pin : pins) {
            if (!pin.startsWith(prefix)) continue;
            String key = PortableMonitorPins.requestKey(pin.substring(prefix.length()));
            if (PortableMonitorPins.component(key) || rows.containsKey(key)
                || (tab == 0 ? !key.equals("eu") : key.equals("eu"))) continue;
            PacketSyncPortableMonitor.Row withdrawn = new PacketSyncPortableMonitor.Row();
            withdrawn.key = key;
            PacketSyncPortableMonitor.Row previous = metadata.get(key);
            withdrawn.name = previous == null ? key : previous.name;
            withdrawn.kind = key.equals("eu") ? 0 : key.startsWith("fluid:") ? 2 : 1;
            withdrawn.amount = unavailable;
            rows.put(key, withdrawn);
        }
        return new ArrayList<>(rows.values());
    }

    public static List<PinnedLine> pinnedLines(ItemStack stack) {
        PacketSyncPortableMonitor snapshot = snapshot(stack);
        if (snapshot == null) return Collections.emptyList();
        Cached cache = CACHE.get(snapshot.terminalId);
        int options = Arrays.hashCode(Config.hudPinnedEntries) * 31 + (Config.hudTierEnabled ? 1 : 0)
            + (Config.hudAEAmountEnabled ? 2 : 0)
            + (Config.hudAERateEnabled ? 4 : 0)
            + (stale(stack) ? 8 : 0)
            + (Config.hudEUEnabled ? 16 : 0)
            + (Config.hudInstantEnabled ? 32 : 0)
            + (Config.hudAverageEnabled ? 64 : 0)
            + (Config.hudCompact ? 128 : 0);
        if (cache.renderSnapshot == snapshot && cache.renderOptions == options) return cache.renderLines;
        List<PinnedLine> lines = new ArrayList<>();
        for (PacketSyncPortableMonitor.Panel panel : snapshot.panels) {
            String prefix = snapshot.terminalId + "/" + panel.binding.panelId + "/";
            String source = panel.binding.panelId.toString();
            String stockLabel = "\u00a73[" + StatCollector.translateToLocal(PortableHudLayout.Group.AE_STOCK.labelKey)
                + "] ";
            boolean unavailable = panel.state != 0 || stale(stack);
            String status = "\u00a77" + panel.binding.label()
                + " \u00a76"
                + stateText(panel.state == 0 ? 4 : panel.state);
            Set<String> presentKeys = new HashSet<>();
            for (PacketSyncPortableMonitor.Row row : panel.rows) {
                presentKeys.add(row.key);
                if (row.kind == 0 || !pinned(snapshot.terminalId, panel, row)) continue;
                String text = unavailable ? status
                    : pinnedStockText(
                        row,
                        panel.displayMode,
                        Config.hudAEAmountEnabled,
                        Config.hudAERateEnabled && stockPinned(snapshot.terminalId, panel, row, false),
                        Config.hudAERateEnabled && stockPinned(snapshot.terminalId, panel, row, true));
                if (text != null) lines.add(
                    new PinnedLine(stockLabel + text, unavailable ? null : row.icon)
                        .grouped(PortableHudLayout.Group.AE_STOCK, source));
            }
            for (String pin : Config.hudPinnedEntries) {
                if (!pin.startsWith(prefix) || PortableMonitorPins.component(pin.substring(prefix.length()))) continue;
                String key = PortableMonitorPins.requestKey(pin.substring(prefix.length()));
                if (presentKeys.add(key)) lines.add(
                    new PinnedLine((key.equals("eu") ? "" : stockLabel) + status, new double[0]).grouped(
                        key.equals("eu") ? PortableHudLayout.Group.EU_TREND : PortableHudLayout.Group.AE_STOCK,
                        source));
            }
            for (int graph = 0; graph < 2; graph++) {
                PacketSyncPortableMonitor.Row value = null;
                for (PacketSyncPortableMonitor.Row row : panel.rows)
                    if (graph == 0 ? row.kind == 0 : row.key.equals(panel.chartKey)) {
                        value = row;
                        break;
                    }
                String name = graph == 0 ? "EU / EU/t"
                    : value == null ? "AE" : value.name + " / " + (value.kind == 2 ? "mB/min" : "/min");
                PortableHudLayout.Group group = graph == 0 ? PortableHudLayout.Group.EU_TREND
                    : PortableHudLayout.Group.AE_TREND;
                if (PortableMonitorPins.selected(Config.hudPinnedEntries, prefix, PortableMonitorPins.title(graph)))
                    lines.add(
                        new PinnedLine(
                            unavailable ? status : name + " [" + (graph == 0 ? panel.window : panel.aeWindow) + "]",
                            new double[0]).grouped(group, source));
                if (graphPinned(snapshot.terminalId, panel, graph)) lines.add(
                    new PinnedLine(
                        unavailable ? status : "",
                        unavailable ? new double[0] : graph == 0 ? panel.euChart : panel.aeChart,
                        unavailable ? new double[0] : graph == 0 ? panel.euRateChart : panel.aeRateChart,
                        value == null ? 0 : value.rate,
                        "",
                        panel.palette,
                        graph == 0).grouped(group, source));
                if (graph == 0 && value != null && pinned(snapshot.terminalId, panel, value)) {
                    if (unavailable) lines.add(new PinnedLine(status, new double[0]).grouped(group, source));
                    else for (String text : pinnedEUValueLines(
                        value,
                        panel.displayMode,
                        Config.hudEUEnabled,
                        Config.hudInstantEnabled,
                        Config.hudAverageEnabled,
                        Config.hudTierEnabled,
                        StatCollector.translateToLocal("gtswn.monitor.field.instant"),
                        StatCollector.translateToLocal("gtswn.monitor.field.average")))
                        lines.add(new PinnedLine(text, new double[0]).grouped(group, source));
                }
            }
        }
        cache.renderSnapshot = snapshot;
        cache.renderOptions = options;
        cache.renderLines = lines;
        return lines;
    }

    /** Viewer always presents complete content, independently of HUD field switches. */
    public static String viewerRowText(PacketSyncPortableMonitor.Row row) {
        return viewerRowText(row, 1);
    }

    public static String viewerRowText(PacketSyncPortableMonitor.Row row, int displayMode) {
        if (row.kind == 0) return "\u00a7bEU: \u00a7f" + amount(row.amount, displayMode)
            + " | "
            + StatCollector.translateToLocal("gtswn.monitor.field.instant")
            + ": "
            + rate(row.rate, "EU/t", displayMode)
            + " | "
            + StatCollector.translateToLocal("gtswn.monitor.field.average")
            + ": "
            + rate(row.average, "EU/t", displayMode);
        return "\u00a7b" + row.name
            + ": \u00a7f"
            + amount(row.amount, displayMode)
            + (row.kind == 2 ? " mB" : "")
            + " "
            + rate(row.rate, row.kind == 2 ? "mB/min" : "/min", displayMode);
    }

    /** Explicit field options make the EU text formatter independently testable without a client world. */
    public static String pinnedEUText(PacketSyncPortableMonitor.Row row, boolean energy, boolean instant,
        boolean average, boolean compact, boolean tier, String instantLabel, String averageLabel) {
        if (!energy && !instant && !average) return null;
        List<String> parts = new ArrayList<>();
        if (energy) parts.add("\u00a7bEU: \u00a7f" + energyAmount(row.amount));
        if (instant) parts.add("\u00a7b" + (compact ? "\u0394t" : instantLabel) + ": " + rate(row.rate, "EU/t"));
        if (average) parts.add("\u00a7b" + (compact ? "\u0394avg" : averageLabel) + ": " + rate(row.average, "EU/t"));
        if (tier && (instant || average)) parts.add(GTTierUtil.formatGTPower(instant ? row.rate : row.average));
        return String.join(" | ", parts);
    }

    public static String energyAmount(String amount) {
        try {
            return FormatUtil.formatScientific(new BigInteger(amount));
        } catch (NumberFormatException ignored) {
            return amount;
        }
    }

    /** Three physical EU value rows share one source without adding screen numbers to their text. */
    public static List<String> pinnedEUValueLines(PacketSyncPortableMonitor.Row row, int displayMode, boolean energy,
        boolean instant, boolean average, boolean tier, String instantLabel, String averageLabel) {
        List<String> values = new ArrayList<>();
        if (instant) values.add(
            "\u00a7b" + instantLabel
                + ": "
                + rate(row.rate, "EU/t", displayMode)
                + (tier ? " " + GTTierUtil.formatGTPower(row.rate) : ""));
        if (average) values.add(
            "\u00a7b" + averageLabel
                + ": "
                + rate(row.average, "EU/t", displayMode)
                + (tier && !instant ? " " + GTTierUtil.formatGTPower(row.average) : ""));
        if (energy) values.add("\u00a7bEU: \u00a7f" + amount(row.amount, displayMode));
        return values;
    }

    /** Each AE target keeps one physical inventory row, with instant preceding the five-minute average. */
    public static String pinnedStockText(PacketSyncPortableMonitor.Row row, int displayMode, boolean showAmount,
        boolean instant, boolean average) {
        String unit = row.kind == 2 ? "mB/min" : "/min";
        return "\u00a7b" + row.name
            + (showAmount ? ": \u00a7f" + amount(row.amount, displayMode) + (row.kind == 2 ? " mB" : "") : "")
            + (instant ? " | " + rate(row.rate, unit, displayMode) : "")
            + (average ? (instant ? "\u00a77 - " : " | ") + rate(row.average, unit, displayMode) : "");
    }

    public static String amount(String value, int displayMode) {
        try {
            BigInteger number = new BigInteger(value);
            return displayMode == 1 ? FormatUtil.formatScientific(number)
                : displayMode == 2 ? FormatUtil.formatMetric(number) : FormatUtil.formatNormal(number);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    public static String rate(double value, String unit, int displayMode) {
        double number = Math.abs(value);
        return (value > 0 ? "\u00a7a+" : value < 0 ? "\u00a7c-" : "\u00a77")
            + (displayMode == 1 ? FormatUtil.formatScientificDouble(number)
                : displayMode == 2 ? FormatUtil.formatMetricDouble(number, 2) : FormatUtil.formatNormalDouble(number))
            + " "
            + unit;
    }

    public static String gridStatus(PacketSyncPortableMonitor.Panel panel, PacketSyncPortableMonitor.Row row) {
        if (panel.euChart.length < 2)
            return "\u00a76" + StatCollector.translateToLocal("gtswn.hud.network.status.calculating");
        if (row.rate == 0) return "\u00a77" + StatCollector.translateToLocal("gtswn.hud.network.status.silent");
        if (Math.abs(row.rate) < 1) return "\u00a77" + StatCollector.translateToLocal("gtswn.hud.network.status.lt1");
        return StatCollector
            .translateToLocal(row.rate > 0 ? "gtswn.monitor.grid.increasing" : "gtswn.monitor.grid.decreasing");
    }

    public static String rate(double value, String unit) {
        return (value > 0 ? "\u00a7a+" : value < 0 ? "\u00a7c-" : "\u00a77")
            + FormatUtil.formatScientificDouble(Math.abs(value))
            + " "
            + unit;
    }

    public static String stateText(int state) {
        return StatCollector.translateToLocal("gtswn.monitor.state." + state);
    }

    public static final class PinnedLine {

        public final String text;
        public final double[] chart;
        public final double[] rateChart;
        public final boolean graph;
        public final double change;
        public final String currentText;
        public final TrendLinePalette palette;
        public final boolean euGraph;
        public final PortableMonitorIcon icon;
        public final PortableHudLayout.Group group;
        public final String source;

        /** Keep rendering data unchanged while attaching the subscription's source identity. */
        public PinnedLine grouped(PortableHudLayout.Group group, String source) {
            return new PinnedLine(this, group, source);
        }

        private PinnedLine(PinnedLine line, PortableHudLayout.Group group, String source) {
            this.text = line.text;
            this.chart = line.chart;
            this.rateChart = line.rateChart;
            this.graph = line.graph;
            this.change = line.change;
            this.currentText = line.currentText;
            this.palette = line.palette;
            this.euGraph = line.euGraph;
            this.icon = line.icon;
            this.group = group;
            this.source = source;
        }

        PinnedLine(String text, double[] chart) {
            this.text = text;
            this.chart = chart;
            this.rateChart = new double[0];
            this.graph = false;
            this.change = 0;
            this.currentText = "";
            this.palette = TrendLinePalette.DEFAULT;
            this.euGraph = true;
            this.icon = null;
            this.group = PortableHudLayout.Group.NONE;
            this.source = "";
        }

        public PinnedLine(String text, PortableMonitorIcon icon) {
            this.text = text;
            this.chart = new double[0];
            this.rateChart = new double[0];
            this.graph = false;
            this.change = 0;
            this.currentText = "";
            this.palette = TrendLinePalette.DEFAULT;
            this.euGraph = true;
            this.icon = icon;
            this.group = PortableHudLayout.Group.NONE;
            this.source = "";
        }

        public PinnedLine(String text, double[] chart, double[] rates, double change) {
            this(text, chart, rates, change, "");
        }

        public PinnedLine(String text, double[] chart, double[] rates, double change, String currentText) {
            this(text, chart, rates, change, currentText, TrendLinePalette.DEFAULT, true);
        }

        public PinnedLine(String text, double[] chart, double[] rates, double change, String currentText,
            TrendLinePalette palette, boolean euGraph) {
            this.text = text;
            this.chart = chart;
            this.rateChart = rates;
            this.change = change;
            this.graph = true;
            this.currentText = currentText;
            this.palette = palette;
            this.euGraph = euGraph;
            this.icon = null;
            this.group = PortableHudLayout.Group.NONE;
            this.source = "";
        }
    }

    private static final class Cached {

        PacketSyncPortableMonitor snapshot;
        PacketSyncPortableMonitor mutationResponse;
        int sequence, action, value, result;
        UUID targetPanel = new UUID(0, 0);
        long sentAt, receivedAt;
        long receiptRevision;
        String owner = "", requestOwner = "";
        long bindingRevision = Long.MIN_VALUE;
        boolean inFlight;
        boolean dirty;
        int selected = -1;
        int dashboardSelected = -1;
        boolean dashboardRequest;
        ItemStack stack;
        PacketRequestPortableMonitor request;
        PacketSyncPortableMonitor renderSnapshot;
        int renderOptions;
        List<PinnedLine> renderLines;
        int pinsHash = Integer.MIN_VALUE;
        boolean hasPins;
        final Map<UUID, PortablePanelView> views = new HashMap<>();
        final Map<UUID, Long> viewVersions = new HashMap<>();
        final Map<UUID, PacketSyncPortableMonitor.Panel> viewSources = new HashMap<>();
    }
}
