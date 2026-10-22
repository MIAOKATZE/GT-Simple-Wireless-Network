package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Cell;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Node;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Power;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Review;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketEngineeringTest {

    @Test
    public void requestCarriesBoundedViewAndProviderExpectation() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            new PacketEngineeringSubscribe(17, 7, -1, -4, -4, 3, 3, true, true, false, true).toBytes(bytes);
            assertEquals(33, bytes.readableBytes());
            PacketEngineeringSubscribe decoded = new PacketEngineeringSubscribe();
            decoded.fromBytes(bytes);
            assertTrue(decoded.valid);
            assertEquals(17, decoded.sessionId);
            assertEquals(7, decoded.slot);
            assertEquals(-1, decoded.dim);
            assertEquals(-4, decoded.minCellX);
            assertTrue(decoded.boundOnly);
            assertTrue(decoded.gtGrid);
            assertTrue(decoded.baseline);
            assertFalse(decoded.close);
        } finally {
            bytes.release();
        }
    }

    @Test
    public void requestRejectsAppendedNbtAndOversizedOrUnknownSlotAndFlags() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            PacketEngineeringSubscribe request = new PacketEngineeringSubscribe(
                1,
                2,
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                false);
            request.toBytes(bytes);
            for (int offset : new int[] { 8, 29 }) {
                ByteBuf invalid = bytes.copy();
                try {
                    invalid.setByte(offset, offset == 8 ? 8 : 16);
                    PacketEngineeringSubscribe decoded = new PacketEngineeringSubscribe();
                    decoded.fromBytes(invalid);
                    assertFalse(decoded.valid);
                } finally {
                    invalid.release();
                }
            }
            bytes.writeByte(10);
            PacketEngineeringSubscribe decoded = new PacketEngineeringSubscribe();
            decoded.fromBytes(bytes);
            assertFalse(decoded.valid);
            assertFalse(new PacketEngineeringSubscribe(1, 2, 0, 0, 0, 64, 0, false, false, false).valid);
        } finally {
            bytes.release();
        }
    }

    @Test
    public void providerIdentityRejectsTruncationUnknownOriginAndOversizedLength() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            PacketEngineeringSubscribe request = new PacketEngineeringSubscribe(
                1,
                2,
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                false);
            request.sourceBinding = "existing-server-id";
            request.toBytes(bytes);
            for (int length = 0; length < bytes.readableBytes(); length++) {
                ByteBuf prefix = bytes.copy(0, length);
                try {
                    PacketEngineeringSubscribe decoded = new PacketEngineeringSubscribe();
                    decoded.fromBytes(prefix);
                    assertFalse("prefix=" + length, decoded.valid);
                } finally {
                    prefix.release();
                }
            }
            for (int offset : new int[] { 30, 31 }) {
                ByteBuf invalid = bytes.copy();
                try {
                    if (offset == 30) invalid.setByte(offset, 3);
                    else invalid.setShort(offset, 513);
                    PacketEngineeringSubscribe decoded = new PacketEngineeringSubscribe();
                    decoded.fromBytes(invalid);
                    assertFalse(decoded.valid);
                } finally {
                    invalid.release();
                }
            }
        } finally {
            bytes.release();
        }
    }

    private static EngineeringSnapshot snapshot(List<Node> nodes) {
        Power before = new Power(300, 5, 250, 4), current = new Power(105, 5, 90, 4);
        Review review = new Review(20, 1, 0, 1, new Power(100, 5, 80, 4), current, 8, 9, 8, 1, true, false);
        QuantumNetworkData quantum = new QuantumNetworkData();
        quantum.online = true;
        quantum.anchorDim = -1;
        quantum.anchorX = -32;
        quantum.anchorY = 64;
        quantum.totalChannels = 1024;
        quantum.usedChannels = 12;
        quantum.quantumNodeCount = 1;
        quantum.incorporationCount = 2;
        quantum.incorporationChannels = 11;
        return new EngineeringSnapshot(
            11,
            2,
            40,
            255,
            "ok",
            nodes,
            Collections.singletonList(new Cell(-1, 0, 1500, 100, current)),
            before,
            current,
            1500,
            100,
            2048,
            false,
            true,
            false,
            quantum,
            true,
            true,
            review);
    }

    @Test
    public void summaryReviewPatternAndCountsAboveOld1024LimitRoundTrip() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            Node node = new Node(
                1,
                -1,
                -32,
                64,
                0,
                "owner-uuid",
                "玩家",
                "NORTH",
                true,
                1,
                512,
                4.5,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                -1,
                -48,
                70,
                16,
                8);
            new PacketEngineeringSnapshot(snapshot(Collections.singletonList(node))).toBytes(bytes);
            PacketEngineeringSnapshot decoded = new PacketEngineeringSnapshot();
            decoded.fromBytes(bytes);
            EngineeringSnapshot s = decoded.snapshot;
            assertNotNull(s);
            assertTrue(s.axisSymmetrical);
            assertTrue(s.patternResolved);
            assertFalse(s.coverageBoundOnly);
            assertEquals(1500, s.monitored);
            assertEquals(2048, s.totalBound);
            assertEquals(1500, s.cells.get(0).monitored);
            assertEquals("玩家", s.nodes.get(0).ownerName);
            assertEquals(Long.MAX_VALUE, s.nodes.get(0).stored);
            assertEquals(20, s.review.baselineTick);
            assertEquals(1, s.review.removedMonitored);
            assertEquals(8, s.review.loadedChunks);
            assertEquals(5, s.delta.instIn, 0);
            assertEquals(-1, s.quantum.anchorDim);
            assertEquals(12, s.quantum.usedChannels);
            assertTrue(s.quantum.entries.isEmpty());
        } finally {
            bytes.release();
        }
    }

    @Test
    public void responseRejectsEveryTruncatedPrefixTrailingBytesNanAndExcessiveNodeCount() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            new PacketEngineeringSnapshot(snapshot(Collections.emptyList())).toBytes(bytes);
            for (int length = 0; length < bytes.readableBytes(); length++) {
                ByteBuf invalid = bytes.copy(0, length);
                try {
                    PacketEngineeringSnapshot decoded = new PacketEngineeringSnapshot();
                    decoded.fromBytes(invalid);
                    assertNull("prefix=" + length, decoded.snapshot);
                } finally {
                    invalid.release();
                }
            }
            bytes.writeByte(0);
            PacketEngineeringSnapshot decoded = new PacketEngineeringSnapshot();
            decoded.fromBytes(bytes);
            assertNull(decoded.snapshot);
            bytes.clear();
            new PacketEngineeringSnapshot(snapshot(Collections.emptyList())).toBytes(bytes);
            bytes.setDouble(35, Double.NaN);
            decoded.fromBytes(bytes);
            assertNull(decoded.snapshot);
            bytes.clear();
            List<Node> nodes = new ArrayList<>();
            for (int i = 0; i <= EngineeringSnapshot.MAX_NODES; i++)
                nodes.add(new Node(1, 0, i, 64, 0, "", "", "", true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
            new PacketEngineeringSnapshot(snapshot(nodes)).toBytes(bytes);
            decoded.fromBytes(bytes);
            assertNull(decoded.snapshot);
        } finally {
            bytes.release();
        }
    }
}
