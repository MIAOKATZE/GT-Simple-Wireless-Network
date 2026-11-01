package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketDeviceLinkActionTest {

    @Test
    public void explicitDesiredStateAndExactIdentityRoundTrip() {
        for (boolean desired : new boolean[] { false, true }) {
            PacketDeviceLinkAction original = new PacketDeviceLinkAction(
                UUID.randomUUID(),
                "-1:12:64:3",
                123,
                7,
                desired);
            ByteBuf buffer = Unpooled.buffer();
            original.toBytes(buffer);
            PacketDeviceLinkAction decoded = new PacketDeviceLinkAction();
            decoded.fromBytes(buffer);
            assertTrue(decoded.valid);
            assertEquals(desired, decoded.desiredState);
            assertEquals(original.terminalId, decoded.terminalId);
            assertEquals(original.key, decoded.key);
            assertEquals(123, decoded.meta);
            assertEquals(7, decoded.requestId);
        }
    }

    @Test
    public void truncatedAndNonBooleanPayloadsNeverBecomeActions() {
        PacketDeviceLinkAction request = new PacketDeviceLinkAction(UUID.randomUUID(), "0:1:64:3", 1, 7, true);
        ByteBuf full = Unpooled.buffer();
        request.toBytes(full);
        for (int length = 0; length < full.readableBytes(); length++) {
            PacketDeviceLinkAction decoded = new PacketDeviceLinkAction();
            decoded.fromBytes(full.copy(0, length));
            assertFalse(decoded.valid);
        }
        full.setByte(full.writerIndex() - 1, 2);
        PacketDeviceLinkAction decoded = new PacketDeviceLinkAction();
        decoded.fromBytes(full);
        assertFalse(decoded.valid);
    }

    @Test
    public void autoSettingsRejectUnexpectedActionsOrTrailingBytes() {
        for (byte[] bytes : new byte[][] { {}, { 0 }, { 2, 0 }, { 0, 2 }, { 1, 1, 0 } }) {
            PacketTerminalLinkSettings decoded = new PacketTerminalLinkSettings();
            decoded.fromBytes(Unpooled.wrappedBuffer(bytes));
            assertFalse(decoded.valid);
        }
        PacketTerminalLinkSettings decoded = new PacketTerminalLinkSettings();
        decoded.fromBytes(Unpooled.wrappedBuffer(new byte[] { 1, 1 }));
        assertTrue(decoded.valid);
        assertTrue(decoded.update);
        assertTrue(decoded.enabled);
    }
}
