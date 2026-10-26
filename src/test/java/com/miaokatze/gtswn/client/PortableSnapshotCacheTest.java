package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.network.PacketRequestPortableMonitor;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;
import com.miaokatze.gtswn.network.PortablePanelRenderData;

public class PortableSnapshotCacheTest {

    private static final UUID TERMINAL = new UUID(0, 1), PANEL = new UUID(0, 2);

    @Test
    public void directDashboardKeepsForegroundSelectionAcrossRequestsWithoutAnOrdinaryViewer() {
        for (int sequence = 1; sequence <= 2; sequence++) {
            PacketRequestPortableMonitor request = PortableMonitorClientCache
                .foregroundRequest(TERMINAL, sequence, 0, 0, true, true, -1, 2);
            assertEquals(TERMINAL, request.terminalId);
            assertEquals(sequence, request.sequence);
            assertEquals("F10 host must request its selected screen", 2, request.selected);
            assertTrue(PortableMonitorClientCache.usesDashboardSelection(true, true, 2));
        }
    }

    @Test
    public void ordinaryViewerAndForeignGuiKeepTheirOwnSelectionInsteadOfBorrowingDashboardDemand() {
        PacketRequestPortableMonitor viewer = PortableMonitorClientCache
            .foregroundRequest(TERMINAL, 3, 0, 0, true, false, 1, 2);
        assertEquals(1, viewer.selected);
        PacketRequestPortableMonitor foreign = PortableMonitorClientCache
            .foregroundRequest(TERMINAL, 4, 0, 0, true, false, -1, 2);
        assertEquals(-1, foreign.selected);
        assertFalse(PortableMonitorClientCache.usesDashboardSelection(true, false, 2));
    }

    @Test
    public void worldDashboardAndClosedForegroundDemandRetainTheirExistingRequestSelection() {
        assertEquals(0, PortableMonitorClientCache.foregroundRequest(TERMINAL, 5, 0, 0, false, false, -1, 0).selected);
        assertEquals(1, PortableMonitorClientCache.foregroundRequest(TERMINAL, 6, 0, 0, true, true, 1, -1).selected);
        assertFalse(PortableMonitorClientCache.usesDashboardSelection(true, true, -1));
    }

    @Test
    public void completeBaseRestoresChartsWhileKeepingFreshLiveValuesAndRealHistory() {
        PacketSyncPortableMonitor previous = full();
        PacketSyncPortableMonitor response = unchanged();
        PacketSyncPortableMonitor.Panel live = response.panels.get(0);
        live.currentEnergy = "999";
        live.sampleTime = 500;
        assertTrue(PortableMonitorClientCache.restoreSnapshot(response, previous, request()));
        assertFalse(live.chartsUnchanged);
        assertEquals("999", live.currentEnergy);
        assertEquals(500, live.sampleTime);
        assertEquals(10, live.render.eu.get(0).timeMs);
        assertEquals(1, live.render.eu.get(0).tick);
        assertArrayEquals(new double[] { .25, .75 }, live.euChart, 0);
        assertArrayEquals(new double[] { .75, .25 }, live.euRateChart, 0);
        assertArrayEquals(new double[] { 0, 1 }, live.aeChart, 0);
        assertArrayEquals(new double[] { 1, 0 }, live.aeRateChart, 0);
        live.euChart[0] = 0;
        live.render.eu.clear();
        live.render.config.setInteger("trackingWindow", 7);
        assertEquals(.25, previous.panels.get(0).euChart[0], 0);
        assertEquals(1, previous.panels.get(0).render.eu.size());
        assertEquals(0, previous.panels.get(0).render.config.getInteger("trackingWindow"));
    }

