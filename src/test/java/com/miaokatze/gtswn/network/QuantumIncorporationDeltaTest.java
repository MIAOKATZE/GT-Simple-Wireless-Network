package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketSyncQuantumIncorporationState.Position;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class QuantumIncorporationDeltaTest {

    private static PacketSyncQuantumIncorporationState snapshot(Position... positions) {
        return new PacketSyncQuantumIncorporationState(-1, -2, 3, Arrays.asList(positions));
    }

    @Test
    public void acknowledgedDeltaAddsUpdatesRemovesAndClears() {
        Position old = new Position(1, new UUID(1, 2)), kept = new Position(2, new UUID(3, 4));
        Position changed = new Position(2, new UUID(5, 6)), added = new Position(3, new UUID(7, 8));
        PacketSyncQuantumIncorporationState base = snapshot(old, kept), target = snapshot(changed, added);
        PacketQuantumIncorporationDelta delta = new PacketQuantumIncorporationDelta(target, base, 7, 8);
        assertEquals(Collections.singletonList(1), delta.removals);
        assertEquals(2, delta.upserts.size());
        ByteBuf buffer = Unpooled.buffer();
        try {
            delta.toBytes(buffer);
            PacketQuantumIncorporationDelta decoded = new PacketQuantumIncorporationDelta();
            decoded.fromBytes(buffer);
            assertTrue(decoded.valid);
            PacketSyncQuantumIncorporationState applied = decoded.apply(base);
            assertEquals(
                2,
                applied.getPositions()
                    .size());
            assertTrue(
                applied.getPositions()
                    .stream()
                    .anyMatch(p -> p.local == 2 && p.identityMost == 5));
            PacketQuantumIncorporationDelta empty = new PacketQuantumIncorporationDelta(snapshot(), applied, 8, 9);
            assertTrue(
                empty.apply(applied)
                    .getPositions()
                    .isEmpty());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void resetWorksWithoutBaselineAndDeltaDoesNot() {
        PacketSyncQuantumIncorporationState value = snapshot(new Position(6, UUID.randomUUID()));
        PacketQuantumIncorporationDelta reset = new PacketQuantumIncorporationDelta(value, null, 0, 1);
        assertTrue(reset.full);
        assertEquals(
            1,
            reset.apply(null)
                .getPositions()
                .size());
        PacketQuantumIncorporationDelta delta = new PacketQuantumIncorporationDelta(value, value, 1, 2);
        assertNull(delta.apply(null));
    }

    @Test
    public void duplicatePositionsAndTruncationAreRejected() {
        Position same = new Position(1, UUID.randomUUID());
        PacketQuantumIncorporationDelta packet = new PacketQuantumIncorporationDelta(snapshot(same, same), null, 0, 1);
        ByteBuf buffer = Unpooled.buffer();
        try {
            packet.toBytes(buffer);
            PacketQuantumIncorporationDelta decoded = new PacketQuantumIncorporationDelta();
            decoded.fromBytes(buffer);
            assertFalse(decoded.valid);
            buffer.clear();
            packet.toBytes(buffer);
            buffer.writerIndex(buffer.writerIndex() - 1);
            decoded.fromBytes(buffer);
            assertFalse(decoded.valid);
        } finally {
            buffer.release();
        }
    }
}
