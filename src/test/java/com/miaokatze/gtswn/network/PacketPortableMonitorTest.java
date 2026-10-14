package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketPortableMonitorTest {

    @Test
    public void protocol58IsRejectedBeforeIconLayoutCanBeRead() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            new PacketSyncPortableMonitor(UUID.randomUUID(), 1, 1).toBytes(bytes);
            bytes.setByte(4, 58);
            PacketSyncPortableMonitor response = new PacketSyncPortableMonitor();
            response.fromBytes(bytes);
            assertFalse(response.valid);
            assertTrue(response.panels.isEmpty());
        } finally {
            bytes.release();
        }
    }

    @Test
    public void removalUsesStablePanelIdentityAndReceivesExplicitAcknowledgement() {
        PacketRequestPortableMonitor request = new PacketRequestPortableMonitor(UUID.randomUUID(), 93, 1, 15);
        request.targetPanel = UUID.randomUUID();
        ByteBuf bytes = Unpooled.buffer();
        try {
            request.toBytes(bytes);
            assertEquals(80, bytes.readableBytes());
            PacketRequestPortableMonitor decoded = new PacketRequestPortableMonitor();
            decoded.fromBytes(bytes);
            assertTrue(decoded.valid);
            assertEquals(request.targetPanel, decoded.targetPanel);
            assertEquals(15, decoded.value); // Index is incidental; the server removes only targetPanel.
            bytes.clear();
            PacketSyncPortableMonitor response = new PacketSyncPortableMonitor(request.terminalId, request.sequence, 0);
            response.result = 2;
            response.toBytes(bytes);
            PacketSyncPortableMonitor acknowledgement = new PacketSyncPortableMonitor();
            acknowledgement.fromBytes(bytes);
            assertTrue(acknowledgement.valid);
            assertEquals(2, acknowledgement.result);
        } finally {
            bytes.release();
        }
    }

    @Test
    public void absentRemovalIdentityAndVersion56AreRejected() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            new PacketRequestPortableMonitor(UUID.randomUUID(), 1, 1, 0).toBytes(bytes);
            PacketRequestPortableMonitor decoded = new PacketRequestPortableMonitor();
            decoded.fromBytes(bytes);
            assertFalse(decoded.valid);
            bytes.clear();
            new PacketRequestPortableMonitor(UUID.randomUUID(), 1, 0, 0).toBytes(bytes);
            bytes.setInt(0, 0x50574d56);
            bytes.setByte(4, 56);
            decoded.fromBytes(bytes);
            assertFalse(decoded.valid);
        } finally {
            bytes.release();
        }
    }

    @Test
    public void version57PacketsAreRejectedBeforeReadingNewIdentityAndPaletteFields() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            new PacketRequestPortableMonitor(UUID.randomUUID(), 1, 0, 0).toBytes(bytes);
            bytes.setInt(0, 0x50574d57);
            bytes.setByte(4, 57);
            PacketRequestPortableMonitor request = new PacketRequestPortableMonitor();
            request.fromBytes(bytes);
            assertFalse(request.valid);
            bytes.clear();
            new PacketSyncPortableMonitor(UUID.randomUUID(), 1, 0).toBytes(bytes);
            bytes.setInt(0, 0x50574d57);
            bytes.setByte(4, 57);
            PacketSyncPortableMonitor snapshot = new PacketSyncPortableMonitor();
            snapshot.fromBytes(bytes);
            assertFalse(snapshot.valid);
            assertTrue(snapshot.panels.isEmpty());
        } finally {
            bytes.release();
        }
    }

    @Test
    public void bothPinnedGraphsKeepReceivingHistoryAfterGuiClosesInTextMode() {
        java.util.Set<String> pins = new java.util.HashSet<>(
            Arrays.asList(PortableMonitorRequestQueue.EU_GRAPH, PortableMonitorRequestQueue.AE_GRAPH));
        assertTrue(PortableMonitorRequestQueue.needsEUChart(false, pins, false));
        assertTrue(PortableMonitorRequestQueue.needsAEChart(false, pins));
        assertFalse(PortableMonitorRequestQueue.needsEUChart(false, java.util.Collections.emptySet(), false));
        assertFalse(PortableMonitorRequestQueue.needsAEChart(false, java.util.Collections.emptySet()));
    }

    @Test
    public void requestContainsOnlyBoundSelectionAndExplicitPinKeys() {
        PacketRequestPortableMonitor original = new PacketRequestPortableMonitor(UUID.randomUUID(), 42, 0, 0);
        original.selected = 3;
        original.selectedPanel = UUID.randomUUID();
        original.charts = true;
        original.knownRenderVersion = 88;
        UUID panel = UUID.randomUUID();
        original.pins.put(panel, new java.util.HashSet<>(Arrays.asList("eu", "fluid:water")));
        ByteBuf buffer = Unpooled.buffer();
        try {
            original.toBytes(buffer);
            PacketRequestPortableMonitor decoded = new PacketRequestPortableMonitor();
            decoded.fromBytes(buffer);
            assertTrue(decoded.valid);
            assertEquals(3, decoded.selected);
            assertEquals(original.selectedPanel, decoded.selectedPanel);
            assertTrue(decoded.charts);
            assertEquals(original.pins, decoded.pins);
            assertEquals(42, decoded.sequence);
            assertEquals(88, decoded.knownRenderVersion);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void fullRendererPayloadRetainsExactEuPowerAeRatesAndScreenStyle() {
        PacketSyncPortableMonitor packet = new PacketSyncPortableMonitor(UUID.randomUUID(), 1, 1);
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.binding = new MonitorBindings.Binding(0, 1, 2, 3, UUID.randomUUID());
        panel.detail = true;
        panel.render = new PortablePanelRenderData();
        panel.renderVersion = 123;
        panel.render.config.setBoolean("showChartEnergy", true);
        panel.render.config.setBoolean("showChartStatus", true);
        panel.render.config.setInteger("trendLineSplineType", 3);
        panel.render.config.setInteger("trackingWindow", 6);
        panel.render.config.setString("energyAxisMin", "1e20");
        panel.render.config.setString("aeLineColor", "00FF77");
        panel.render.chartKey = "fluid:water";
        panel.chartKey = "fluid:water";
        java.math.BigInteger energy = new java.math.BigInteger("100000000000000000001");
        panel.currentEnergy = energy.toString();
        panel.render.currentEnergy = energy.toString();
        panel.render.eu.add(new com.miaokatze.gtswn.common.panel.NetworkInfoSample(100, 10, energy, -42.5));
        panel.render.ae.add(new com.miaokatze.gtswn.common.panel.AEMonitorSample(200, 20, 1500, -12.25));
        panel.euRateChart = new double[] { 0, 1 };
        panel.aeRateChart = new double[] { 1, .25 };
        packet.panels.add(panel);
        ByteBuf buf = Unpooled.buffer();
        try {
            packet.toBytes(buf);
            PacketSyncPortableMonitor decoded = new PacketSyncPortableMonitor();
            decoded.fromBytes(buf);
            assertTrue(decoded.valid);
            PacketSyncPortableMonitor.Panel view = decoded.panels.get(0);
            assertEquals(energy, view.render.eu.get(0).eu);
            assertEquals(-42.5, view.render.eu.get(0).eut, 0);
            assertEquals(-12.25, view.render.ae.get(0).rate, 0);
            assertEquals(1500, view.render.ae.get(0).amount);
            assertEquals(3, view.render.config.getInteger("trendLineSplineType"));
            assertEquals(6, view.render.config.getInteger("trackingWindow"));
            assertEquals("1e20", view.render.config.getString("energyAxisMin"));
            assertEquals("00FF77", view.render.config.getString("aeLineColor"));
            assertEquals(panel.currentEnergy, view.currentEnergy);
            assertArrayEquals(panel.euRateChart, view.euRateChart, 0);
            assertArrayEquals(panel.aeRateChart, view.aeRateChart, 0);
        } finally {
            buf.release();
        }
    }

    @Test
    public void oldProtocolIsRejectedAndUnchangedDetailCanOmitFullBody() {
        ByteBuf old = Unpooled.buffer()
            .writeZero(48);
        try {
            PacketRequestPortableMonitor request = new PacketRequestPortableMonitor();
            request.fromBytes(old);
            assertFalse(request.valid);
        } finally {
            old.release();
        }
        PacketSyncPortableMonitor packet = new PacketSyncPortableMonitor(UUID.randomUUID(), 1, 1);
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.binding = new MonitorBindings.Binding(0, 0, 64, 0, UUID.randomUUID());
        panel.detail = true;
        panel.renderVersion = 99;
        packet.panels.add(panel);
        ByteBuf buf = Unpooled.buffer();
        try {
            packet.toBytes(buf);
            PacketSyncPortableMonitor decoded = new PacketSyncPortableMonitor();
            decoded.fromBytes(buf);
            assertTrue(decoded.valid);
            assertEquals(99, decoded.panels.get(0).renderVersion);
            assertNull(decoded.panels.get(0).render);
        } finally {
            buf.release();
        }
    }

    @Test
    public void snapshotRoundTripKeepsIndependentScreensAndRates() {
        UUID terminal = UUID.randomUUID();
        PacketSyncPortableMonitor original = new PacketSyncPortableMonitor(terminal, 27, 2);
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.binding = new MonitorBindings.Binding(-1, -500, 64, 200, UUID.randomUUID());
        panel.ownerName = "测试玩家";
        panel.window = "5m";
        panel.aeConnected = true;
        panel.sampleTime = 500;
        PacketSyncPortableMonitor.Row row = new PacketSyncPortableMonitor.Row();
        row.key = "fluid:water";
        row.name = "水";
        row.amount = "20000000000";
        row.kind = 2;
        row.monitored = true;
        row.rate = -200.5;
        row.average = 12.25;
        panel.rows.add(row);
        panel.euChart = new double[] { 0, .5, 1 };
        original.panels.add(panel);
        original.bindingRevision = 8;
        panel.aeWindow = "8h";
        panel.displayMode = 2;
        panel.palette = new com.miaokatze.gtswn.common.panel.TrendLinePalette(
            false,
            0x123456,
            0x345678,
            0x56789a,
            0x789abc,
            0x9abcde,
            0xbcdef0);
        PacketSyncPortableMonitor.Panel unloaded = new PacketSyncPortableMonitor.Panel();
        unloaded.binding = new MonitorBindings.Binding(12, 1, 2, 3, UUID.randomUUID());
        unloaded.state = 1;
        original.panels.add(unloaded);
        ByteBuf buffer = Unpooled.buffer();
        try {
            original.toBytes(buffer);
            PacketSyncPortableMonitor decoded = new PacketSyncPortableMonitor();
            decoded.fromBytes(buffer);
            assertTrue(decoded.valid);
            assertEquals(terminal, decoded.terminalId);
            assertEquals(27, decoded.sequence);
            assertEquals(8, decoded.bindingRevision);
            assertEquals("8h", decoded.panels.get(0).aeWindow);
            assertEquals(2, decoded.panels.get(0).displayMode);
            assertFalse(decoded.panels.get(0).palette.enabled);
            assertEquals(0x345678, decoded.panels.get(0).palette.energyUp);
            assertEquals(0xbcdef0, decoded.panels.get(0).palette.rateDown);
            assertEquals(-1, decoded.panels.get(0).binding.dimension);
            assertEquals(panel.binding.panelId, decoded.panels.get(0).binding.panelId);
            assertEquals("测试玩家", decoded.panels.get(0).ownerName);
            assertEquals(-200.5, decoded.panels.get(0).rows.get(0).rate, 0);
            assertEquals(12.25, decoded.panels.get(0).rows.get(0).average, 0);
            assertTrue(decoded.panels.get(0).rows.get(0).monitored);
            assertArrayEquals(panel.euChart, decoded.panels.get(0).euChart, 0);
            assertEquals(1, decoded.panels.get(1).state);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void rejectsTruncatedOversizedAndInvalidActions() {
        ByteBuf truncated = Unpooled.buffer()
            .writeByte(0);
        ByteBuf oversized = Unpooled.buffer(PacketSyncPortableMonitor.MAX_BYTES + 1)
            .writeZero(PacketSyncPortableMonitor.MAX_BYTES + 1);
        ByteBuf invalid = Unpooled.buffer();
        try {
            PacketSyncPortableMonitor response = new PacketSyncPortableMonitor();
            response.fromBytes(truncated);
            assertFalse(response.valid);
            response.fromBytes(oversized);
            assertFalse(response.valid);
            new PacketRequestPortableMonitor(UUID.randomUUID(), 1, 2, 3).toBytes(invalid);
            PacketRequestPortableMonitor request = new PacketRequestPortableMonitor();
            request.fromBytes(invalid);
            assertFalse(request.valid);
        } finally {
            truncated.release();
            oversized.release();
            invalid.release();
        }
    }

    @Test
    public void maximumUtf8SnapshotRemainsBelowWireLimit() {
        net.minecraftforge.fluids.FluidStack fluid = com.miaokatze.gtswn.test.FluidRegistryTestBootstrap.water(1000);
        fluid.tag = new net.minecraft.nbt.NBTTagCompound();
        fluid.tag.setString("identity", new String(new char[650]).replace('\0', 'x'));
        PortableMonitorIcon icon = PortableMonitorIcon.of(fluid);
        assertNotNull(icon);
        PacketSyncPortableMonitor original = new PacketSyncPortableMonitor(UUID.randomUUID(), 1, 1);
        String huge = new String(new char[1024]).replace('\0', '水');
        for (int i = 0; i < 16; i++) {
            PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
            panel.binding = new MonitorBindings.Binding(i, 0, 64, 0, UUID.randomUUID());
            panel.ownerName = huge;
            for (int j = 0; j < (i == 0 ? PacketSyncPortableMonitor.MAX_ROWS : 8); j++) {
                PacketSyncPortableMonitor.Row row = new PacketSyncPortableMonitor.Row();
                row.key = huge;
                row.name = huge;
                row.amount = huge;
                row.kind = j == 0 ? 0 : 2;
                row.icon = row.kind == 2 ? icon : null;
                panel.rows.add(row);
            }
            panel.euChart = new double[61];
            panel.aeChart = new double[61];
            original.panels.add(panel);
        }
        ByteBuf buffer = Unpooled.buffer();
        try {
            original.toBytes(buffer);
            assertTrue(buffer.readableBytes() < PacketSyncPortableMonitor.MAX_BYTES);
            PacketSyncPortableMonitor decoded = new PacketSyncPortableMonitor();
            decoded.fromBytes(buffer);
            assertTrue(decoded.valid);
            assertEquals(16, decoded.panels.size());
            assertEquals(PacketSyncPortableMonitor.MAX_ROWS, decoded.panels.get(0).rows.size());
            assertEquals(8, decoded.panels.get(15).rows.size());
            assertFalse(decoded.panels.get(0).rows.get(0).name.contains("\ufffd"));
        } finally {
            buffer.release();
        }
    }

    @Test
    public void normalizesArbitraryPrecisionEuWithoutInfinity() {
        BigDecimal base = new BigDecimal("1e1000");
        assertArrayEquals(
            new double[] { 0, .5, 1 },
            PortableMonitorRequestQueue
                .normalize(Arrays.asList(base, base.add(BigDecimal.ONE), base.add(new BigDecimal(2)))),
            0);
        assertArrayEquals(new double[] { .5, .5 }, PortableMonitorRequestQueue.normalize(Arrays.asList(base, base)), 0);
    }
}
