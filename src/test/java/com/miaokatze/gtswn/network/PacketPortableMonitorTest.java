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
    public void requestContainsOnlyBoundSelectionAndExplicitPinKeys() {
        PacketRequestPortableMonitor original = new PacketRequestPortableMonitor(UUID.randomUUID(), 42, 0, 0);
        original.selected = 3;
        original.charts = true;
        UUID panel = UUID.randomUUID();
        original.pins.put(panel, new java.util.HashSet<>(Arrays.asList("eu", "fluid:water")));
        ByteBuf buffer = Unpooled.buffer();
        try {
            original.toBytes(buffer);
            PacketRequestPortableMonitor decoded = new PacketRequestPortableMonitor();
            decoded.fromBytes(buffer);
            assertTrue(decoded.valid);
            assertEquals(3, decoded.selected);
            assertTrue(decoded.charts);
            assertEquals(original.pins, decoded.pins);
            assertEquals(42, decoded.sequence);
        } finally {
            buffer.release();
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
        row.rate = -200.5;
        row.average = 12.25;
        panel.rows.add(row);
        panel.euChart = new double[] { 0, .5, 1 };
        original.panels.add(panel);
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
            assertEquals(-1, decoded.panels.get(0).binding.dimension);
            assertEquals(panel.binding.panelId, decoded.panels.get(0).binding.panelId);
            assertEquals("测试玩家", decoded.panels.get(0).ownerName);
            assertEquals(-200.5, decoded.panels.get(0).rows.get(0).rate, 0);
            assertEquals(12.25, decoded.panels.get(0).rows.get(0).average, 0);
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
