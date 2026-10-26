package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketDeviceTerminalDeltaTest {

    @Test
    public void requestBinaryIdentityRoundTripsAndRejectsTruncationTrailingAndUnknownSchema() {
        PacketRequestDeviceTerminalDelta request = new PacketRequestDeviceTerminalDelta(
            UUID.randomUUID(),
            UUID.randomUUID(),
            123,
            Long.MAX_VALUE - 1,
            Long.MAX_VALUE);
        ByteBuf bytes = Unpooled.buffer();
        try {
            request.toBytes(bytes);
            assertEquals(PacketRequestDeviceTerminalDelta.WIRE_BYTES, bytes.readableBytes());
            PacketRequestDeviceTerminalDelta decoded = new PacketRequestDeviceTerminalDelta();
            decoded.fromBytes(bytes.duplicate());
            assertTrue(decoded.isValid());
            assertEquals(request.getTerminalId(), decoded.getTerminalId());
            assertEquals(request.getSession(), decoded.getSession());
            assertEquals(123, decoded.getEpoch());
            assertEquals(Long.MAX_VALUE - 1, decoded.getKnownVersion());
            assertEquals(Long.MAX_VALUE, decoded.getPendingVersion());
            for (int length = 0; length < bytes.readableBytes(); length++) {
                decoded.fromBytes(bytes.slice(0, length));
                assertFalse(decoded.isValid());
            }
            bytes.setByte(0, 2);
            decoded.fromBytes(bytes.duplicate());
            assertFalse(decoded.isValid());
            bytes.setByte(0, 1);
            bytes.writeByte(0);
            decoded.fromBytes(bytes.duplicate());
            assertFalse(decoded.isValid());
        } finally {
            bytes.release();
        }
    }

    @Test
    public void syncExactBytesRoundtripAndMalformedHeadersCannotCreateAuthority() {
        UUID terminal = UUID.randomUUID(), session = UUID.randomUUID();
        byte[] payload = new byte[] { 1, 0, 0, 0, 0 };
        PacketSyncDeviceTerminalDelta message = new PacketSyncDeviceTerminalDelta(
            terminal,
            session,
            9,
            -1,
            100,
            PacketSyncDeviceTerminalDelta.FULL,
            0,
            1,
            0,
            payload);
        ByteBuf bytes = Unpooled.buffer();
        try {
            message.toBytes(bytes);
            assertEquals(message.encodedBytes(), bytes.readableBytes());
            assertEquals(71, message.encodedBytes());
            PacketSyncDeviceTerminalDelta decoded = new PacketSyncDeviceTerminalDelta();
            decoded.fromBytes(bytes.duplicate());
            assertEquals(terminal, decoded.getTerminalId());
            assertEquals(session, decoded.getSession());
            assertEquals(9, decoded.getEpoch());
            assertEquals(100, decoded.getVersion());
            assertArrayEquals(payload, decoded.getPayload());
            for (int length = 0; length < bytes.readableBytes(); length++) {
                decoded.fromBytes(bytes.slice(0, length));
                assertNull(decoded.getTerminalId());
            }
            bytes.setByte(1, 255);
            decoded.fromBytes(bytes.duplicate());
            assertNull(decoded.getTerminalId());
            bytes.setByte(1, PacketSyncDeviceTerminalDelta.FULL);
            bytes.setInt(62, Integer.MAX_VALUE);
            decoded.fromBytes(bytes.duplicate());
            assertNull(decoded.getTerminalId());
        } finally {
            bytes.release();
        }
    }

    @Test
    public void legacyRequestWireRemainsEmptyOrExactlyTwentyFiveBytes() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            new PacketRequestDeviceTerminalData().toBytes(bytes);
            assertEquals(0, bytes.readableBytes());
            new PacketRequestDeviceTerminalData(UUID.randomUUID(), 7, true).toBytes(bytes);
            assertEquals(25, bytes.readableBytes());
            PacketRequestDeviceTerminalData decoded = new PacketRequestDeviceTerminalData();
            decoded.fromBytes(bytes);
            assertTrue(decoded.isValid());
            assertEquals(7, decoded.getKnownVersion());
        } finally {
            bytes.release();
        }
    }
}
