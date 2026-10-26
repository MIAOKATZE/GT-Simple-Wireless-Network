package com.miaokatze.gtswn.client;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;
import com.miaokatze.gtswn.network.PortablePanelRenderData;

/** Detached, read-only tile facade built on receipt; never installed in a world or attached to AE. */
public final class PortablePanelView extends TileEntityNetworkInfoPanel {

    private PacketSyncPortableMonitor.Panel source;
    private final PortablePanelRenderData render;
    private final List<ItemStack> items = new ArrayList<>();
    private final List<FluidStack> fluids = new ArrayList<>();
    private final Map<String, AEMonitorSample> latest = new HashMap<>();
    private final Map<String, Double> averages = new HashMap<>();
    private ItemStack chartItem;
    private FluidStack chartFluid;
    private BigInteger energy = BigInteger.ZERO;
    private int viewTab;
    private boolean energyAvailable;
    private final List<NetworkInfoSample> euSamples;
    private final List<AEMonitorSample> aeSamples;
    private String status;
    private long liveFingerprint = Long.MIN_VALUE;
    private final com.miaokatze.gtswn.common.panel.EUCacheBridge statusFormatter;
    private final boolean energySeriesAvailable;

    public PortablePanelView(PacketSyncPortableMonitor.Panel source) {
        this.source = source;
        this.render = source.render;
        if (render == null) throw new IllegalArgumentException("Full panel data required");
        readPortableRenderConfig(render.config);
        try {
            energy = new BigInteger(render.currentEnergy);
            energyAvailable = true;
        } catch (NumberFormatException ignored) {}
        euSamples = Collections.unmodifiableList(render.eu);
        aeSamples = Collections.unmodifiableList(render.ae);
        boolean finite = true;
        for (NetworkInfoSample sample : render.eu) if (!Double.isFinite(sample.eu.doubleValue())) {
            finite = false;
            break;
        }
        energySeriesAvailable = finite;
        chartItem = item(render.chartKey);
        chartFluid = fluid(render.chartKey);
        com.miaokatze.gtswn.common.panel.PanelConfigStore settings = new com.miaokatze.gtswn.common.panel.PanelConfigStore(
            () -> {});
        settings.readSync(render.config);
        statusFormatter = new com.miaokatze.gtswn.common.panel.EUCacheBridge(this, settings);
        updateLive(source);
    }

