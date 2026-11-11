// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.network.PacketRequestDeviceTerminalDelta;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalDelta;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class DeviceTerminalPagedDeliveryTest {

    @Test
    public void configuredCapacityAndBoundaryCompleteWithoutClipping() {
        for (int count : new int[] { 1024, 1025, 65536 }) {
            DeviceTerminalClientCache.clear();
            UUID id = UUID.randomUUID();
            PacketRequestDeviceTerminalDelta request = DeviceTerminalClientCache.compactRequestMetadata(id)
                .paged();
            DeviceTerminalDeltaDelivery delivery = new DeviceTerminalDeltaDelivery(id);
            List<Entry> rows = DeviceTerminalDeltaDeliveryTest.rows(count, 7);
            delivery.prepare(request, "source", 1, () -> rows);
            int pages = 0;
            while (delivery.hasPage()) {
                PacketSyncDeviceTerminalDelta sent = delivery.next();
                assertEquals(2, sent.getWireVersion());
                assertEquals(count, sent.getEntryTotal());
                ByteBuf bytes = Unpooled.buffer();
                try {
                    sent.toBytes(bytes);
                    assertEquals(sent.encodedBytes(), bytes.readableBytes());
                    PacketSyncDeviceTerminalDelta read = new PacketSyncDeviceTerminalDelta();
                    read.fromBytes(bytes);
                    assertEquals(id, read.getTerminalId());
                    DeviceTerminalClientCache.receiveDelta(read);
                    DeviceTerminalClientCache.receiveDelta(read); // duplicate page never grows the committed list
                } finally {
                    bytes.release();
                }
                delivery.sent(sent, pages++);
                if (delivery.hasPage()) assertNull(DeviceTerminalClientCache.getSnapshot(id));
            }
            assertEquals((count + 127) / 128, pages);
            assertEquals(count, DeviceTerminalClientCache.getSnapshot(id).entries.size());
            assertEquals(rows.get(count - 1).key, DeviceTerminalClientCache.getSnapshot(id).entries.get(count - 1).key);
            delivery.prepare(
                DeviceTerminalClientCache.compactRequestMetadata(id)
                    .paged(),
                "source",
                2,
                () -> rows);
            assertEquals(1, delivery.acknowledgedVersion());
            assertTrue(delivery.hasPage());
            assertEquals(
                count > 1024 ? PacketSyncDeviceTerminalDelta.FULL : PacketSyncDeviceTerminalDelta.DELTA,
                delivery.next()
                    .getKind());
        }
    }

    @Test
    public void oldWireRetainsOriginalLayoutAndCapacity() {
        UUID id = UUID.randomUUID();
        PacketSyncDeviceTerminalDelta old = new PacketSyncDeviceTerminalDelta(
            id,
            UUID.randomUUID(),
            1,
            -1,
            1,
            PacketSyncDeviceTerminalDelta.FULL,
            7,
            8,
            1024,
            new byte[0]);
        ByteBuf bytes = Unpooled.buffer();
        try {
            old.toBytes(bytes);
            assertEquals(1, bytes.getUnsignedByte(0));
            assertEquals(66, bytes.readableBytes());
        } finally {
            bytes.release();
        }
        try {
            new PacketSyncDeviceTerminalDelta(
                id,
                UUID.randomUUID(),
                1,
                -1,
                1,
                PacketSyncDeviceTerminalDelta.FULL,
                8,
                9,
                1025,
                new byte[0]);
            fail();
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void v2RequestKeepsEnvelopeAndUnknownWireFailsClosed() {
        UUID id = UUID.randomUUID();
        PacketRequestDeviceTerminalDelta request = new PacketRequestDeviceTerminalDelta(
            id,
            UUID.randomUUID(),
            0,
            -1,
            -1).paged();
        ByteBuf bytes = Unpooled.buffer();
        try {
            request.toBytes(bytes);
            assertEquals(57, bytes.readableBytes());
            PacketRequestDeviceTerminalDelta read = new PacketRequestDeviceTerminalDelta();
            read.fromBytes(bytes);
            assertTrue(read.isValid());
            assertEquals(2, read.getWireVersion());
            bytes.readerIndex(0);
            bytes.setByte(0, 3);
            read.fromBytes(bytes);
            assertFalse(read.isValid());
        } finally {
            bytes.release();
        }
    }
}