    @Test
    public void missingPartialOrChangedBasesCannotBecomeFreshSnapshots() {
        assertFalse(PortableMonitorClientCache.restoreSnapshot(unchanged(), null, request()));
        for (int change = 0; change < 8; change++) {
            PacketSyncPortableMonitor previous = full();
            PacketSyncPortableMonitor.Panel old = previous.panels.get(0);
            if (change == 0) previous.terminalId = UUID.randomUUID();
            if (change == 1) old.binding = new MonitorBindings.Binding(1, 0, 64, 0, PANEL);
            if (change == 2) old.binding = new MonitorBindings.Binding(0, 1, 64, 0, PANEL);
            if (change == 3) old.binding = new MonitorBindings.Binding(0, 0, 64, 0, UUID.randomUUID());
            if (change == 4) old.chartKey = "fluid:lava";
            if (change == 5) old.renderVersion++;
            if (change == 6) old.state = 1;
            if (change == 7) old.render = null;
            PacketSyncPortableMonitor response = unchanged();
            assertFalse(
                "base change " + change,
                PortableMonitorClientCache.restoreSnapshot(response, previous, request()));
            assertNull(response.panels.get(0).render);
            assertTrue(response.panels.get(0).chartsUnchanged);
        }
    }

    @Test
    public void omissionMustMatchTheExactOptedInRequestAndNeverCarriesAMutationAck() {
        for (int change = 0; change < 6; change++) {
            PacketRequestPortableMonitor request = request();
            if (change == 0) request.reuseCharts = false;
            if (change == 1) request.knownRenderVersion = 0;
            if (change == 2) request.selectedPanel = UUID.randomUUID();
            if (change == 3) request.sequence++;
            if (change == 4) request.terminalId = UUID.randomUUID();
            if (change == 5) request.action = 1;
            assertFalse(PortableMonitorClientCache.restoreSnapshot(unchanged(), full(), request));
        }
        PacketRequestPortableMonitor mutation = request();
        mutation.action = 2;
        PortableMonitorClientCache.configureChartReuse(mutation, full());
        assertFalse(mutation.reuseCharts);
        assertEquals(0, mutation.knownRenderVersion);
        PacketSyncPortableMonitor acknowledgement = full();
        acknowledgement.sequence = mutation.sequence;
        assertTrue(PortableMonitorClientCache.restoreSnapshot(acknowledgement, null, mutation));
    }

    @Test
    public void onlyCompleteSelectedSnapshotsAreAcknowledgedAndEmptyHistoryIsComplete() {
        PacketRequestPortableMonitor request = request();
        PortableMonitorClientCache.configureChartReuse(request, full());
        assertTrue(request.reuseCharts);
        assertEquals(7, request.knownRenderVersion);
        for (int change = 0; change < 5; change++) {
            PacketSyncPortableMonitor previous = full();
            PacketSyncPortableMonitor.Panel old = previous.panels.get(0);
            if (change == 0) old.detail = false;
            if (change == 1) old.state = 2;
            if (change == 2) old.render = null;
            if (change == 3) old.chartsUnchanged = true;
            if (change == 4) previous.terminalId = UUID.randomUUID();
            request = request();
            PortableMonitorClientCache.configureChartReuse(request, previous);
            assertEquals(0, request.knownRenderVersion);
        }
        PacketSyncPortableMonitor empty = full();
        empty.panels.get(0).render.eu.clear();
        empty.panels.get(0).euChart = new double[0];
        request = request();
        PortableMonitorClientCache.configureChartReuse(request, empty);
        assertEquals(7, request.knownRenderVersion);
        assertTrue(PortableMonitorClientCache.restoreSnapshot(unchanged(), empty, request));
    }

    @Test
    public void oldServerFullRepliesNeedNoBaseAndLegacyRenderOnlyOmissionKeepsNewCharts() {
        PacketSyncPortableMonitor full = full();
        full.sequence = request().sequence;
        assertTrue(PortableMonitorClientCache.restoreSnapshot(full, null, request()));
        PacketSyncPortableMonitor legacy = unchanged();
        legacy.panels.get(0).chartsUnchanged = false;
        legacy.panels.get(0).euChart = new double[] { .1 };
        PacketRequestPortableMonitor request = request();
        request.reuseCharts = false;
        assertTrue(PortableMonitorClientCache.restoreSnapshot(legacy, full(), request));
        assertArrayEquals(new double[] { .1 }, legacy.panels.get(0).euChart, 0);
        assertNotNull(legacy.panels.get(0).render);
    }

