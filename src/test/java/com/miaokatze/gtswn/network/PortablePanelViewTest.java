package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.math.BigInteger;

import org.junit.Test;

import com.miaokatze.gtswn.client.PortablePanelView;
import com.miaokatze.gtswn.client.render.RenderNetworkInfoPanel;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;

public class PortablePanelViewTest {

    @Test
    public void oversizedHistoryHidesOnlyUnsafeEnergyCurveAndKeepsExactLiveEnergyAndPower() {
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.render = new PortablePanelRenderData();
        BigInteger energy = BigInteger.TEN.pow(400);
        panel.currentEnergy = energy.toString();
        panel.render.currentEnergy = panel.currentEnergy;
        panel.render.config.setBoolean("showChartEnergy", true);
        panel.render.config.setBoolean("showChartStatus", true);
        panel.render.eu.add(new NetworkInfoSample(1, 1, energy, -50));
        panel.render.eu.add(new NetworkInfoSample(2, 2, energy.add(BigInteger.ONE), 75));
        PortablePanelView view = new PortablePanelView(panel);
        assertEquals(energy, view.getCachedEu());
        assertTrue(view.energyAvailable());
        assertFalse(view.energySeriesAvailable());
        assertFalse(view.isShowChartEnergy());
        assertTrue(view.isShowChartStatus());
        assertEquals(
            75,
            view.getCachedSamples()
                .get(1).eut,
            0);
    }

    @Test
    public void maxFiniteRangePaddingAxesAndNormalizationStayFinite() throws Exception {
        java.lang.reflect.Method range = RenderNetworkInfoPanel.class
            .getDeclaredMethod("rawRange", double[].class, Double.class, Double.class);
        range.setAccessible(true);
        double[] bounds = (double[]) range
            .invoke(null, new double[] { -Double.MAX_VALUE, 0, Double.MAX_VALUE }, null, null);
        assertTrue(Double.isFinite(bounds[0]));
        assertTrue(Double.isFinite(bounds[1]));
        java.lang.reflect.Method axis = RenderNetworkInfoPanel.class
            .getDeclaredMethod("axisValue", double[].class, int.class);
        axis.setAccessible(true);
        for (int i = 0; i < 5; i++) assertTrue(Double.isFinite((double) axis.invoke(null, bounds, i)));
        java.lang.reflect.Method normalize = RenderNetworkInfoPanel.class
            .getDeclaredMethod("normalizedValues", double[].class, double[].class);
        normalize.setAccessible(true);
        assertArrayEquals(
            new double[] { 0, .5, 1 },
            (double[]) normalize.invoke(null, new double[] { -Double.MAX_VALUE, 0, Double.MAX_VALUE }, bounds),
            0);
        assertNull(normalize.invoke(null, new double[] { Double.POSITIVE_INFINITY, 1 }, bounds));
    }

    @Test
    public void chartBindingDoesNotBecomeNativeMonitorUntilExplicitlyMonitored() throws Exception {
        // Use the actual native registry without requiring a LaunchClassLoader/Forge mod lifecycle.
        net.minecraft.item.Item item = new net.minecraft.item.Item();
        java.lang.reflect.Method register = net.minecraft.item.Item.itemRegistry.getClass()
            .getDeclaredMethod("addObjectRaw", int.class, String.class, Object.class);
        register.setAccessible(true);
        register.invoke(net.minecraft.item.Item.itemRegistry, 31888, "minecraft:gtswn_portable_role_fixture", item);
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.render = new PortablePanelRenderData();
        panel.render.chartKey = "item:minecraft:gtswn_portable_role_fixture:0";
        PacketSyncPortableMonitor.Row chart = new PacketSyncPortableMonitor.Row();
        chart.key = panel.render.chartKey;
        chart.kind = 1;
        chart.amount = "500";
        panel.rows.add(chart);
        PortablePanelView view = new PortablePanelView(panel);
        assertNotNull(view.getChartItem());
        assertTrue(
            view.getMonitoredItems()
                .isEmpty());
        chart.monitored = true;
        view.updateLive(panel);
        assertEquals(
            1,
            view.getMonitoredItems()
                .size());
        assertEquals(
            500,
            view.getAEMonitorLatest()
                .get(chart.key).amount);
    }

    @Test
    public void longWindowHistoryRemainsStableWhileLiveBalanceAndPowerUpdate() {
        PacketSyncPortableMonitor.Panel initial = new PacketSyncPortableMonitor.Panel();
        initial.render = new PortablePanelRenderData();
        initial.render.config.setInteger("trackingWindow", 7);
        initial.render.currentEnergy = "100";
        initial.currentEnergy = "100";
        initial.render.eu.add(new NetworkInfoSample(1, 1, BigInteger.valueOf(100), 1));
        initial.render.eu.add(new NetworkInfoSample(2, 2, BigInteger.valueOf(200), 2));
        PacketSyncPortableMonitor.Row eu = new PacketSyncPortableMonitor.Row();
        eu.kind = 0;
        eu.key = "eu";
        eu.amount = "100";
        eu.rate = 1;
        initial.rows.add(eu);
        PortablePanelView view = new PortablePanelView(initial);
        java.util.List<NetworkInfoSample> history = view.getCachedSamples();
        PacketSyncPortableMonitor.Panel live = new PacketSyncPortableMonitor.Panel();
        live.render = initial.render;
        live.currentEnergy = "9000";
        live.aeConnected = true;
        PacketSyncPortableMonitor.Row changed = new PacketSyncPortableMonitor.Row();
        changed.kind = 0;
        changed.key = "eu";
        changed.amount = "9000";
        changed.rate = -50;
        live.rows.add(changed);
        view.updateLive(live);
        assertEquals(BigInteger.valueOf(9000), view.getCachedEu());
        assertSame(history, view.getCachedSamples());
        assertTrue(view.isAEConnected());
        assertEquals(
            7,
            view.getPortableRenderConfig()
                .getInteger("trackingWindow"));
    }

    @Test
    public void constantHighEuHasFiniteVisibleAxisRange() throws Exception {
        java.lang.reflect.Method method = RenderNetworkInfoPanel.class
            .getDeclaredMethod("rawRange", double[].class, Double.class, Double.class);
        method.setAccessible(true);
        double[] bounds = (double[]) method.invoke(null, new double[] { 1e20, 1e20, 1e20 }, null, null);
        assertTrue(Double.isFinite(bounds[0]));
        assertTrue(Double.isFinite(bounds[1]));
        assertTrue(bounds[1] > bounds[0]);
        assertEquals(.5, (1e20 - bounds[0]) / (bounds[1] - bounds[0]), 1e-8);
    }
}
