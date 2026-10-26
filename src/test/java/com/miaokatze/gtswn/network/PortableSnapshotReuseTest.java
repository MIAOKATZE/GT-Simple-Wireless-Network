package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PortableSnapshotReuseTest {

    @Test
    public void optInKeepsProtocol60LayoutAndLegacyServerFallsBackToFull() {
        for (long version : new long[] { 0, 1, 42, Long.MAX_VALUE }) {
            PacketRequestPortableMonitor request = new PacketRequestPortableMonitor(UUID.randomUUID(), 1, 0, 0);
            request.knownRenderVersion = version;
            ByteBuf bytes = Unpooled.buffer();
            try {
                request.toBytes(bytes);
                assertEquals(80, bytes.readableBytes());
                assertEquals(60, bytes.getUnsignedByte(4));
                assertEquals(version, bytes.getLong(70));
                PacketRequestPortableMonitor decoded = new PacketRequestPortableMonitor();
                decoded.fromBytes(bytes);
                assertTrue(decoded.valid);
                assertFalse(decoded.reuseCharts);
                bytes.clear();
                request.reuseCharts = true;
                request.toBytes(bytes);
                assertEquals(80, bytes.readableBytes());
                long legacyKnownRevision = bytes.getLong(70);
                assertTrue(legacyKnownRevision < 0);
                assertNotEquals(version, legacyKnownRevision); // Old server's equality gate requests a full body.
                decoded.fromBytes(bytes);
                assertTrue(decoded.valid);
                assertTrue(decoded.reuseCharts);
                assertEquals(version, decoded.knownRenderVersion);
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    public void unchangedChartsSave1952BytesAndNeverChangeRealTimestamps() {
        Fixture source = new Fixture();
        PortableMonitorRequestQueue.RenderCache cache = PortableMonitorRequestQueue.renderCache(source, null, null);
        cache.aeChart = cache.euChart.clone();
        cache.aeRateChart = cache.euRateChart.clone();
        PacketSyncPortableMonitor.Panel legacy = panel();
        PortableMonitorRequestQueue.publishRender(legacy, cache, true, true, cache.version, false);
        assertNull(legacy.render);
        assertEquals(61, legacy.euChart.length);
        PacketSyncPortableMonitor.Panel unchanged = panel();
        PortableMonitorRequestQueue.publishRender(unchanged, cache, true, true, cache.version, true);
        assertTrue(unchanged.chartsUnchanged);
        assertEquals(0, unchanged.euChart.length);
        ByteBuf full = Unpooled.buffer(), delta = Unpooled.buffer();
        try {
            packet(legacy).toBytes(full);
            packet(unchanged).toBytes(delta);
            assertEquals(1952, full.readableBytes() - delta.readableBytes());
            PacketSyncPortableMonitor decoded = new PacketSyncPortableMonitor();
            decoded.fromBytes(delta);
            assertTrue(decoded.valid);
            assertTrue(decoded.panels.get(0).chartsUnchanged);
            assertEquals(cache.version, decoded.panels.get(0).renderVersion);
            PacketSyncPortableMonitor.Panel cold = panel();
            PortableMonitorRequestQueue.publishRender(cold, cache, true, true, 0, true);
            full.clear();
            packet(cold).toBytes(full);
            decoded.fromBytes(full);
            assertTrue(decoded.valid);
            assertFalse(decoded.panels.get(0).chartsUnchanged);
            assertEquals(61, decoded.panels.get(0).render.eu.size());
            assertEquals(3000, decoded.panels.get(0).render.eu.get(60).timeMs);
            assertEquals(60, decoded.panels.get(0).render.eu.get(60).tick);
        } finally {
            full.release();
            delta.release();
        }
    }

    @Test
    public void exactContentInvalidatesInteriorTimesAndCollidingConfigurationHashesButNotLiveBalance() {
        Fixture source = new Fixture();
        source.config.setString("energyAxisMin", "Aa");
        PortableMonitorRequestQueue.RenderCache first = PortableMonitorRequestQueue.renderCache(source, null, null);
        source.energy = BigInteger.valueOf(999);
        assertSame(first, PortableMonitorRequestQueue.renderCache(source, null, null));
        int hash = source.config.hashCode();
        source.config.setString("energyAxisMin", "BB");
        assertEquals(hash, source.config.hashCode());
        PortableMonitorRequestQueue.RenderCache changed = PortableMonitorRequestQueue.renderCache(source, null, null);
        assertNotEquals(first.version, changed.version);
        assertEquals("Aa", first.render.config.getString("energyAxisMin"));
        NetworkInfoSample middle = source.samples.get(20);
        source.samples.set(20, new NetworkInfoSample(middle.timeMs + 1, middle.tick, middle.eu, middle.eut));
        PortableMonitorRequestQueue.RenderCache moved = PortableMonitorRequestQueue.renderCache(source, null, null);
        assertNotEquals(changed.version, moved.version);
        assertEquals(middle.timeMs, changed.render.eu.get(20).timeMs);
        source.samples.set(20, new NetworkInfoSample(middle.timeMs + 1, middle.tick, BigInteger.TEN, middle.eut));
        assertNotEquals(moved.version, PortableMonitorRequestQueue.renderCache(source, null, null).version);
    }

    @Test
    public void fullPublicationIsDetachedFromSourceAndOtherResponses() {
        Fixture source = new Fixture();
        PortableMonitorRequestQueue.RenderCache cache = PortableMonitorRequestQueue.renderCache(source, null, null);
        PacketSyncPortableMonitor.Panel first = panel(), second = panel();
        PortableMonitorRequestQueue.publishRender(first, cache, true, true, 0, true);
        PortableMonitorRequestQueue.publishRender(second, cache, true, true, 0, true);
        first.render.config.setInteger("trackingWindow", 7);
        first.render.eu.clear();
        first.euChart[0] = 1;
        source.samples.clear();
        assertEquals(61, second.render.eu.size());
        assertEquals(61, cache.render.eu.size());
        assertEquals(0, second.render.config.getInteger("trackingWindow"));
        assertEquals(0, second.euChart[0], 0);
        assertEquals(0, cache.euChart[0], 0);
    }

    @Test
    public void aeInteriorChangesAndHistoricalTimesAreNotHiddenByAnUnchangedNewestPoint() {
        List<AEMonitorSample> first = new ArrayList<>(), second = new ArrayList<>();
        first.add(new AEMonitorSample(10, 1, 100, 2));
        first.add(new AEMonitorSample(20, 2, 200, 3));
        second.addAll(first);
        assertTrue(PortablePanelRenderData.sameAE(first, second));
        second.set(0, new AEMonitorSample(11, 1, 100, 2));
        assertFalse(PortablePanelRenderData.sameAE(first, second));
        second.set(0, new AEMonitorSample(10, 1, 101, 2));
        assertFalse(PortablePanelRenderData.sameAE(first, second));
    }

    @Test
    public void selectedLongWindowIgnoresNewShortWindowPointsUntilItsOwnHistoryAdvances() {
        Fixture source = new Fixture();
        source.aeWindow = AEMonitorDataSet.WINDOW_1_HOUR;
        AEMonitorDataSet set = new AEMonitorDataSet();
        String key = "fluid:water";
        for (int i = 1; i <= 12; i++) set.addSample(key, i * 100, i * 100, i * 5000);
        PortableMonitorRequestQueue.RenderCache first = PortableMonitorRequestQueue.renderCache(source, set, key);
        assertEquals(1, first.render.ae.size());
        set.addSample(key, 1300, 1300, 65000);
        assertSame(first, PortableMonitorRequestQueue.renderCache(source, set, key));
        for (int i = 14; i <= 24; i++) set.addSample(key, i * 100, i * 100, i * 5000);
        PortableMonitorRequestQueue.RenderCache changed = PortableMonitorRequestQueue.renderCache(source, set, key);
        assertNotEquals(first.version, changed.version);
        assertEquals(2, changed.render.ae.size());
        assertEquals(120000, changed.render.ae.get(1).timeMs);
    }

    @Test
    public void revisionsNeverAliasZeroOrOverflowAndMalformedOmissionsAreRejected() {
        assertEquals(1, PortableMonitorRequestQueue.nextVersion(0));
        assertEquals(Long.MAX_VALUE, PortableMonitorRequestQueue.nextVersion(Long.MAX_VALUE - 1));
        for (long version : new long[] { -1, Long.MAX_VALUE }) {
            try {
                PortableMonitorRequestQueue.nextVersion(version);
                fail("revision must not wrap");
            } catch (IllegalStateException expected) {}
        }
        PacketSyncPortableMonitor.Panel panel = panel();
        panel.chartsUnchanged = true;
        panel.renderVersion = Long.MAX_VALUE;
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet(panel).toBytes(bytes);
            PacketSyncPortableMonitor decoded = new PacketSyncPortableMonitor();
            decoded.fromBytes(bytes);
            assertTrue(decoded.valid);
            assertEquals(Long.MAX_VALUE, decoded.panels.get(0).renderVersion);
            bytes.clear();
            packet(panel).toBytes(bytes);
            bytes.setLong(bytes.writerIndex() - 9, -1); // Complement of reserved revision zero.
            decoded.fromBytes(bytes);
            assertFalse(decoded.valid);
            bytes.clear();
            packet(panel).toBytes(bytes);
            panel.renderVersion = 0;
            try {
                packet(panel).toBytes(bytes);
                fail("zero cannot identify an omitted base");
            } catch (IllegalArgumentException expected) {}
            PacketRequestPortableMonitor request = new PacketRequestPortableMonitor(UUID.randomUUID(), 1, 0, 0);
            request.knownRenderVersion = -1;
            try {
                request.toBytes(bytes);
                fail("internal revisions must be nonnegative");
            } catch (IllegalArgumentException expected) {}
        } finally {
            bytes.release();
        }
    }

    private static PacketSyncPortableMonitor.Panel panel() {
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.binding = new MonitorBindings.Binding(0, 0, 64, 0, new UUID(0, 1));
        panel.detail = true;
        return panel;
    }

    private static PacketSyncPortableMonitor packet(PacketSyncPortableMonitor.Panel panel) {
        PacketSyncPortableMonitor packet = new PacketSyncPortableMonitor(new UUID(0, 2), 1, 1);
        packet.panels.add(panel);
        return packet;
    }

    private static final class Fixture extends TileEntityNetworkInfoPanel {

        final NBTTagCompound config = new NBTTagCompound();
        final List<NetworkInfoSample> samples = new ArrayList<>();
        BigInteger energy = BigInteger.ONE;
        int aeWindow;

        Fixture() {
            for (int i = 0; i < 61; i++) samples.add(new NetworkInfoSample(i * 50, i, BigInteger.valueOf(i), i));
        }

        @Override
        public NBTTagCompound getPortableRenderConfig() {
            return config;
        }

        @Override
        public List<NetworkInfoSample> getCachedSamples() {
            return samples;
        }

        @Override
        public int getAETrackingWindow() {
            return aeWindow;
        }

        @Override
        public BigInteger getCachedEu() {
            return energy;
        }
    }
}
