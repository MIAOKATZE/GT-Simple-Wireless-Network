package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.util.EUDataSet;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Row;
import com.miaokatze.gtswn.network.PortablePanelRenderData;

public class WirelessGridReadingTest {

    @Test
    public void terminalProjectionReusesExactHudHistoryWithoutAnyMountedScreen() {
        EUDataSet sampler = new EUDataSet(3);
        BigInteger huge = BigInteger.TEN.pow(400);
        sampler.add(huge, 100);
        WirelessGridReading cold = WirelessGridReading.readTerminal(huge, sampler.snapshot(), "Terminal", 123);
        assertEquals(new BigDecimal(huge), cold.stored);
        assertNull(cold.netRate);
        assertEquals("warming", cold.state);
        assertTrue(Double.isNaN(cold.history.get(0).eut));
        sampler.add(huge.subtract(BigInteger.valueOf(975)), 150);
        WirelessGridReading live = WirelessGridReading.readTerminal(huge, sampler.snapshot(), "Terminal", 124);
        assertEquals(-19.5, live.netRate.doubleValue(), 0);
        assertEquals(sampler.calculateRecentEUT(), live.netRate.doubleValue(), 0);
        assertEquals("discharging", live.state);
        assertEquals("Terminal", live.source);
        assertTrue(live.fresh);
        assertEquals(124, live.receivedAtMillis);
        assertEquals(1, cold.history.size());
        sampler.add(huge.subtract(BigInteger.valueOf(975)), 200);
        assertEquals("stable", WirelessGridReading.readTerminal(huge, sampler.snapshot(), "Terminal", 125).state);
        sampler.add(huge, 250);
        assertEquals(
            3,
            sampler.snapshot()
                .size());
        assertEquals(100, cold.history.get(0).tick);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void terminalHistoryCannotMutateOriginalHudSampler() {
        EUDataSet sampler = new EUDataSet();
        sampler.add(BigInteger.TEN, 100);
        sampler.snapshot()
            .clear();
    }

    @Test
    public void missingRevokedAndUnavailableScreensNeverBecomeZero() {
        assertNull(WirelessGridReading.read(null, false).stored);
        Panel panel = panel();
        panel.currentEnergy = "0";
        assertNull(WirelessGridReading.read(panel, false).stored);
        panel.state = 1;
        assertNull(WirelessGridReading.read(panel, true).stored);
        panel.state = 0;
        panel.binding = null;
        assertNull(WirelessGridReading.read(panel, true).stored);
    }

    @Test
    public void exactHugeStockAndUnknownColdRateRemainIndependent() {
        Panel panel = panel();
        panel.currentEnergy = BigInteger.TEN.pow(400)
            .toString();
        panel.render.eu.add(new NetworkInfoSample(1000, 100, BigInteger.TEN, 0));
        WirelessGridReading reading = WirelessGridReading.read(panel, true);
        assertEquals(new BigDecimal(BigInteger.TEN.pow(400)), reading.stored);
        assertNull(reading.netRate);
        assertEquals("warming", reading.state);
        panel.currentEnergy = "invalid";
        assertNull(WirelessGridReading.read(panel, true).stored);
    }

    @Test
    public void netChangeUsesScalarReceiptNeverNormalizedChartOrDevicePower() {
        Panel panel = panel();
        panel.currentEnergy = "12";
        panel.render.eu.add(new NetworkInfoSample(1000, 100, BigInteger.TEN, 0));
        panel.render.eu.add(new NetworkInfoSample(2000, 200, BigInteger.valueOf(12), 2));
        panel.euRateChart = new double[] { 0.2, 0.8 };
        Row eu = new Row();
        eu.kind = 0;
        eu.key = "eu";
        eu.rate = -19.5;
        panel.rows.add(eu);
        WirelessGridReading reading = WirelessGridReading.read(panel, true);
        assertEquals(0, new BigDecimal("-19.5").compareTo(reading.netRate));
        assertEquals("discharging", reading.state);
        eu.rate = 0;
        assertEquals("stable", WirelessGridReading.read(panel, true).state);
        eu.rate = Double.NaN;
        assertNull(WirelessGridReading.read(panel, true).netRate);
        panel.render = null;
        assertNull(WirelessGridReading.read(panel, true).netRate);
    }

    @Test
    public void nonzeroFirstSlopeIsUsableAndExplicitColdZeroStaysUnknown() {
        Panel panel = panel();
        panel.render.eu.add(new NetworkInfoSample(1000, 100, BigInteger.TEN, 3));
        assertEquals("charging", WirelessGridReading.read(panel, true).state);
        panel.render.eu.add(new NetworkInfoSample(2000, 200, BigInteger.TEN, 0));
        panel.render.config.setBoolean("portableCold", true);
        assertNull(WirelessGridReading.read(panel, true).netRate);
    }

    @Test
    public void stockPlotPreservesTinyChangesAtHugeStock() {
        BigDecimal huge = new BigDecimal(BigInteger.TEN.pow(400));
        FactoryTrend plot = new FactoryTrend(new BigDecimal[] { huge, null, huge.add(BigDecimal.TEN) }, false);
        assertEquals(1 / 12.0, plot.normalized[0], 1e-12);
        assertEquals(11 / 12.0, plot.normalized[2], 1e-12);
        assertTrue(Double.isNaN(plot.normalized[1]));
        FactoryTrend flat = new FactoryTrend(new BigDecimal[] { huge, huge }, false);
        assertEquals(.5, flat.normalized[0], 1e-12);
        FactoryTrend signed = new FactoryTrend(new BigDecimal[] { BigDecimal.valueOf(-10), BigDecimal.TEN }, true);
        assertEquals(.5, signed.zero, 0);
        FactoryTrend zero = new FactoryTrend(new BigDecimal[] { BigDecimal.ZERO }, false);
        assertEquals("0", zero.minimum);
        assertEquals(0, zero.normalized[0], 0);
    }

    private static Panel panel() {
        Panel panel = new Panel();
        panel.binding = new MonitorBindings.Binding(0, 1, 2, 3, UUID.randomUUID());
        panel.render = new PortablePanelRenderData();
        return panel;
    }
}
