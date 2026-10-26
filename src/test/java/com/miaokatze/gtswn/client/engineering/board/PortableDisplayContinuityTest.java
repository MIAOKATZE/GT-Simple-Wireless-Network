package com.miaokatze.gtswn.client.engineering.board;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel;
import com.miaokatze.gtswn.network.PortableHistoryPatch;

public class PortableDisplayContinuityTest {

    @Test
    public void SummaryProjectionDoesNotInvalidatePriorDetailDictionary() {
        Panel detail = panel();
        detail.detail = true;
        detail.metadataVersion = 10;
        detail.configVersion = 3;
        Panel summary = panel();
        summary.binding = detail.binding;
        summary.metadataVersion = 11;
        assertTrue(PortableSuperDashboard.sameDisplaySource(detail, summary));
    }

    @Test
    public void acknowledgedOmissionKeepsDisplayButChangedMetadataOrSourceDoesNot() {
        Panel old = panel(), next = panel();
        next.binding = old.binding;
        old.detail = next.detail = true;
        old.metadataVersion = next.metadataVersion = 7;
        next.metadataUnchanged = true;
        assertTrue(PortableSuperDashboard.sameDisplaySource(old, next));
        next.metadataVersion++;
        assertFalse(PortableSuperDashboard.sameDisplaySource(old, next));
        next.metadataVersion--;
        next.binding = new MonitorBindings.Binding(0, 99, 2, 3, old.binding.panelId);
        assertFalse(PortableSuperDashboard.sameDisplaySource(old, next));
    }

    @Test
    public void rawConfigGenerationIsCheckedBeforeHistoryReconstruction() {
        Panel old = panel(), next = panel();
        next.binding = old.binding;
        old.detail = next.detail = true;
        old.metadataVersion = next.metadataVersion = 1;
        old.configVersion = 10;
        next.historyPatch = new PortableHistoryPatch();
        next.historyPatch.configVersion = 10;
        assertTrue(PortableSuperDashboard.sameDisplaySource(old, next));
        next.historyPatch.configVersion = 11;
        assertFalse(PortableSuperDashboard.sameDisplaySource(old, next));
    }

    private static Panel panel() {
        Panel panel = new Panel();
        panel.binding = new MonitorBindings.Binding(0, 1, 2, 3, UUID.randomUUID());
        return panel;
    }
}
