package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketSyncQuantumIncorporationStateTest {

    @Test
    public void roundTripPreservesNegativeChunkCoordinatesAndReplacementIdentity() {
        UUID first = UUID.fromString("01234567-89ab-cdef-fedc-ba9876543210");
        UUID replacement = UUID.fromString("ffffffff-ffff-ffff-0000-000000000001");
        PacketSyncQuantumIncorporationState original = new PacketSyncQuantumIncorporationState(
            -1,
            -17,
            23,
            Arrays.asList(
                new PacketSyncQuantumIncorporationState.Position(0, first),
                new PacketSyncQuantumIncorporationState.Position(65535, replacement, 14)));
        ByteBuf buffer = Unpooled.buffer();
        try {
            original.toBytes(buffer);
            PacketSyncQuantumIncorporationState decoded = new PacketSyncQuantumIncorporationState();
            decoded.fromBytes(buffer);
            assertTrue(decoded.isValid());
            assertEquals(-1, decoded.getDimension());
            assertEquals(-17, decoded.getChunkX());
            assertEquals(23, decoded.getChunkZ());
            assertEquals(
                2,
                decoded.getPositions()
                    .size());
            assertEquals(
                65535,
                decoded.getPositions()
                    .get(1).local);
            assertEquals(
                first.getMostSignificantBits(),
                decoded.getPositions()
                    .get(0).identityMost);
            assertEquals(
                first.getLeastSignificantBits(),
                decoded.getPositions()
                    .get(0).identityLeast);
            assertEquals(
                replacement.getLeastSignificantBits(),
                decoded.getPositions()
                    .get(1).identityLeast);
            assertEquals(
                14,
                decoded.getPositions()
                    .get(1).color);
            assertEquals(
                0,
                decoded.getPositions()
                    .get(0).color);
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void emptySnapshotIsValidWhileTruncatedSnapshotCannotClearCache() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new PacketSyncQuantumIncorporationState(0, 2, 3, Collections.emptyList()).toBytes(buffer);
            PacketSyncQuantumIncorporationState decoded = new PacketSyncQuantumIncorporationState();
            decoded.fromBytes(buffer);
            assertTrue(decoded.isValid());
            assertTrue(
                decoded.getPositions()
                    .isEmpty());
            buffer.clear();
            buffer.writeInt(0)
                .writeInt(2)
                .writeInt(3)
                .writeInt(1)
                .writeShort(7);
            decoded.fromBytes(buffer);
            assertFalse(decoded.isValid());
            assertTrue(
                decoded.getPositions()
                    .isEmpty());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void rejectsInvalidPaletteIndex() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeInt(0)
                .writeInt(0)
                .writeInt(0)
                .writeInt(1)
                .writeShort(0)
                .writeLong(1)
                .writeLong(2)
                .writeByte(16);
            PacketSyncQuantumIncorporationState decoded = new PacketSyncQuantumIncorporationState();
            decoded.fromBytes(buffer);
            assertFalse(decoded.isValid());
            assertTrue(
                decoded.getPositions()
                    .isEmpty());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void rejectsInvalidCountsAndUnexpectedTrailingBytes() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (int count : new int[] { -1, 65537, Integer.MAX_VALUE }) {
                buffer.clear();
                buffer.writeInt(0)
                    .writeInt(0)
                    .writeInt(0)
                    .writeInt(count);
                PacketSyncQuantumIncorporationState decoded = new PacketSyncQuantumIncorporationState();
                decoded.fromBytes(buffer);
                assertFalse(decoded.isValid());
            }
            buffer.clear();
            buffer.writeInt(0)
                .writeInt(0)
                .writeInt(0)
                .writeInt(0)
                .writeByte(1);
            PacketSyncQuantumIncorporationState decoded = new PacketSyncQuantumIncorporationState();
            decoded.fromBytes(buffer);
            assertFalse(decoded.isValid());
        } finally {
            buffer.release();
        }
    }
}
