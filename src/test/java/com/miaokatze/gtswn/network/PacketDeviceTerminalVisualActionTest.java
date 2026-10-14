package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketDeviceTerminalVisualActionTest {

    private static final UUID ID = UUID.fromString("12345678-1234-5678-1234-567812345678");

    private PacketDeviceTerminalVisualAction roundTrip(PacketDeviceTerminalVisualAction original) {
        ByteBuf buf = Unpooled.buffer();
        try {
            original.toBytes(buf);
            PacketDeviceTerminalVisualAction result = new PacketDeviceTerminalVisualAction();
            result.fromBytes(buf);
            return result;
        } finally {
            buf.release();
        }
    }

    @Test
    public void roundTripKeepsExactTargetAndSafeStyleAndAllowsOnlyNewToggleZeroId() {
        PacketDeviceTerminalVisualAction rename = roundTrip(
            PacketDeviceTerminalVisualAction.rename(ID, "0:1:2:3", "\u673a\u5668", 4, true));
        assertEquals(ID, rename.terminalId);
        assertEquals((byte) 2, rename.action);
        assertEquals("0:1:2:3", rename.key);
        assertEquals("\u673a\u5668", rename.name);
        assertEquals(4, rename.color);
        assertTrue(rename.bold);
        assertTrue(roundTrip(PacketDeviceTerminalVisualAction.toggle(null)).valid());
        assertFalse(
            roundTrip(
                PacketDeviceTerminalVisualAction.teleport(PacketDeviceTerminalVisualAction.NEW_TERMINAL, "0:1:2:3"))
                    .valid());
        assertFalse(roundTrip(PacketDeviceTerminalVisualAction.rename(ID, "0:1:2:3", "bad\n", 1, false)).valid());
    }

    @Test
    public void forgedDeclaredStringLengthIsRejectedBeforeAllocation() {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeLong(ID.getMostSignificantBits())
                .writeLong(ID.getLeastSignificantBits())
                .writeByte(2);
            buf.writeByte(255)
                .writeByte(127)
                .writeByte(0)
                .writeByte(15)
                .writeByte(0);
            PacketDeviceTerminalVisualAction packet = new PacketDeviceTerminalVisualAction();
            packet.fromBytes(buf);
            assertNull(packet.terminalId);
            assertEquals((byte) -1, packet.action);
        } finally {
            buf.release();
        }
    }

    @Test
    public void malformedUtf8AndNonBooleanStyleCannotBecomeRequests() {
        for (boolean malformed : new boolean[] { true, false }) {
            ByteBuf buf = Unpooled.buffer();
            try {
                buf.writeLong(ID.getMostSignificantBits())
                    .writeLong(ID.getLeastSignificantBits())
                    .writeByte(2);
                ByteBufUtils.writeUTF8String(buf, "0:1:2:3");
                if (malformed) buf.writeByte(1)
                    .writeByte(255);
                else ByteBufUtils.writeUTF8String(buf, "ok");
                buf.writeByte(15)
                    .writeByte(malformed ? 0 : 2);
                PacketDeviceTerminalVisualAction packet = new PacketDeviceTerminalVisualAction();
                packet.fromBytes(buf);
                assertFalse(packet.valid());
            } finally {
                buf.release();
            }
        }
    }
}
