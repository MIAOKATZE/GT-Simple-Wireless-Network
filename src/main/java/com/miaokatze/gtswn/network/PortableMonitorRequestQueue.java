package com.miaokatze.gtswn.network;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.hud.PortableMonitorPins;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;
import com.miaokatze.gtswn.common.panel.AEMonitorDataStore;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.panel.WindowLabel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import baubles.api.BaublesApi;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** At most one pending request/player, 256 globally, 16 serviced/tick, 20t/player. */
public final class PortableMonitorRequestQueue {

    public static final String EU_GRAPH = "$chart:eu", AE_GRAPH = "$chart:ae";
    private static final Map<TileEntityNetworkInfoPanel, RenderCache> RENDER_CACHE = new WeakHashMap<>();
    private static long nextRenderVersion;

    private static final ConcurrentHashMap<UUID, Request> PENDING = new ConcurrentHashMap<>();
    private final Map<EntityPlayerMP, Long> nextAllowed = new WeakHashMap<>();
    private final Map<EntityPlayerMP, PacketSyncPortableMonitor> actionReplay = new WeakHashMap<>();
    private long serverTick;

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new PortableMonitorRequestQueue());
    }

    public static void enqueue(EntityPlayerMP player, PacketRequestPortableMonitor message) {
        if (player == null) return;
        synchronized (PENDING) {
            if (PENDING.size() >= 256) return;
            PENDING.putIfAbsent(player.getUniqueID(), new Request(player, message));
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        serverTick++;
        int remaining = 16;
        for (Map.Entry<UUID, Request> entry : PENDING.entrySet()) {
            Request request = entry.getValue();
            EntityPlayerMP player = request.player;
            if (player.playerNetServerHandler == null || player.worldObj == null) {
                PENDING.remove(entry.getKey(), request);
                continue;
            }
            // A valid single-flight action must survive the cooldown, not wait for a transport retry.
            if (serverTick < nextAllowed.getOrDefault(player, 0L)) continue;
            if (remaining-- <= 0) break;
            if (!PENDING.remove(entry.getKey(), request)) continue;
            nextAllowed.put(player, serverTick + 20);
            try {
                process(player, request.message);
            } catch (RuntimeException failure) {
                GTSimpleWirelessNetwork.LOG.error("Portable monitor snapshot failed", failure);
            }
        }
    }

    private void process(EntityPlayerMP player, PacketRequestPortableMonitor request) {
        ItemStack stack = find(player, request.terminalId);
        if (stack == null) return;
        // Viewing follows possession of a physically bound terminal; mutation requires its current owner.
        boolean owner = player.getUniqueID()
            .toString()
            .equals(PortableWirelessNetworkMonitor.getOwnerUUID(stack));
        PacketSyncPortableMonitor previous = actionReplay.get(player);
        if (request.action != 0 && previous != null
            && previous.terminalId.equals(request.terminalId)
            && previous.sequence == request.sequence) {
            GTSWNPacketHandler.NETWORK.sendTo(previous, player);
            return;
        }
        // Older retries must never remove a screen rebound after a later acknowledged action.
        if (request.action != 0 && previous != null && request.sequence - previous.sequence <= 0) return;
        int result = request.action == 0 ? 0 : owner ? 1 : 2;
        if (request.action == 1 && owner && !MonitorBindings.remove(stack, request.targetPanel)) result = 3;
        if (request.action == 2 && owner)
            stack.stackTagCompound.setInteger(PortableWirelessNetworkMonitor.NBT_HUD_MODE, request.value);
        if (request.action != 0 && owner) {
            MonitorBindings.synchronize(player);
        }
        PacketSyncPortableMonitor response = new PacketSyncPortableMonitor(
            request.terminalId,
            request.sequence,
            PortableWirelessNetworkMonitor.getHudMode(stack));
        response.result = result;
        response.bindingRevision = MonitorBindings.revision(stack);
        List<MonitorBindings.Binding> bindings = MonitorBindings.read(stack);
        int selected = selectedIndex(request, bindings, owner, result);
        int panelIndex = 0;
        for (MonitorBindings.Binding binding : bindings) {
            PacketSyncPortableMonitor.Panel data = new PacketSyncPortableMonitor.Panel();
            data.binding = binding;
            World world = DimensionManager.getWorld(binding.dimension);
            // blockExists tests loaded chunks. Never load a dimension or chunk for remote viewing.
            if (world == null || !world.blockExists(binding.x, binding.y, binding.z)) {
                data.state = 1;
            } else {
                TileEntity tile = world.getTileEntity(binding.x, binding.y, binding.z);
                if (!(tile instanceof TileEntityNetworkInfoPanel)) {
                    data.state = 2;
                } else {
                    TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
                    if (!binding.panelId.equals(panel.getPortableMonitorId())) data.state = 3;
                    else {
                        Set<String> pins = request.pins.getOrDefault(binding.panelId, java.util.Collections.emptySet());
                        boolean detail = selected == panelIndex;
                        if (detail || !pins.isEmpty()) fill(
                            data,
                            panel,
                            detail,
                            pins,
                            request.charts,
                            request.action == 0 ? request.knownRenderVersion : 0,
                            request.action == 0 && request.reuseCharts);
                        else fillSummary(data, panel);
                    }
                }
            }
            response.panels.add(data);
            panelIndex++;
        }
        if (request.action != 0) actionReplay.put(player, response);
        GTSWNPacketHandler.NETWORK.sendTo(response, player);
    }

    static int selectedIndex(PacketRequestPortableMonitor request, List<MonitorBindings.Binding> bindings) {
        if (request.selected < 0) return -1;
        if (!request.selectedPanel.equals(new UUID(0, 0))) {
            for (int i = 0; i < bindings.size(); i++)
                if (bindings.get(i).panelId.equals(request.selectedPanel)) return i;
            return -1;
        }
        return Math.min(request.selected, bindings.size() - 1);
    }

    /** Only an acknowledged removal of the selected screen may select its remaining neighbour. */
    static int selectedIndex(PacketRequestPortableMonitor request, List<MonitorBindings.Binding> bindings,
        boolean owner, int result) {
        int selected = selectedIndex(request, bindings);
        if (selected < 0 && request.selected >= 0
            && owner
            && result == 1
            && request.action == 1
            && request.selectedPanel.equals(request.targetPanel)
            && !bindings.isEmpty()) return Math.min(request.selected, bindings.size() - 1);
        return selected;
    }

    private static ItemStack find(EntityPlayerMP player, UUID id) {
        ItemStack held = player.getHeldItem();
        if (id.equals(MonitorBindings.id(held))) return held;
        try {
            IInventory baubles = BaublesApi.getBaubles(player);
            if (baubles != null) for (int i = 0; i < baubles.getSizeInventory(); i++) {
                ItemStack stack = baubles.getStackInSlot(i);
                if (id.equals(MonitorBindings.id(stack))) return stack;
            }
        } catch (NoClassDefFoundError ignored) {}
        for (ItemStack stack : player.inventory.mainInventory) if (id.equals(MonitorBindings.id(stack))) return stack;
        return null;
    }

    private static void fill(PacketSyncPortableMonitor.Panel data, TileEntityNetworkInfoPanel panel, boolean detail,
        Set<String> requestedPins, boolean charts, long knownRenderVersion, boolean reuseCharts) {
        data.detail = detail;
        fillSummary(data, panel);
        boolean needsEU = detail || requestedPins.contains("eu");
        if (needsEU) {
            String exactEnergy = panel.getCachedEu()
                .toString();
            data.currentEnergy = exactEnergy.length() <= 4096 ? exactEnergy : "";
            PacketSyncPortableMonitor.Row eu = new PacketSyncPortableMonitor.Row();
            eu.key = "eu";
            eu.name = "EU";
            eu.amount = panel.getCachedEu()
                .toString();
            if (eu.amount.length() > 512)
                eu.amount = com.miaokatze.gtswn.common.util.FormatUtil.formatScientific(panel.getCachedEu());
            List<NetworkInfoSample> samples = panel.getCachedSamples();
            if (!samples.isEmpty()) {
                NetworkInfoSample newest = samples.get(samples.size() - 1);
                data.sampleTime = newest.timeMs;
                eu.rate = newest.eut;
                if (samples.size() > 1) {
                    NetworkInfoSample first = samples.get(0);
                    long ticks = newest.tick - first.tick;
                    if (ticks > 0) eu.average = new BigDecimal(newest.eu.subtract(first.eu))
                        .divide(BigDecimal.valueOf(ticks), MathContext.DECIMAL64)
                        .doubleValue();
                }
            }
            eu.rate = panel.getCachedEut();
            if (needsEU) data.rows.add(eu);
        }
        AEMonitorDataSet set = AEMonitorDataStore.get(panel.getWorldObj())
            .getIfPresent(panel.getAEMonitorDataKey());
        Map<String, PacketSyncPortableMonitor.Row> keys = new HashMap<>();
        addItem(data, keys, set, panel.getChartItem(), detail, requestedPins, false);
        addFluid(data, keys, set, panel.getChartFluid(), detail, requestedPins, false);
        for (ItemStack item : panel.getMonitoredItems()) addItem(data, keys, set, item, detail, requestedPins, true);
        for (FluidStack fluid : panel.getMonitoredFluids())
            addFluid(data, keys, set, fluid, detail, requestedPins, true);
        String chartKey = panel.getChartItem() != null ? TileEntityNetworkInfoPanel.getAEKey(panel.getChartItem())
            : panel.getChartFluid() != null ? TileEntityNetworkInfoPanel.getAEKey(panel.getChartFluid()) : null;
        data.chartKey = chartKey == null ? "" : chartKey;
        data.aeWindow = aeWindow(set, chartKey, panel.getAETrackingWindow());
        if (requestedPins.contains(AE_GRAPH) || requestedPins.contains(PortableMonitorPins.AE_TITLE)
            || requestedPins.contains(PortableMonitorPins.AE_PLOT)) {
            addItem(data, keys, set, panel.getChartItem(), true, requestedPins, false);
            addFluid(data, keys, set, panel.getChartFluid(), true, requestedPins, false);
        }
        boolean euGraph = needsEUChart(detail, requestedPins, charts);
        boolean aeGraph = needsAEChart(detail, requestedPins);
        if (euGraph || aeGraph) {
            RenderCache cache = renderCache(panel, set, chartKey);
            publishRender(data, cache, euGraph, aeGraph, knownRenderVersion, reuseCharts);
        }
    }

    /** Real loaded-screen state without reading rows, histories, stores or render caches. */
    static void fillSummary(PacketSyncPortableMonitor.Panel data, TileEntityNetworkInfoPanel panel) {
        data.ownerName = panel.getOwnerName();
        data.window = panel.getWindowName();
        data.aeWindow = WindowLabel.formatSpan(0L);
        data.displayMode = panel.getDisplayMode();
        data.palette = panel.getTrendLinePalette();
        data.tab = panel.getCurrentTab();
        data.aeConnected = panel.isAEConnected();
    }

    public static boolean needsEUChart(boolean detail, Set<String> pins, boolean legacyCharts) {
        return detail || pins.contains(EU_GRAPH) || pins.contains(PortableMonitorPins.EU_PLOT);
    }

    static String aeWindow(AEMonitorDataSet set, String key, int window) {
        return WindowLabel.formatSpan(set == null || key == null ? 0L : set.spanTicks(key, window));
    }

    public static boolean needsAEChart(boolean detail, Set<String> pins) {
        return detail || pins.contains(AE_GRAPH) || pins.contains(PortableMonitorPins.AE_PLOT);
    }

    static RenderCache renderCache(TileEntityNetworkInfoPanel panel, AEMonitorDataSet set, String chartKey) {
        net.minecraft.nbt.NBTTagCompound config = panel.getPortableRenderConfig();
        List<NetworkInfoSample> eu = tail(panel.getCachedSamples());
        List<AEMonitorSample> ae = set == null || chartKey == null ? java.util.Collections.emptyList()
            : tail(set.query(chartKey, panel.getAETrackingWindow()));
        String key = chartKey == null ? "" : chartKey;
        RenderCache old = RENDER_CACHE.get(panel);
        // Compare the actual selected window, not a hash or the newest sample of a different window.
        // Time and interior samples matter even when the normalized chart shape is unchanged.
        if (old != null && old.render.config.equals(config)
            && old.render.chartKey.equals(key)
            && PortablePanelRenderData.sameEU(old.eu, eu)
            && PortablePanelRenderData.sameAE(old.ae, ae)) return old;
        RenderCache cache = new RenderCache();
        cache.version = nextVersion(nextRenderVersion);
        nextRenderVersion = cache.version;
        cache.eu = new ArrayList<>(eu);
        cache.ae = new ArrayList<>(ae);
        cache.render.config = (net.minecraft.nbt.NBTTagCompound) config.copy();
        cache.render.chartKey = key;
        String currentEnergy = panel.getCachedEu()
            .toString();
        cache.render.currentEnergy = currentEnergy.length() <= 4096 ? currentEnergy : "";
        List<BigDecimal> energy = new ArrayList<>(), power = new ArrayList<>(), amounts = new ArrayList<>(),
            rates = new ArrayList<>();
        for (NetworkInfoSample sample : eu) {
            if (sample.eu.toString()
                .length() <= 512) cache.render.eu.add(sample);
            energy.add(new BigDecimal(sample.eu));
            power.add(BigDecimal.valueOf(Double.isFinite(sample.eut) ? sample.eut : 0));
        }
        for (AEMonitorSample sample : ae) {
            cache.render.ae.add(sample);
            amounts.add(BigDecimal.valueOf(sample.amount));
            rates.add(BigDecimal.valueOf(Double.isFinite(sample.rate) ? sample.rate : 0));
        }
        cache.euChart = normalize(energy);
        cache.euRateChart = normalize(power);
        cache.aeChart = normalize(amounts);
        cache.aeRateChart = normalize(rates);
        RENDER_CACHE.put(panel, cache);
        return cache;
    }

    private static <T> List<T> tail(List<T> values) {
        return values.subList(Math.max(0, values.size() - PacketSyncPortableMonitor.MAX_SAMPLES), values.size());
    }

    static long nextVersion(long previous) {
        if (previous < 0 || previous == Long.MAX_VALUE)
            throw new IllegalStateException("Portable render revision exhausted");
        return previous + 1;
    }

    static void publishRender(PacketSyncPortableMonitor.Panel data, RenderCache cache, boolean euGraph, boolean aeGraph,
        long knownRenderVersion, boolean reuseCharts) {
        data.renderVersion = cache.version;
        data.chartsUnchanged = reuseCharts && data.detail
            && knownRenderVersion > 0
            && knownRenderVersion == cache.version;
        if (data.chartsUnchanged) return;
        if (euGraph) {
            data.euChart = cache.euChart.clone();
            data.euRateChart = cache.euRateChart.clone();
        }
        if (aeGraph) {
            data.aeChart = cache.aeChart.clone();
            data.aeRateChart = cache.aeRateChart.clone();
        }
        if (data.detail && knownRenderVersion != cache.version) data.render = cache.render.copy();
    }

    static final class RenderCache {

        long version;
        List<NetworkInfoSample> eu;
        List<AEMonitorSample> ae;
        final PortablePanelRenderData render = new PortablePanelRenderData();
        double[] euChart, euRateChart, aeChart, aeRateChart;
    }

    private static void addItem(PacketSyncPortableMonitor.Panel data, Map<String, PacketSyncPortableMonitor.Row> keys,
        AEMonitorDataSet set, ItemStack item, boolean detail, Set<String> pins, boolean monitored) {
        if (item != null && (detail || pins.contains(TileEntityNetworkInfoPanel.getAEKey(item)))) {
            addRow(data, keys, set, TileEntityNetworkInfoPanel.getAEKey(item), item.getDisplayName(), 1, monitored);
            PacketSyncPortableMonitor.Row row = keys.get(TileEntityNetworkInfoPanel.getAEKey(item));
            if (row != null && row.icon == null) row.icon = PortableMonitorIcon.cached(item);
        }
    }

    private static void addFluid(PacketSyncPortableMonitor.Panel data, Map<String, PacketSyncPortableMonitor.Row> keys,
        AEMonitorDataSet set, FluidStack fluid, boolean detail, Set<String> pins, boolean monitored) {
        if (fluid != null && (detail || pins.contains(TileEntityNetworkInfoPanel.getAEKey(fluid)))) {
            addRow(data, keys, set, TileEntityNetworkInfoPanel.getAEKey(fluid), fluid.getLocalizedName(), 2, monitored);
            PacketSyncPortableMonitor.Row row = keys.get(TileEntityNetworkInfoPanel.getAEKey(fluid));
            if (row != null && row.icon == null) row.icon = PortableMonitorIcon.cached(fluid);
        }
    }

    private static void addRow(PacketSyncPortableMonitor.Panel data, Map<String, PacketSyncPortableMonitor.Row> keys,
        AEMonitorDataSet set, String key, String name, int kind, boolean monitored) {
        PacketSyncPortableMonitor.Row existing = keys.get(key);
        if (existing != null) {
            existing.monitored |= monitored;
            return;
        }
        if (data.rows.size() >= PacketSyncPortableMonitor.MAX_ROWS) return;
        PacketSyncPortableMonitor.Row row = new PacketSyncPortableMonitor.Row();
        row.key = key;
        row.name = name;
        row.kind = kind;
        row.monitored = monitored;
        AEMonitorSample latest = set == null ? null : set.newest(key);
        row.amount = latest == null ? "..." : Long.toString(latest.amount);
        row.rate = latest == null ? 0 : latest.rate;
        row.average = set == null ? 0 : set.averageRate(key);
        data.rows.add(row);
        keys.put(key, row);
    }

    /** Normalize before conversion to double so arbitrary-size EU does not overflow charts. */
    static double[] normalize(List<BigDecimal> values) {
        int count = Math.min(PacketSyncPortableMonitor.MAX_SAMPLES, values.size());
        double[] normalized = new double[count];
        if (count == 0) return normalized;
        List<BigDecimal> tail = values.subList(values.size() - count, values.size());
        BigDecimal min = tail.get(0), max = min;
        for (BigDecimal value : tail) {
            min = min.min(value);
            max = max.max(value);
        }
        BigDecimal range = max.subtract(min);
        for (int i = 0; i < count; i++) normalized[i] = range.signum() == 0 ? .5
            : tail.get(i)
                .subtract(min)
                .divide(range, MathContext.DECIMAL64)
                .doubleValue();
        return normalized;
    }

    private static final class Request {

        final EntityPlayerMP player;
        final PacketRequestPortableMonitor message;

        Request(EntityPlayerMP player, PacketRequestPortableMonitor message) {
            this.player = player;
            this.message = message;
        }
    }
}
