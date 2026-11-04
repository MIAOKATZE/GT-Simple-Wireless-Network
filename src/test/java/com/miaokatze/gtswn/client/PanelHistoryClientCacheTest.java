package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.network.PacketSyncPanelHistory;
import com.miaokatze.gtswn.network.PortableHistoryPatch;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PanelHistoryClientCacheTest {

    @Before
    public void before() {
        PanelHistoryClientCache.clear();
    }

    @After
    public void after() {
        PanelHistoryClientCache.clear();
    }

    @Test
    public void consecutiveEuAndAePacketsAgainstTheSameAckKeepLiveValuesAndSameSourceCharts() {
        for (Long initial : new Long[] { null, 7L }) {
            PanelHistoryClientCache.clear();
            Panel panel = new Panel();
            UUID session = UUID.randomUUID();
            PacketSyncPanelHistory first = message(panel, session, 1, 0, 1, initial);
            first.aePresent = true;
            assertEquals(1, receive(panel, first));
            assertEquals(2, receive(panel, message(panel, session, 2, 1, 2, null)));
            PacketSyncPanelHistory ae = message(panel, session, 3, 1, 3, 42L);
            assertEquals(0, receive(panel, ae));
            assertLive(panel, 3, 42);
            assertEquals(
                2,
                panel.getCachedSamples()
                    .size());
            assertEquals(
                1,
                panel.getAEChartSamples()
                    .size());
            assertEquals(
                1,
                panel.getAEChartSamples()
                    .get(0).tick);
        }
    }

    @Test
    public void unchangedHistoryTargetStillUpdatesLatestAndAverages() {
        Panel panel = new Panel();
        UUID session = UUID.randomUUID();
        assertEquals(1, receive(panel, message(panel, session, 1, 0, 1, 7L)));
        assertEquals(2, receive(panel, message(panel, session, 2, 1, 2, 8L)));
        assertEquals(0, receive(panel, message(panel, session, 3, 1, 2, 99L)));
        assertLive(panel, 3, 99);
        assertEquals(
            2,
            panel.getCachedSamples()
                .size());
        assertEquals(
            2,
            panel.getAEChartSamples()
                .size());
    }

    @Test
    public void failedHistoryKeepsLiveSequenceAndOnlyFullHistoryRestoresTheBaseline() {
        Panel panel = new Panel();
        UUID session = UUID.randomUUID();
        receive(panel, message(panel, session, 1, 0, 1, 1L));
        receive(panel, message(panel, session, 2, 1, 2, 2L));
        assertEquals(0, receive(panel, message(panel, session, 4, 1, 3, 4L)));
        assertEquals(-1, receive(panel, message(panel, session, 3, 0, 9, 999L)));
        assertEquals(-1, receive(panel, message(panel, session, 4, 0, 9, 999L)));
        assertLive(panel, 4, 4);
        assertEquals(0, receive(panel, message(panel, session, 5, 2, 4, 5L)));
        assertEquals(6, receive(panel, message(panel, session, 6, 0, 5, 6L)));
        assertEquals(
            1,
            panel.getCachedSamples()
                .size());
        assertEquals(
            5,
            panel.getCachedSamples()
                .get(0).tick);
        assertEquals(7, receive(panel, message(panel, session, 7, 5, 6, 7L)));
        assertLive(panel, 7, 7);
        assertEquals(
            2,
            panel.getAEChartSamples()
                .size());
    }

    @Test
    public void twoScreensKeepTheirBaselinesSequencesAndRowsSeparate() {
        Panel first = new Panel(), second = new Panel();
        UUID firstSession = UUID.randomUUID(), secondSession = UUID.randomUUID();
        receive(first, message(first, firstSession, 1, 0, 1, 10L));
        receive(second, message(second, secondSession, 1, 0, 1, 20L));
        receive(first, message(first, firstSession, 2, 1, 2, 11L));
        assertEquals(0, receive(first, message(first, firstSession, 3, 1, 3, 12L)));
        assertEquals(2, receive(second, message(second, secondSession, 2, 1, 2, 21L)));
        assertLive(first, 3, 12);
        assertLive(second, 2, 21);
        assertEquals(-1, receive(second, message(first, firstSession, 4, 0, 4, 999L)));
        assertLive(second, 2, 21);
        // The same tile object with a new persisted screen ID cannot reuse its former baseline.
        second.id = UUID.randomUUID();
        assertEquals(0, receive(second, message(second, secondSession, 1, 2, 3, 22L)));
        assertLive(second, 1, 22);
        assertTrue(
            second.getCachedSamples()
                .isEmpty());
    }

    @Test
    public void newSessionCannotUseOldHistoryButItsLiveSnapshotStillApplies() {
        Panel panel = new Panel();
        receive(panel, message(panel, UUID.randomUUID(), 100, 0, 1, 7L));
        UUID nextSession = UUID.randomUUID();
        assertEquals(0, receive(panel, message(panel, nextSession, 1, 1, 2, 42L)));
        assertLive(panel, 1, 42);
        assertTrue(
            panel.getCachedSamples()
                .isEmpty());
        assertTrue(
            panel.getAEChartSamples()
                .isEmpty());
        assertEquals(2, receive(panel, message(panel, nextSession, 2, 0, 2, 43L)));
        assertFalse(
            panel.getAEChartSamples()
                .isEmpty());
    }

    @Test
    public void failedSourceChangeClearsFormerChartsAndAcceptsNewSourceRows() {
        Panel panel = new Panel();
        UUID session = UUID.randomUUID();
        receive(panel, message(panel, session, 1, 0, 1, 7L));
        PacketSyncPanelHistory changed = message(panel, session, 2, 99, 100, 42L);
        changed.history.euSource = "eu:replacement";
        changed.history.aeSource = "ae:replacement";
        assertEquals(0, receive(panel, changed));
        assertLive(panel, 2, 42);
        assertTrue(
            panel.getCachedSamples()
                .isEmpty());
        assertTrue(
            panel.getAEChartSamples()
                .isEmpty());
    }

    @Test
    public void absentAeSnapshotPreservesSameSourceRowsButSourceAndSessionChangesInvalidateThem() {
        Panel panel = new Panel();
        UUID session = UUID.randomUUID();
        receive(panel, message(panel, session, 1, 0, 1, 7L));
        assertEquals(2, receive(panel, message(panel, session, 2, 1, 2, null)));
        assertLive(panel, 2, 7);
        assertEquals(
            1,
            panel.getAEChartSamples()
                .size());
        PacketSyncPanelHistory selection = message(panel, session, 3, 1, 3, null);
        selection.history.chartKey = "selected:replacement";
        assertEquals(0, receive(panel, selection));
        assertLive(panel, 3, 7);
        assertTrue(
            panel.getAEChartSamples()
                .isEmpty());
        PacketSyncPanelHistory changed = message(panel, session, 4, 1, 4, null);
        changed.history.aeSource = "ae:replacement";
        assertEquals(0, receive(panel, changed));
        assertTrue(
            panel.getAEMonitorLatest()
                .isEmpty());
        assertTrue(
            panel.getAEMonitorAvg300s()
                .isEmpty());
        receive(panel, message(panel, session, 5, 0, 5, 9L));
        assertEquals(0, receive(panel, message(panel, UUID.randomUUID(), 1, 5, 6, null)));
        assertTrue(
            panel.getAEMonitorLatest()
                .isEmpty());
        assertTrue(
            panel.getAEMonitorAvg300s()
                .isEmpty());
    }

    @Test
    public void explicitOfflineEmptyMapsClearLiveRowsEvenWhenHistoryCannotApply() {
        Panel panel = new Panel();
        UUID session = UUID.randomUUID();
        receive(panel, message(panel, session, 1, 0, 1, 7L));
        PacketSyncPanelHistory offline = message(panel, session, 2, 99, 100, null);
        offline.aePresent = true;
        assertEquals(0, receive(panel, offline));
        assertTrue(
            panel.getAEMonitorLatest()
                .isEmpty());
        assertTrue(
            panel.getAEMonitorAvg300s()
                .isEmpty());
        assertEquals(
            1,
            panel.getAEChartSamples()
                .size());
        assertEquals(BigInteger.valueOf(2), panel.getCachedEu());
    }

    @Test
    public void worldAndConnectionChangesInvalidateHistoryAndLiveSequenceTogether() {
        Panel panel = new Panel();
        UUID session = UUID.randomUUID();
        Object world = new Object(), connection = new Object();
        PanelHistoryClientCache.session(world, connection);
        receive(panel, message(panel, session, 100, 0, 1, 7L));
        PanelHistoryClientCache.session(world, connection);
        assertEquals(-1, receive(panel, message(panel, session, 1, 0, 1, 99L)));
        Object nextWorld = new Object();
        PanelHistoryClientCache.session(nextWorld, connection);
        assertEquals(0, receive(panel, message(panel, session, 1, 1, 2, 42L)));
        assertLive(panel, 1, 42);
        assertTrue(
            panel.getAEChartSamples()
                .isEmpty());
        receive(panel, message(panel, session, 100, 0, 2, 43L));
        PanelHistoryClientCache.session(nextWorld, new Object());
        assertEquals(0, receive(panel, message(panel, session, 1, 2, 3, 44L)));
        assertLive(panel, 1, 44);
        PanelHistoryClientCache.clear();
        assertEquals(1, receive(panel, message(panel, session, 1, 0, 3, 45L)));
    }

    private static long receive(Panel panel, PacketSyncPanelHistory message) {
        ByteBuf bytes = Unpooled.buffer();
        try {
            message.toBytes(bytes);
            PacketSyncPanelHistory decoded = new PacketSyncPanelHistory();
            decoded.fromBytes(bytes);
            assertTrue(decoded.valid);
            return PanelHistoryClientCache.receive(panel, decoded);
        } finally {
            bytes.release();
        }
    }

    private static void assertLive(Panel panel, long energy, long amount) {
        assertEquals(BigInteger.valueOf(energy), panel.getCachedEu());
        assertEquals(energy * 10.0, panel.getCachedEut(), 0.0);
        assertEquals("live:" + energy, panel.getCachedStatus());
        assertEquals(
            amount,
            panel.getAEMonitorLatest()
                .get("row").amount);
        assertEquals(
            amount / 2.0,
            panel.getAEMonitorAvg300s()
                .get("row"),
            0.0);
    }

    private static PacketSyncPanelHistory message(Panel panel, UUID session, long sequence, long base, long target,
        Long amount) {
        PacketSyncPanelHistory packet = new PacketSyncPanelHistory();
        packet.panelId = panel.id;
        packet.sessionId = session;
        packet.sequence = sequence;
        packet.energy = Long.toString(sequence);
        packet.power = sequence * 10.0;
        packet.status = "live:" + sequence;
        PortableHistoryPatch patch = new PortableHistoryPatch();
        patch.baseVersion = base;
        patch.targetVersion = target;
        patch.configVersion = 1;
        patch.chartKey = "selected";
        patch.euSource = "eu:original";
        patch.aeSource = "ae:original";
        if (base == 0) {
            patch.config = new NBTTagCompound();
            patch.euReset = patch.aeReset = true;
        }
        patch.eu.add(new NetworkInfoSample(target * 50, target, BigInteger.valueOf(target), target));
        patch.ae.add(new AEMonitorSample(target * 50, target, target, target));
        packet.history = patch;
        packet.aePresent = amount != null;
        if (amount != null) {
            packet.latest.put("row", new AEMonitorSample(sequence * 50, sequence, amount, amount));
            packet.averages.put("row", amount / 2.0);
        }
        return packet;
    }

    private static final class Panel extends TileEntityNetworkInfoPanel {

        UUID id = UUID.randomUUID();

        @Override
        public UUID getPortableMonitorId() {
            return id;
        }
    }
}