    @Test
    public void legacyServerIsLearnedOnlyFromIdenticalFullReplyAndResetOnNewSession() {
        PortableMonitorClientCache.resetForHudSession();
        try {
            PacketRequestPortableMonitor request = request();
            PacketSyncPortableMonitor changed = full();
            changed.sequence = request.sequence;
            changed.panels.get(0).renderVersion++;
            PortableMonitorClientCache.observeChartReuse(changed, full(), request);
            PortableMonitorClientCache.configureChartReuse(request, full());
            assertTrue(request.reuseCharts);
            PacketSyncPortableMonitor legacy = full();
            legacy.sequence = request.sequence;
            PortableMonitorClientCache.observeChartReuse(legacy, full(), request);
            request = request();
            PortableMonitorClientCache.configureChartReuse(request, full());
            assertFalse(request.reuseCharts);
            assertEquals(7, request.knownRenderVersion); // Resume legacy positive-revision render omission.
            PortableMonitorClientCache.resetForHudSession();
            request = request();
            PortableMonitorClientCache.configureChartReuse(request, full());
            assertTrue(request.reuseCharts);
        } finally {
            PortableMonitorClientCache.resetForHudSession();
        }
    }

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void sessionResetDropsEveryBaseAndColdRequestRecoversWithFullData() throws Exception {
        java.lang.reflect.Field field = PortableMonitorClientCache.class.getDeclaredField("CACHE");
        field.setAccessible(true);
        Map cache = (Map) field.get(null);
        cache.put(TERMINAL, null);
        PortableMonitorClientCache.resetForHudSession();
        assertTrue(cache.isEmpty());
        PacketRequestPortableMonitor request = request();
        PortableMonitorClientCache.configureChartReuse(request, null);
        assertTrue(request.reuseCharts);
        assertEquals(0, request.knownRenderVersion);
        assertFalse(PortableMonitorClientCache.restoreSnapshot(unchanged(), null, request));
        PacketSyncPortableMonitor recovered = full();
        recovered.sequence = request.sequence;
        assertTrue(PortableMonitorClientCache.restoreSnapshot(recovered, null, request));
    }

    private static PacketRequestPortableMonitor request() {
        PacketRequestPortableMonitor request = new PacketRequestPortableMonitor(TERMINAL, 2, 0, 0);
        request.selected = 0;
        request.selectedPanel = PANEL;
        request.knownRenderVersion = 7;
        request.reuseCharts = true;
        return request;
    }

    private static PacketSyncPortableMonitor full() {
        PacketSyncPortableMonitor snapshot = new PacketSyncPortableMonitor(TERMINAL, 1, 1);
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.binding = new MonitorBindings.Binding(0, 0, 64, 0, PANEL);
        panel.detail = true;
        panel.renderVersion = 7;
        panel.chartKey = "fluid:water";
        panel.render = new PortablePanelRenderData();
        panel.render.eu.add(new NetworkInfoSample(10, 1, BigInteger.ONE, 2));
        panel.euChart = new double[] { .25, .75 };
        panel.euRateChart = new double[] { .75, .25 };
        panel.aeChart = new double[] { 0, 1 };
        panel.aeRateChart = new double[] { 1, 0 };
        snapshot.panels.add(panel);
        return snapshot;
    }

    private static PacketSyncPortableMonitor unchanged() {
        PacketSyncPortableMonitor snapshot = full();
        snapshot.sequence = 2;
        PacketSyncPortableMonitor.Panel panel = snapshot.panels.get(0);
        panel.render = null;
        panel.chartsUnchanged = true;
        panel.euChart = panel.euRateChart = panel.aeChart = panel.aeRateChart = new double[0];
        return snapshot;
    }
}
