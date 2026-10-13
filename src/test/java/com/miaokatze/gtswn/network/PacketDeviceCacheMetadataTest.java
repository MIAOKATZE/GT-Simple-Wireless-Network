package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketDeviceCacheMetadataTest {

    private static final UUID ID = UUID.fromString("12345678-1234-5678-1234-567812345678");

    @Test
    public void knownVersionAndCompleteCacheRoundTripWithoutAuthoritativeData() {
        ByteBuf b = Unpooled.buffer();
        try {
            new PacketRequestDeviceTerminalData(ID, 42, true).toBytes(b);
            assertEquals(25, b.readableBytes());
            PacketRequestDeviceTerminalData p = new PacketRequestDeviceTerminalData();
            p.fromBytes(b);
            assertTrue(p.isValid());
            assertTrue(p.hasClientState());
            assertTrue(p.isComplete());
            assertEquals(ID, p.getTerminalId());
            assertEquals(42, p.getKnownVersion());
        } finally {
            b.release();
        }
    }

    @Test
    public void missingAndLegacyCacheRequestsStayDistinctAndMalformedMetadataFails() {
        ByteBuf b = Unpooled.buffer();
        try {
            PacketRequestDeviceTerminalData legacy = new PacketRequestDeviceTerminalData();
            legacy.fromBytes(b);
            assertTrue(legacy.isValid());
            assertFalse(legacy.hasClientState());
            new PacketRequestDeviceTerminalData(ID, -1, false).toBytes(b);
            PacketRequestDeviceTerminalData missing = new PacketRequestDeviceTerminalData();
            missing.fromBytes(b);
            assertTrue(missing.isValid());
            assertTrue(missing.hasClientState());
            assertEquals(-1, missing.getKnownVersion());
            assertFalse(missing.isComplete());
            b.clear()
                .writeLong(ID.getMostSignificantBits())
                .writeLong(ID.getLeastSignificantBits())
                .writeLong(-2)
                .writeByte(0);
            missing.fromBytes(b);
            assertFalse(missing.isValid());
        } finally {
            b.release();
        }
    }

    @Test
    public void keepAliveIsExplicitNegativePageNotAnEmptyTerminalDataPage() {
        ByteBuf b = Unpooled.buffer();
        try {
            new PacketSyncDeviceTerminalData(ID, 5, -1, 4, 512, Collections.emptyList()).toBytes(b);
            PacketSyncDeviceTerminalData decoded = new PacketSyncDeviceTerminalData();
            decoded.fromBytes(b);
            assertEquals(ID, decoded.getTerminalId());
            assertEquals(-1, decoded.getPageIndex());
            assertEquals(4, decoded.getPageTotal());
            assertEquals(512, decoded.getEntryTotal());
            assertTrue(
                decoded.getEntries()
                    .isEmpty());
        } finally {
            b.release();
        }
    }
}
