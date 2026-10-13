package com.miaokatze.gtswn.network;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashSet;
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
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;
import com.miaokatze.gtswn.common.panel.AEMonitorDataStore;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import baubles.api.BaublesApi;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** At most one pending request/player, 256 globally, 16 serviced/tick, 20t/player. */
public final class PortableMonitorRequestQueue {

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
            if (remaining-- <= 0) break;
            Request request = entry.getValue();
            if (!PENDING.remove(entry.getKey(), request)) continue;
            EntityPlayerMP player = request.player;
            if (player.playerNetServerHandler == null || player.worldObj == null
                || serverTick < nextAllowed.getOrDefault(player, 0L)) continue;
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
        if (request.action != 0 && !owner) return;
        PacketSyncPortableMonitor previous = actionReplay.get(player);
        if (request.action != 0 && previous != null
            && previous.terminalId.equals(request.terminalId)
            && previous.sequence == request.sequence) {
            GTSWNPacketHandler.NETWORK.sendTo(previous, player);
            return;
        }
        if (request.action == 1) MonitorBindings.remove(stack, request.value);
        if (request.action == 2)
            stack.stackTagCompound.setInteger(PortableWirelessNetworkMonitor.NBT_HUD_MODE, request.value);
        if (request.action != 0) player.inventory.markDirty();
        PacketSyncPortableMonitor response = new PacketSyncPortableMonitor(
            request.terminalId,
            request.sequence,
            PortableWirelessNetworkMonitor.getHudMode(stack));
        int panelIndex = 0;
        for (MonitorBindings.Binding binding : MonitorBindings.read(stack)) {
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
                        boolean detail = request.selected == panelIndex;
                        data.ownerName = panel.getOwnerName();
                        if (detail || !pins.isEmpty()) fill(data, panel, detail, pins, request.charts);
                    }
                }
            }
            response.panels.add(data);
            panelIndex++;
        }
        if (request.action != 0) actionReplay.put(player, response);
        GTSWNPacketHandler.NETWORK.sendTo(response, player);
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
        Set<String> requestedPins, boolean charts) {
        data.detail = detail;
        data.ownerName = panel.getOwnerName();
        data.window = panel.getWindowName();
        data.tab = panel.getCurrentTab();
        data.aeConnected = panel.isAEConnected();
        boolean needsEU = detail || requestedPins.contains("eu");
        if (needsEU) {
            PacketSyncPortableMonitor.Row eu = new PacketSyncPortableMonitor.Row();
            eu.key = "eu";
            eu.name = "EU";
            eu.amount = panel.getCachedEu()
                .toString();
            if (eu.amount.length() > 512)
                eu.amount = com.miaokatze.gtswn.common.util.FormatUtil.formatScientific(panel.getCachedEu());
            List<NetworkInfoSample> samples = panel.getCachedSamples();
            List<BigDecimal> euValues = new ArrayList<>();
            if (detail || charts) for (NetworkInfoSample sample : samples) euValues.add(new BigDecimal(sample.eu));
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
            if (detail || requestedPins.contains("eu")) data.rows.add(eu);
            if (detail || charts && requestedPins.contains("eu")) data.euChart = normalize(euValues);
        }
        if (!detail && requestedPins.stream()
            .allMatch("eu"::equals)) return;
        AEMonitorDataSet set = AEMonitorDataStore.get(panel.getWorldObj())
            .getIfPresent(panel.getAEMonitorDataKey());
        Set<String> keys = new HashSet<>();
        addItem(data, keys, set, panel.getChartItem(), detail, requestedPins);
        addFluid(data, keys, set, panel.getChartFluid(), detail, requestedPins);
        for (ItemStack item : panel.getMonitoredItems()) addItem(data, keys, set, item, detail, requestedPins);
        for (FluidStack fluid : panel.getMonitoredFluids()) addFluid(data, keys, set, fluid, detail, requestedPins);
        String chartKey = panel.getChartItem() != null ? TileEntityNetworkInfoPanel.getAEKey(panel.getChartItem())
            : panel.getChartFluid() != null ? TileEntityNetworkInfoPanel.getAEKey(panel.getChartFluid()) : null;
        List<BigDecimal> aeValues = new ArrayList<>();
        if ((detail || charts && !requestedPins.isEmpty()) && set != null && chartKey != null) {
            for (AEMonitorSample sample : set.query(chartKey, panel.getAETrackingWindow())) {
                aeValues.add(BigDecimal.valueOf(sample.amount));
            }
        }
        if (detail || charts) data.aeChart = normalize(aeValues);
    }

    private static void addItem(PacketSyncPortableMonitor.Panel data, Set<String> keys, AEMonitorDataSet set,
        ItemStack item, boolean detail, Set<String> pins) {
        if (item != null && (detail || pins.contains(TileEntityNetworkInfoPanel.getAEKey(item))))
            addRow(data, keys, set, TileEntityNetworkInfoPanel.getAEKey(item), item.getDisplayName(), 1);
    }

    private static void addFluid(PacketSyncPortableMonitor.Panel data, Set<String> keys, AEMonitorDataSet set,
        FluidStack fluid, boolean detail, Set<String> pins) {
        if (fluid != null && (detail || pins.contains(TileEntityNetworkInfoPanel.getAEKey(fluid))))
            addRow(data, keys, set, TileEntityNetworkInfoPanel.getAEKey(fluid), fluid.getLocalizedName(), 2);
    }

    private static void addRow(PacketSyncPortableMonitor.Panel data, Set<String> keys, AEMonitorDataSet set, String key,
        String name, int kind) {
        if (data.rows.size() >= PacketSyncPortableMonitor.MAX_ROWS || !keys.add(key)) return;
        PacketSyncPortableMonitor.Row row = new PacketSyncPortableMonitor.Row();
        row.key = key;
        row.name = name;
        row.kind = kind;
        AEMonitorSample latest = set == null ? null : set.newest(key);
        row.amount = latest == null ? "..." : Long.toString(latest.amount);
        row.rate = latest == null ? 0 : latest.rate;
        row.average = set == null ? 0 : set.averageRate300s(key);
        data.rows.add(row);
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
