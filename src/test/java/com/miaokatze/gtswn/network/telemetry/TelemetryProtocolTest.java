package com.miaokatze.gtswn.network.telemetry;

import static org.junit.Assert.*;

import java.util.Random;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketMonitorCapabilities;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class TelemetryProtocolTest {

    @Test
    public void tokenBucketsUseActualBytesAndRecoverRollback() {
        TelemetryByteBudget bucket = new TelemetryByteBudget(100, 1000);
        assertTrue(bucket.available(1000, 0));
        bucket.consume(1000);
        assertFalse(bucket.available(1, 0));
        assertTrue(bucket.available(5, 1));
        bucket.consume(5);
        assertTrue(bucket.available(100, 21));
        bucket.consume(100);
        assertFalse(bucket.available(1, 21));
        assertTrue(bucket.available(1000, 0));
        assertFalse(bucket.available(1001, 100000));
    }

    @Test
    public void arbitraryChangesRoundTripFromExactBaseline() {
        Random random = new Random(8473);
        for (int round = 0; round < 300; round++) {
            byte[] before = new byte[random.nextInt(4000)];
            random.nextBytes(before);
            byte[] after = before.clone();
            for (int changes = 0; changes < round % 17 && after.length > 0; changes++)
                after[random.nextInt(after.length)]++;
            ByteDelta delta = ByteDelta.between(before, after);
            ByteBuf wire = Unpooled.buffer();
            try {
                delta.write(wire);
                assertArrayEquals(
                    after,
                    ByteDelta.read(wire)
                        .apply(before));
                assertFalse(wire.isReadable());
            } finally {
                wire.release();
            }
        }
    }

    @Test
    public void emptyResetAndWrongLengthAreExplicit() {
        ByteDelta empty = ByteDelta.between(new byte[] { 4 }, new byte[0]);
        assertTrue(empty.full);
        assertArrayEquals(new byte[0], empty.apply(null));
        byte[] unchanged = new byte[100];
        ByteDelta heartbeat = ByteDelta.between(unchanged, unchanged);
        assertFalse(heartbeat.full);
        assertEquals(0, heartbeat.ranges.length);
        assertNull(heartbeat.apply(new byte[99]));
    }

    @Test
    public void rangeBoundsAndOverlapAreRejected() {
        ByteBuf wire = Unpooled.buffer();
        try {
            wire.writeBoolean(false)
                .writeInt(10)
                .writeShort(2);
            wire.writeInt(1)
                .writeInt(4)
                .writeInt(0);
            wire.writeInt(3)
                .writeInt(1)
                .writeByte(0);
            try {
                ByteDelta.read(wire);
                fail("overlap accepted");
            } catch (IllegalArgumentException expected) {}
        } finally {
            wire.release();
        }
    }

    @Test
    public void fullResetNeverAliasesMutableCallerData() {
        byte[] source = { 1, 2, 3 };
        ByteDelta reset = ByteDelta.between(null, source);
        source[0] = 99;
        assertArrayEquals(new byte[] { 1, 2, 3 }, reset.apply(null));
    }

    @Test
    public void capabilityPacketRejectsWrongLengthNegativeNonceAndUnknownFlags() {
        for (long nonce : new long[] { -1, 0, 1 })
            for (int bits : new int[] { MonitorCapabilities.ALL, MonitorCapabilities.ALL | (1 << 30) }) {
                ByteBuf buffer = Unpooled.buffer();
                try {
                    buffer.writeLong(nonce)
                        .writeInt(bits);
                    PacketMonitorCapabilities packet = new PacketMonitorCapabilities();
                    packet.fromBytes(buffer);
                    assertEquals(nonce > 0 && bits == MonitorCapabilities.ALL ? nonce : 0, packet.nonce);
                } finally {
                    buffer.release();
                }
            }
    }
}