    /** Update live numbers independently of the long-window history/configuration generation. */
    public void updateLive(PacketSyncPortableMonitor.Panel source) {
        this.source = source;
        long fingerprint = source.currentEnergy.hashCode() * 31L + (source.aeConnected ? 1 : 0);
        for (PacketSyncPortableMonitor.Row row : source.rows) {
            if (row.key.equals(render.chartKey) && row.icon != null) {
                chartItem = row.icon.item;
                chartFluid = row.icon.fluid;
            }
            fingerprint = fingerprint * 31 + row.key.hashCode();
            fingerprint = fingerprint * 31 + row.amount.hashCode();
            fingerprint = fingerprint * 31 + row.sampleTime;
            fingerprint = fingerprint * 31 + row.sampleTick;
            fingerprint = fingerprint * 31 + Double.doubleToLongBits(row.rate);
            fingerprint = fingerprint * 31 + Double.doubleToLongBits(row.average);
            fingerprint = fingerprint * 31 + (row.monitored ? 1 : 0);
            fingerprint = fingerprint * 31 + (row.icon == null ? 0 : row.icon.fingerprint());
        }
        if (fingerprint == liveFingerprint) return;
        liveFingerprint = fingerprint;
        items.clear();
        fluids.clear();
        latest.clear();
        averages.clear();
        double power = render.eu.isEmpty() ? 0 : render.eu.get(render.eu.size() - 1).eut;
        if (!source.currentEnergy.isEmpty()) {
            try {
                energy = new BigInteger(source.currentEnergy);
                energyAvailable = true;
            } catch (NumberFormatException ignored) {
                energyAvailable = false;
            }
        }
        for (PacketSyncPortableMonitor.Row row : source.rows) {
            if (row.kind == 0) {
                power = row.rate;
                if (source.currentEnergy.isEmpty()) try {
                    energy = new BigInteger(row.amount);
                    energyAvailable = true;
                } catch (NumberFormatException ignored) {
                    energyAvailable = false;
                }
            } else {
                ItemStack item = row.icon == null ? item(row.key) : row.icon.item;
                FluidStack fluid = row.icon == null ? fluid(row.key) : row.icon.fluid;
                if (row.monitored && item != null) items.add(item);
                if (row.monitored && fluid != null) fluids.add(fluid);
                try {
                    latest.put(
                        row.key,
                        new AEMonitorSample(row.sampleTime, row.sampleTick, Long.parseLong(row.amount), row.rate));
                } catch (NumberFormatException ignored) {}
                averages.put(row.key, row.average);
            }
        }
        boolean silent = render.eu.size() >= 2
            && render.eu.get(render.eu.size() - 1).tick - render.eu.get(0).tick >= 6000;
        for (NetworkInfoSample sample : render.eu) if (!sample.eu.equals(render.eu.get(0).eu)) {
            silent = false;
            break;
        }
        boolean cold = render.config.hasKey("portableCold") ? render.config.getBoolean("portableCold")
            : render.eu.size() < 2;
        boolean longSilent = render.config.hasKey("portableLongSilent") ? render.config.getBoolean("portableLongSilent")
            : silent;
        status = statusFormatter.formatStatus(power, cold, longSilent && power == 0);
    }

    private static ItemStack item(String key) {
        if (!key.startsWith("item:")) return null;
        int split = key.lastIndexOf(':');
        if (split <= 5) return null;
        try {
            Object item = Item.itemRegistry.getObject(key.substring(5, split));
            return item instanceof Item ? new ItemStack((Item) item, 1, Integer.parseInt(key.substring(split + 1)))
                : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static FluidStack fluid(String key) {
        if (!key.startsWith("fluid:")) return null;
        return FluidRegistry.getFluidStack(key.substring(6), 1000);
    }

    public void selectTab(int tab) {
        viewTab = Math.max(0, Math.min(2, tab));
    }

    @Override
    public int getCurrentTab() {
        return viewTab;
    }

    @Override
    public BigInteger getCachedEu() {
        return energy;
    }

    @Override
    public boolean isShowBriefEnergy() {
        return energyAvailable && super.isShowBriefEnergy();
    }

    public boolean energyAvailable() {
        return energyAvailable;
    }

    public boolean energySeriesAvailable() {
        return energySeriesAvailable;
    }

    @Override
    public boolean isShowChartEnergy() {
        return energySeriesAvailable && super.isShowChartEnergy();
    }

    @Override
    public List<NetworkInfoSample> getCachedSamples() {
        return euSamples;
    }

    @Override
    public String getOwnerName() {
        return source.ownerName;
    }

    @Override
    public boolean isAEConnected() {
        return source.aeConnected;
    }

    @Override
    public ItemStack getChartItem() {
        return chartItem;
    }

    @Override
    public FluidStack getChartFluid() {
        return chartFluid;
    }

    @Override
    public List<AEMonitorSample> getAEChartSamples() {
        return aeSamples;
    }

    @Override
    public List<ItemStack> getMonitoredItems() {
        return items;
    }

    @Override
    public List<FluidStack> getMonitoredFluids() {
        return fluids;
    }

    @Override
    public Map<String, AEMonitorSample> getAEMonitorLatest() {
        return latest;
    }

    @Override
    public Map<String, Double> getAEMonitorAvg300s() {
        return averages;
    }

    @Override
    public String getCachedStatus() {
        return status;
    }
}
