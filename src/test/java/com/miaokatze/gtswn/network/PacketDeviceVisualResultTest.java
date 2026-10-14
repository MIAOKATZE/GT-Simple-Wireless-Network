package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketDeviceVisualResultTest {

    private static final UUID ID = UUID.fromString("12345678-1234-5678-1234-567812345678");

    @Test
    public void ordinaryAckKeepsSeventeenBytesAndIsNotAResult() {
        ByteBuf b = Unpooled.buffer();
        try {
            new PacketSyncDeviceTerminalVisualState(ID, true).toBytes(b);
            assertEquals(17, b.readableBytes());
            PacketSyncDeviceTerminalVisualState p = new PacketSyncDeviceTerminalVisualState();
            p.fromBytes(b);
            assertEquals(ID, p.terminalId);
            assertTrue(p.enabled);
            assertFalse(p.hasResult);
        } finally {
            b.release();
        }
    }

    @Test
    public void authoritativeSuccessAndFailureCarryTargetCooldownAndPosition() {
        for (byte result : new byte[] { 0, 3, 5, 7, 8, 12 }) {
            ByteBuf b = Unpooled.buffer();
            try {
                new PacketSyncDeviceTerminalVisualState(ID, result, "0:1:64:2", result == 0 ? 60 : 5, 1.5, 65, 2.5)
                    .toBytes(b);
                PacketSyncDeviceTerminalVisualState p = new PacketSyncDeviceTerminalVisualState();
                p.fromBytes(b);
                assertEquals(ID, p.terminalId);
                assertTrue(p.hasResult);
                assertEquals(result, p.result);
                assertEquals("0:1:64:2", p.targetKey);
                assertEquals(result == 0 ? 60 : 5, p.cooldownTicks);
                assertEquals(1.5, p.x, 0.0);
                assertEquals(65, p.y, 0.0);
            } finally {
                b.release();
            }
        }
    }

    @Test
    public void hugeDeclaredKeyAndNonFiniteOutcomeAreRejectedBeforeClientStateChanges() {
        ByteBuf b = Unpooled.buffer();
        try {
            b.writeLong(ID.getMostSignificantBits())
                .writeLong(ID.getLeastSignificantBits())
                .writeByte(0)
                .writeByte(1)
                .writeByte(0)
                .writeByte(255)
                .writeByte(127)
                .writeZero(28);
            PacketSyncDeviceTerminalVisualState p = new PacketSyncDeviceTerminalVisualState();
            p.fromBytes(b);
            assertNull(p.terminalId);
            b.clear();
            new PacketSyncDeviceTerminalVisualState(ID, (byte) 0, "0:1:64:2", 60, Double.NaN, 0, 0).toBytes(b);
            p.fromBytes(b);
            assertNull(p.terminalId);
            assertFalse(p.hasResult);
        } finally {
            b.release();
        }
    }
}
