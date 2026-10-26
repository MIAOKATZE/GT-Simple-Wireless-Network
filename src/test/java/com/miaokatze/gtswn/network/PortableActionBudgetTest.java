package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

public class PortableActionBudgetTest {

    @Test
    public void mutationSnapshotsNeverUseUnbudgetedControlLane() {
        for (int result = 1; result <= 3; result++) {
            PacketSyncPortableMonitor response = new PacketSyncPortableMonitor(UUID.randomUUID(), 7, 0);
            response.result = result;
            assertTrue(PortableMonitorRequestQueue.lightweightControl(response));
            response.panels.add(new PacketSyncPortableMonitor.Panel());
            assertFalse(PortableMonitorRequestQueue.lightweightControl(response));
        }
    }

    @Test
    public void deniedBudgetRetainsTheExactActionResultForRetry() {
        PacketSyncPortableMonitor response = new PacketSyncPortableMonitor(UUID.randomUUID(), 9, 1);
        response.result = 1;
        PortableMonitorRequestQueue.ActionDelivery pending = new PortableMonitorRequestQueue.ActionDelivery(
            response,
            new Object());
        pending.recordAdmission(com.miaokatze.gtswn.network.telemetry.TelemetryTransport.Admission.REJECTED);
        assertFalse(pending.queued);
        assertSame(response, pending.response);
        assertEquals(9, pending.response.sequence);
        pending.recordAdmission(com.miaokatze.gtswn.network.telemetry.TelemetryTransport.Admission.QUEUED);
        assertTrue(pending.queued);
        assertSame(response, pending.response);
        assertEquals(1, pending.response.result);
    }
}
