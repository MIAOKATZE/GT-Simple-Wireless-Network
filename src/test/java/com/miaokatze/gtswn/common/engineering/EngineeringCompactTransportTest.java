package com.miaokatze.gtswn.common.engineering;

import static org.junit.Assert.*;

import java.util.Collections;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.EngineeringTelemetryConsumers;
import com.miaokatze.gtswn.network.EngineeringTypedSnapshot;
import com.miaokatze.gtswn.network.PacketEngineeringCompact;
import com.miaokatze.gtswn.network.PacketEngineeringControl;
import com.miaokatze.gtswn.network.PacketEngineeringSnapshot;
import com.miaokatze.gtswn.network.telemetry.ByteDelta;
import com.miaokatze.gtswn.network.telemetry.TelemetryTransport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class EngineeringCompactTransportTest {

    private static EngineeringSnapshot snapshot(int kind) {
        EngineeringSnapshot.Node node = new EngineeringSnapshot.Node(
            kind,
            -1,
            4,
            64,
            3,
            "owner",
            "Alice",
            "North",
            true,
            kind == 1 ? 2 : 0,
            kind == 1 ? 32 : 0,
            kind == 1 ? 4 : 0,
            kind == 1 ? 900 : 0,
            kind == 1 ? 1000 : 0,
            kind == 1 ? 0 : 2,
            kind == 1 ? 0 : 10,
            kind == 1 ? 0 : 64,
            kind == 1 ? 0 : 20,
            kind == 1 ? 0 : 7);
        return new EngineeringSnapshot(
            9,
            1,
            24,
            2,
            "ok",
            Collections.singletonList(node),
            Collections.emptyList(),
            EngineeringSnapshot.Power.ZERO,
            EngineeringSnapshot.Power.ZERO,
            0,
            0,
            0,
            false,
            false,
            false,
            null);
    }

    @Test
    public void linkAndQuantumRowsOmitOnlyTheirMeaninglessFields() {
        for (int kind = 1; kind <= 3; kind++) {
            EngineeringSnapshot original = snapshot(kind);
            ByteBuf wire = Unpooled.buffer();
            try {
                EngineeringTypedSnapshot packet = new EngineeringTypedSnapshot(original);
                packet.toBytes(wire);
                assertEquals(
                    kind == 1 ? 20 : 33,
                    TelemetryTransport.encodedBytes(new PacketEngineeringSnapshot(original)) - wire.readableBytes());
                EngineeringTypedSnapshot decoded = new EngineeringTypedSnapshot();
                decoded.fromBytes(wire);
                assertNotNull(decoded.snapshot);
                EngineeringSnapshot.Node node = decoded.snapshot.nodes.get(0);
                assertEquals(original.nodes.get(0).voltage, node.voltage);
                assertEquals(original.nodes.get(0).anchorX, node.anchorX);
                assertEquals("Alice", node.ownerName);
                assertEquals(0, decoded.snapshot.serverTick);
            } finally {
                wire.release();
            }
        }
    }

    @Test
    public void unchangedSnapshotHasFortyOneByteFreshnessHeartbeat() {
        byte[] bytes = new byte[100];
        PacketEngineeringCompact packet = new PacketEngineeringCompact(9, 1, 4, 4, 60, ByteDelta.between(bytes, bytes));
        assertEquals(41, TelemetryTransport.encodedBytes(packet));
        ByteBuf wire = Unpooled.buffer();
        try {
            packet.toBytes(wire);
            PacketEngineeringCompact decoded = new PacketEngineeringCompact();
            decoded.fromBytes(wire);
            assertTrue(decoded.valid);
            assertEquals(4, decoded.base);
            assertEquals(60, decoded.serverTick);
        } finally {
            wire.release();
        }
    }

    @Test
    public void invalidBaselineNeverBecomesValidDelta() {
        ByteBuf wire = Unpooled.buffer();
        try {
            new PacketEngineeringCompact(9, 1, 0, 4, 60, ByteDelta.between(new byte[100], new byte[100])).toBytes(wire);
            PacketEngineeringCompact decoded = new PacketEngineeringCompact();
            decoded.fromBytes(wire);
            assertFalse(decoded.valid);
        } finally {
            wire.release();
        }
    }

    @Test
    public void quantumAckVariantRoundTripsWithoutEngineeringView() {
        ByteBuf wire = Unpooled.buffer();
        try {
            PacketEngineeringControl.quantumAck(-1, -4, 5, 88)
                .toBytes(wire);
            PacketEngineeringControl decoded = new PacketEngineeringControl();
            decoded.fromBytes(wire);
            assertTrue(decoded.valid);
            assertTrue(decoded.quantum);
            assertEquals(88, decoded.acknowledged);
            assertEquals(-4, decoded.chunkX);
        } finally {
            wire.release();
        }
    }

    @Test
    public void anyActualConsumerKeepsSubscriptionAlive() {
        assertFalse(EngineeringTelemetryConsumers.visible(false, false, false));
        assertTrue(EngineeringTelemetryConsumers.visible(false, true, false));
        assertTrue(EngineeringTelemetryConsumers.visible(false, false, true));
        assertTrue(EngineeringTelemetryConsumers.visible(true, false, false));
    }
}
