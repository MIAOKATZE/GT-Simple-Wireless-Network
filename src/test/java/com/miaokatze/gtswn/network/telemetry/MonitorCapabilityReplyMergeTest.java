package com.miaokatze.gtswn.network.telemetry;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketMonitorCapabilities;

public class MonitorCapabilityReplyMergeTest {

    @Test
    public void alreadySentStrongReplySurvivesLaterFallbackForSameNonce() {
        UUID actor = UUID.randomUUID();
        PacketMonitorCapabilities sent = new PacketMonitorCapabilities(42, MonitorCapabilities.ALL, actor);
        PacketMonitorCapabilities merged = MonitorCapabilities
            .mergeReply(sent, 42, MonitorCapabilities.LEGACY_ALL, null);
        assertEquals(actor, merged.playerId);
        assertEquals(MonitorCapabilities.ALL, merged.bits);
        PacketMonitorCapabilities fresh = MonitorCapabilities
            .mergeReply(sent, 43, MonitorCapabilities.LEGACY_ALL, null);
        assertNull(fresh.playerId);
        assertEquals(MonitorCapabilities.LEGACY_ALL, fresh.bits);
    }
}
