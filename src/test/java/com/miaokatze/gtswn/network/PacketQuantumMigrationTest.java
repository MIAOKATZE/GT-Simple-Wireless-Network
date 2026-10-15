package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketQuantumMigrationTest {

    @Test
    public void everyIntentRoundTripsWithSessionAndNegativeCoordinates() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (int action = 0; action <= 3; action++) {
                buffer.clear();
                new PacketQuantumMigration(action, 12345678901L, -300, 80, 42).toBytes(buffer);
                assertEquals(21, buffer.readableBytes());
                PacketQuantumMigration decoded = new PacketQuantumMigration();
                decoded.fromBytes(buffer);
                assertEquals(action, decoded.action);
                assertEquals(12345678901L, decoded.session);
                assertEquals(-300, decoded.x);
                assertEquals(80, decoded.y);
                assertEquals(42, decoded.z);
            }
        } finally {
            buffer.release();
        }
    }

    @Test
    public void truncatedTrailingAndUnknownIntentOrSessionAreRejected() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (int length = 0; length <= 22; length++) {
                if (length == 21) continue;
                buffer.clear()
                    .writeZero(length);
                PacketQuantumMigration decoded = new PacketQuantumMigration();
                decoded.fromBytes(buffer);
                assertEquals(-1, decoded.action);
            }
            for (long[] invalid : new long[][] { { -1, 1 }, { 4, 1 }, { 0, 0 }, { 0, -1 } }) {
                buffer.clear();
                new PacketQuantumMigration((int) invalid[0], invalid[1], 0, 0, 0).toBytes(buffer);
                PacketQuantumMigration decoded = new PacketQuantumMigration();
                decoded.fromBytes(buffer);
                assertEquals(-1, decoded.action);
            }
        } finally {
            buffer.release();
        }
    }

    @Test
    public void cancellationBypassesFullQueueAndRetainsHighestSessionBarrier() {
        PacketQuantumMigration.Inbox inbox = new PacketQuantumMigration.Inbox();
        for (int index = 0; index < 64; index++) inbox.add(request(PacketQuantumMigration.COMPLETE, 5));
        PacketQuantumMigration.Request cancel = request(PacketQuantumMigration.CANCEL, 5);
        inbox.add(cancel);
        inbox.add(request(PacketQuantumMigration.CANCEL, 4));
        assertSame(cancel, inbox.pollCancellation());
        assertNull(inbox.pollCancellation());
        for (int index = 0; index < 64; index++)
            assertEquals(PacketQuantumMigration.COMPLETE, inbox.poll().message.action);
        assertNull(inbox.poll());
        inbox.add(request(PacketQuantumMigration.BEGIN, 6));
        inbox.add(request(PacketQuantumMigration.CANCEL, 6));
        inbox.add(request(PacketQuantumMigration.CANCEL, 7));
        assertEquals(7, inbox.pollCancellation().message.session);
        assertEquals(PacketQuantumMigration.BEGIN, inbox.poll().message.action);
    }

    @Test
    public void resultAcknowledgesExactSessionAndRejectsMalformedFrames() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (int result = 0; result <= 2; result++) {
                buffer.clear();
                new PacketQuantumMigrationResult(777, result).toBytes(buffer);
                assertEquals(9, buffer.readableBytes());
                PacketQuantumMigrationResult decoded = new PacketQuantumMigrationResult();
                decoded.fromBytes(buffer);
                assertEquals(777, decoded.session);
                assertEquals(result, decoded.result);
            }
            for (int length = 0; length <= 10; length++) {
                if (length == 9) continue;
                buffer.clear()
                    .writeZero(length);
                PacketQuantumMigrationResult decoded = new PacketQuantumMigrationResult();
                decoded.fromBytes(buffer);
                assertEquals(-1, decoded.result);
            }
            buffer.clear();
            new PacketQuantumMigrationResult(777, 3).toBytes(buffer);
            PacketQuantumMigrationResult decoded = new PacketQuantumMigrationResult();
            decoded.fromBytes(buffer);
            assertEquals(-1, decoded.result);
        } finally {
            buffer.release();
        }
    }

    private static PacketQuantumMigration.Request request(int action, long session) {
        return new PacketQuantumMigration.Request(null, new PacketQuantumMigration(action, session, 0, 0, 0));
    }
}
