package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PortableHistoryPatchTest {

    @Test
    public void sampleAppendDoesNotInvalidateConfigAndClientRenormalizesWholeRawWindow() {
        Fixture source = new Fixture();
        source.add(1, 10);
        source.add(2, 20);
        PortableMonitorRequestQueue.RenderCache first = PortableMonitorRequestQueue.renderCache(source, null, null);
        PacketSyncPortableMonitor.Panel base = panel(first.version);
        assertTrue(
            PortableHistoryPatch.between(null, first, true, true)
                .apply(base, null));
        source.add(3, 100);
        PortableMonitorRequestQueue.RenderCache second = PortableMonitorRequestQueue.renderCache(source, null, null);
        assertEquals(first.configVersion, second.configVersion);
        PortableHistoryPatch append = PortableHistoryPatch.between(first, second, true, true);
        assertNull(append.config);
        assertEquals(1, append.eu.size());
        PacketSyncPortableMonitor.Panel next = panel(second.version);
        assertTrue(append.apply(next, base));
        assertEquals(3, next.render.eu.size());
        assertEquals(1.0 / 9, next.euChart[1], 1e-12);
        assertEquals(150, next.render.eu.get(2).timeMs);
        assertEquals(2, base.render.eu.size());
    }

    @Test
    public void rollWindowDropsOnlyActualPrefixAndMissingBaseCannotBecomeFresh() {
        Fixture source = new Fixture();
        for (int i = 0; i < 61; i++) source.add(i, i);
        PortableMonitorRequestQueue.RenderCache first = PortableMonitorRequestQueue.renderCache(source, null, null);
        PacketSyncPortableMonitor.Panel base = panel(first.version);
        assertTrue(
            PortableHistoryPatch.between(null, first, true, true)
                .apply(base, null));
        source.add(61, 61);
        PortableMonitorRequestQueue.RenderCache second = PortableMonitorRequestQueue.renderCache(source, null, null);
        PortableHistoryPatch patch = PortableHistoryPatch.between(first, second, true, true);
        assertEquals(1, patch.euDrop);
        assertFalse(patch.euReset);
        assertEquals(1, patch.eu.size());
        PacketSyncPortableMonitor.Panel next = panel(second.version);
        assertFalse(patch.apply(next, null));
        assertNull(next.render);
        assertTrue(patch.apply(next, base));
        assertEquals(61, next.render.eu.size());
        assertEquals(1, next.render.eu.get(0).tick);
    }

    @Test
    public void rollbackAndWindowReplacementExplicitlyResetWithoutInventingPoints() {
        Fixture source = new Fixture();
        source.add(100, 100);
        PortableMonitorRequestQueue.RenderCache first = PortableMonitorRequestQueue.renderCache(source, null, null);
        PacketSyncPortableMonitor.Panel base = panel(first.version);
        assertTrue(
            PortableHistoryPatch.between(null, first, true, true)
                .apply(base, null));
        source.samples.clear();
        source.add(5, 500);
        PortableMonitorRequestQueue.RenderCache second = PortableMonitorRequestQueue.renderCache(source, null, null);
        PortableHistoryPatch patch = PortableHistoryPatch.between(first, second, true, true);
        assertTrue(patch.euReset);
        PacketSyncPortableMonitor.Panel next = panel(second.version);
        assertTrue(patch.apply(next, base));
        assertEquals(1, next.render.eu.size());
        assertEquals(250, next.render.eu.get(0).timeMs);
    }

    @Test
    public void configChangeHasSeparateRevisionAndNoRepeatedSamples() {
        Fixture source = new Fixture();
        source.add(1, 1);
        PortableMonitorRequestQueue.RenderCache first = PortableMonitorRequestQueue.renderCache(source, null, null);
        source.config.setInteger("displayMode", 2);
        PortableMonitorRequestQueue.RenderCache second = PortableMonitorRequestQueue.renderCache(source, null, null);
        PortableHistoryPatch patch = PortableHistoryPatch.between(first, second, true, true);
        assertNotEquals(first.configVersion, second.configVersion);
        assertNotNull(patch.config);
        assertTrue(patch.eu.isEmpty());
    }

    @Test
    public void negotiatedEnvelopeCarriesRawOnlyAndRejectsEveryTruncation() {
        Fixture source = new Fixture();
        source.add(1, 23);
        PortableMonitorRequestQueue.RenderCache cache = PortableMonitorRequestQueue.renderCache(source, null, null);
        PacketSyncPortableMonitor snapshot = new PacketSyncPortableMonitor(UUID.randomUUID(), 1, 0);
        PacketSyncPortableMonitor.Panel panel = panel(cache.version);
        panel.historyPatch = PortableHistoryPatch.between(null, cache, true, true);
        snapshot.panels.add(panel);
        ByteBuf bytes = Unpooled.buffer();
        try {
            new PacketSyncPortableHistory(snapshot).toBytes(bytes);
            for (int length = 0; length < bytes.readableBytes(); length++) {
                PacketSyncPortableHistory invalid = new PacketSyncPortableHistory();
                invalid.fromBytes(bytes.slice(0, length));
                assertFalse("length=" + length, invalid.valid);
            }
            PacketSyncPortableHistory decoded = new PacketSyncPortableHistory();
            decoded.fromBytes(bytes.duplicate());
            assertTrue(decoded.valid);
            PacketSyncPortableMonitor.Panel raw = decoded.snapshot.panels.get(0);
            assertEquals(0, raw.euChart.length);
            assertNull(raw.render);
            assertTrue(raw.historyPatch.apply(raw, null));
            assertEquals(BigInteger.valueOf(23), raw.render.eu.get(0).eu);
        } finally {
            bytes.release();
        }
    }

    @Test
    public void negotiatedRequestAcknowledgesEveryPinnedWindowWithoutChangingLegacyBytes() {
        PacketRequestPortableMonitor request = new PacketRequestPortableMonitor(UUID.randomUUID(), 4, 0, 0);
        UUID pinned = UUID.randomUUID();
        request.historyCursors.put(pinned, new PacketRequestPortableMonitor.HistoryCursor(7, 2, 1));
        ByteBuf bytes = Unpooled.buffer();
        try {
            request.toBytes(bytes);
            assertEquals(80, bytes.readableBytes());
            bytes.clear();
            new PacketRequestPortableHistory(request).toBytes(bytes);
            PacketRequestPortableHistory decoded = new PacketRequestPortableHistory();
            decoded.fromBytes(bytes.duplicate());
            assertTrue(decoded.valid);
            assertTrue(decoded.request.rawHistory);
            assertEquals(7, decoded.request.historyCursors.get(pinned).version);
            assertEquals(2, decoded.request.historyCursors.get(pinned).configVersion);
            assertEquals(1, decoded.request.historyCursors.get(pinned).fields);
            bytes.setByte(53, 9);
            decoded = new PacketRequestPortableHistory();
            decoded.fromBytes(bytes.duplicate());
            assertFalse(decoded.valid);
        } finally {
            bytes.release();
        }
    }

    private static PacketSyncPortableMonitor.Panel panel(long version) {
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.binding = new MonitorBindings.Binding(0, 1, 2, 3, new UUID(1, 2));
        panel.detail = true;
        panel.metadataVersion = 1;
        panel.renderVersion = version;
        return panel;
    }

    private static final class Fixture extends TileEntityNetworkInfoPanel {

        final NBTTagCompound config = new NBTTagCompound();
        final List<NetworkInfoSample> samples = new ArrayList<>();

        void add(long tick, long value) {
            samples.add(new NetworkInfoSample(tick * 50, tick, BigInteger.valueOf(value), value));
        }

        @Override
        public NBTTagCompound getPortableRenderConfig() {
            return config;
        }

        @Override
        public List<NetworkInfoSample> getCachedSamples() {
            return samples;
        }
    }
}
