package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PortablePanelMetadataTest {

    @Test
    public void amountsDoNotChangeDictionaryButReorderingOrRenamingDoes() {
        PacketSyncPortableMonitor.Panel panel = panel();
        byte[] first = PortablePanelMetadata.identity(panel);
        panel.rows.get(0).amount = "999999999999999999999999";
        panel.rows.get(0).rate = 3.5;
        assertArrayEquals(first, PortablePanelMetadata.identity(panel));
        java.util.Collections.reverse(panel.rows);
        assertFalse(Arrays.equals(first, PortablePanelMetadata.identity(panel)));
        java.util.Collections.reverse(panel.rows);
        panel.rows.get(0).name = "renamed";
        assertFalse(Arrays.equals(first, PortablePanelMetadata.identity(panel)));
    }

    @Test
    public void omittedDictionaryRestoresOnlyFromExactAcknowledgedRevision() {
        PacketSyncPortableMonitor.Panel base = panel();
        base.metadataVersion = 5;
        base.metadataUnchanged = true;
        PacketSyncPortableMonitor.Panel wire = PortablePanelMetadata.wire(base);
        wire.metadataVersion = 5;
        wire.metadataUnchanged = true;
        assertEquals("", wire.rows.get(0).key);
        assertFalse(PortablePanelMetadata.restore(wire, null));
        PacketSyncPortableMonitor.Panel wrong = panel();
        wrong.metadataVersion = 4;
        assertFalse(PortablePanelMetadata.restore(wire, wrong));
        assertTrue(PortablePanelMetadata.restore(wire, base));
        assertEquals("key:a", wire.rows.get(0).key);
        assertEquals("Alpha", wire.rows.get(0).name);
        assertEquals("123", wire.rows.get(0).amount);
    }

    @Test
    public void envelopeOmitsMetadataWithoutMutatingPublishedResponseAndPreservesRawClocks() {
        PacketSyncPortableMonitor packet = new PacketSyncPortableMonitor(UUID.randomUUID(), 8, 1);
        PacketSyncPortableMonitor.Panel panel = panel();
        panel.metadataVersion = 5;
        packet.panels.add(panel);
        ByteBuf full = Unpooled.buffer(), compact = Unpooled.buffer();
        try {
            new PacketSyncPortableHistory(packet).toBytes(full);
            panel.metadataUnchanged = true;
            new PacketSyncPortableHistory(packet).toBytes(compact);
            assertTrue(compact.readableBytes() < full.readableBytes());
            assertEquals("key:a", panel.rows.get(0).key);
            PacketSyncPortableHistory decoded = new PacketSyncPortableHistory();
            decoded.fromBytes(compact.duplicate());
            assertTrue(decoded.valid);
            PacketSyncPortableMonitor.Panel received = decoded.snapshot.panels.get(0);
            assertEquals("", received.rows.get(0).key);
            assertEquals(1200, received.rows.get(0).sampleTime);
            assertTrue(PortablePanelMetadata.restore(received, panel));
            assertEquals("Alpha", received.rows.get(0).name);
        } finally {
            full.release();
            compact.release();
        }
    }

    private static PacketSyncPortableMonitor.Panel panel() {
        PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
        panel.binding = new MonitorBindings.Binding(0, 1, 2, 3, new UUID(2, 3));
        panel.ownerName = "owner";
        panel.window = "300s";
        panel.chartKey = "key:a";
        for (String id : new String[] { "a", "b" }) {
            PacketSyncPortableMonitor.Row row = new PacketSyncPortableMonitor.Row();
            row.kind = 1;
            row.key = "key:" + id;
            row.name = id.equals("a") ? "Alpha" : "Beta";
            row.amount = "123";
            row.sampleTime = 1200;
            row.sampleTick = 24;
            panel.rows.add(row);
        }
        return panel;
    }
}
