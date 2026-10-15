package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketRequestQuantumInfoTest {

    @Test
    public void pointedPositionRoundTripsWithNegativeWorldCoordinates() {
        ByteBuf buffer = Unpooled.buffer(), output = Unpooled.buffer();
        try {
            new PacketRequestQuantumInfo(-30000000, 255, 30000000).toBytes(buffer);
            assertEquals(12, buffer.readableBytes());
            PacketRequestQuantumInfo decoded = new PacketRequestQuantumInfo();
            decoded.fromBytes(buffer);
            assertTrue(decoded.valid);
            decoded.toBytes(output);
            assertEquals(-30000000, output.readInt());
            assertEquals(255, output.readInt());
            assertEquals(30000000, output.readInt());
            assertFalse(output.isReadable());
        } finally {
            buffer.release();
            output.release();
        }
    }

    @Test
    public void truncatedAndTrailingFramesAreRejectedWithoutDefaultCoordinateFallback() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (int length = 0; length <= 24; length++) {
                if (length == 12) continue;
                buffer.clear()
                    .writeZero(length);
                PacketRequestQuantumInfo decoded = new PacketRequestQuantumInfo();
                decoded.fromBytes(buffer);
                assertFalse("length=" + length, decoded.valid);
                assertEquals(length, buffer.readableBytes());
            }
            PacketRequestQuantumInfo reused = new PacketRequestQuantumInfo(1, 2, 3);
            assertTrue(reused.valid);
            buffer.clear();
            reused.fromBytes(buffer);
            assertFalse(reused.valid);
        } finally {
            buffer.release();
        }
    }
}
