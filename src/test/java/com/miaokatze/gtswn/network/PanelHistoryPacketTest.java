package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PanelHistoryPacketTest {

    @Test
    public void physicalHistoryPreservesExactValuesAndBothOriginalClocks() {
        PortableMonitorRequestQueue.RenderCache cache = new PortableMonitorRequestQueue.RenderCache();
        cache.version = 3;
        cache.configVersion = 1;
        cache.eu = new ArrayList<>();
        cache.ae = new ArrayList<>();
        BigInteger energy = BigInteger.TEN.pow(600);
        cache.eu.add(new NetworkInfoSample(123456, 200, energy, 2.5));
        cache.ae.add(new AEMonitorSample(654321, 201, Long.MAX_VALUE, 1.5));
        PacketSyncPanelHistory packet = new PacketSyncPanelHistory();
        packet.panelId = UUID.randomUUID();
        packet.sessionId = UUID.randomUUID();
        packet.sequence = 8;
        packet.history = PortableHistoryPatch.between(null, cache, true, true);
        packet.energy = energy.toString();
        packet.aePresent = true;
        packet.latest.put("item:test", cache.ae.get(0));
        packet.averages.put("item:test", 2.0);
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.toBytes(bytes);
            PacketSyncPanelHistory decoded = new PacketSyncPanelHistory();
            decoded.fromBytes(bytes.duplicate());
            assertTrue(decoded.valid);
            assertEquals(energy, decoded.history.eu.get(0).eu);
            assertEquals(123456, decoded.history.eu.get(0).timeMs);
            assertEquals(654321, decoded.latest.get("item:test").timeMs);
            assertEquals(Long.MAX_VALUE, decoded.latest.get("item:test").amount);
            for (int size = 0; size < bytes.readableBytes(); size++) {
                decoded = new PacketSyncPanelHistory();
                decoded.fromBytes(bytes.slice(0, size));
                assertFalse(decoded.valid);
            }
        } finally {
            bytes.release();
        }
    }

    @Test
    public void ackIsExactLengthAndRejectsNegativeRevisions() {
        PacketSyncPanelHistory source = new PacketSyncPanelHistory();
        source.panelId = UUID.randomUUID();
        source.sessionId = UUID.randomUUID();
        ByteBuf bytes = Unpooled.buffer();
        try {
            new PacketAckPanelHistory(source, 12).toBytes(bytes);
            assertEquals(52, bytes.readableBytes());
            PacketAckPanelHistory ack = new PacketAckPanelHistory();
            ack.fromBytes(bytes.duplicate());
            assertTrue(ack.valid);
            assertEquals(12, ack.sequence);
            bytes.setLong(44, -1);
            ack.fromBytes(bytes.duplicate());
            assertFalse(ack.valid);
        } finally {
            bytes.release();
        }
    }

    @Test
    public void sourceChangeCannotAppendIdenticalLookingOldSourceSamples() {
        PortableMonitorRequestQueue.RenderCache first = new PortableMonitorRequestQueue.RenderCache();
        first.version = 1;
        first.configVersion = 1;
        first.eu = new ArrayList<>();
        first.ae = new ArrayList<>();
        first.eu.add(new NetworkInfoSample(50, 1, BigInteger.ONE, 0));
        first.render.euSource = "source-a";
        PortableMonitorRequestQueue.RenderCache next = new PortableMonitorRequestQueue.RenderCache();
        next.version = 2;
        next.configVersion = 1;
        next.eu = first.eu;
        next.ae = first.ae;
        next.render.euSource = "source-b";
        PortableHistoryPatch patch = PortableHistoryPatch.between(first, next, true, true);
        assertTrue(patch.euReset);
        assertEquals(1, patch.eu.size());
    }

    @Test
    public void acknowledgedBaseCancelsAndRebuildsPendingPatchAgainstNewCompleteBase() {
        PortableMonitorRequestQueue.RenderCache a = history(1), b = history(2), c = history(3);
        NetworkPanelBroadcastPort.Recipient recipient = new NetworkPanelBroadcastPort.Recipient(new Object());
        recipient.base = a;
        recipient.ack = 1;
        recipient.sent.put(2L, b);
        final PortableHistoryPatch[] pending = { PortableHistoryPatch.between(a, c, true, true) };
        java.util.concurrent.atomic.AtomicInteger rebuilds = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BooleanSupplier cancel = () -> {
            boolean had = pending[0] != null;
            pending[0] = null;
            return had;
        };
        Runnable rebuild = () -> {
            pending[0] = PortableHistoryPatch.between(recipient.base, c, true, true);
            rebuilds.incrementAndGet();
        };
        NetworkPanelBroadcastPort.acknowledge(recipient, 2, cancel, rebuild);
        assertEquals(2, pending[0].baseVersion);
        assertEquals(3, pending[0].targetVersion);
        assertEquals(1, rebuilds.get());
        NetworkPanelBroadcastPort.acknowledge(recipient, 2, cancel, rebuild);
        NetworkPanelBroadcastPort.acknowledge(recipient, 99, cancel, rebuild);
        assertEquals(1, rebuilds.get());
        NetworkPanelBroadcastPort.acknowledge(recipient, 0, cancel, rebuild);
        assertEquals(0, pending[0].baseVersion);
        assertTrue(pending[0].euReset);
        assertTrue(pending[0].aeReset);
        assertEquals(2, rebuilds.get());
        assertTrue(recipient.sent.isEmpty());
        // A delayed pre-reset ACK cannot resurrect the discarded baseline.
        NetworkPanelBroadcastPort.acknowledge(recipient, 2, cancel, rebuild);
        assertNull(recipient.base);
        assertEquals(2, rebuilds.get());
    }

    private static PortableMonitorRequestQueue.RenderCache history(long version) {
        PortableMonitorRequestQueue.RenderCache value = new PortableMonitorRequestQueue.RenderCache();
        value.version = version;
        value.configVersion = 1;
        value.eu = new ArrayList<>();
        value.ae = new ArrayList<>();
        value.eu.add(new NetworkInfoSample(version * 50, version, BigInteger.valueOf(version), 1));
        return value;
    }

    @Test
    public void olderQueuedLegacyFullCannotClearNewerRejectedFramesRecoveryRequirement() {
        NetworkPanelBroadcastPort.Recipient recipient = new NetworkPanelBroadcastPort.Recipient(new Object());
        recipient.legacyNeedsFull = true;
        recipient.legacyGeneration = 3;
        recipient.legacyFullSent(2);
        assertTrue(recipient.legacyNeedsFull);
        recipient.legacyFullSent(3);
        assertFalse(recipient.legacyNeedsFull);
    }

}
