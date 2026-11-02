package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.common.device.DeviceTerminalTeleport.PaymentSource;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketDeviceTeleportProtocolTest {

    private static final UUID ID = UUID.fromString("12345678-1234-5678-1234-567812345678");

    @Test
    public void quoteKeepsExactSourceAmountTargetAndOneTimeToken() {
        for (PaymentSource source : PaymentSource.values()) {
            int amount = source == PaymentSource.FREE ? 0 : source == PaymentSource.EXPERIENCE ? 19 : 1;
            PacketSyncDeviceTeleportQuote quote = roundTrip(
                new PacketSyncDeviceTeleportQuote(123, ID, "-1:123:64:-456", source, amount, true));
            assertTrue(quote.valid());
            assertEquals(123, quote.token);
            assertEquals(ID, quote.terminalId);
            assertEquals("-1:123:64:-456", quote.targetKey);
            assertEquals(source, quote.source);
            assertEquals(amount, quote.amount);
            assertTrue(quote.changed);
            assertFalse(quote.finished);
            assertTrue(roundTrip(PacketSyncDeviceTeleportQuote.finished(123, ID, "0:1:2:3", source, amount)).finished);
        }
    }

    @Test
    public void malformedQuotesAndUnsupportedAmountsCannotBecomeConfirmations() {
        for (PaymentSource source : PaymentSource.values()) {
            assertFalse(roundTrip(new PacketSyncDeviceTeleportQuote(1, ID, "0:1:2:3", source, -1, false)).valid());
            assertFalse(roundTrip(new PacketSyncDeviceTeleportQuote(0, ID, "0:1:2:3", source, 1, false)).valid());
        }
        ByteBuf buffer = Unpooled.buffer();
        try {
            new PacketSyncDeviceTeleportQuote(1, ID, "0:1:2:3", PaymentSource.INVENTORY, 1, false).toBytes(buffer);
            buffer.setByte(buffer.writerIndex() - 1, 8);
            PacketSyncDeviceTeleportQuote invalid = new PacketSyncDeviceTeleportQuote();
            invalid.fromBytes(buffer);
            assertFalse(invalid.valid());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void carriedAndAccessorySourcesRoundTripWithoutBindingAnUnrelatedHotbar() {
        for (int slot : new int[] { 0, 8, 12, 35, -2, -9, -27 })
            assertTrue(roundTrip(PacketDeviceTeleportRequest.openSettings(ID, slot)).valid());
        assertFalse(roundTrip(PacketDeviceTeleportRequest.openSettings(ID, -1)).valid());
        assertFalse(roundTrip(PacketDeviceTeleportRequest.openSettings(ID, 36)).valid());
        assertTrue(roundTrip(PacketDeviceTeleportRequest.confirm(123, true)).valid());
        assertTrue(roundTrip(PacketDeviceTeleportRequest.confirm(123, false)).valid());
        assertFalse(roundTrip(PacketDeviceTeleportRequest.confirm(0, true)).valid());
    }

    @Test
    public void truncatedPaddedOrUnknownRequestsAreRejected() {
        for (int length = 0; length < 22; length++) {
            if (length == 9) continue;
            ByteBuf buffer = Unpooled.buffer();
            try {
                PacketDeviceTeleportRequest.confirm(7, true)
                    .toBytes(buffer);
                if (length < 9) buffer.writerIndex(length);
                else buffer.writeZero(length - 9);
                PacketDeviceTeleportRequest invalid = new PacketDeviceTeleportRequest();
                invalid.fromBytes(buffer);
                assertFalse("length=" + length, invalid.valid());
            } finally {
                buffer.release();
            }
        }
    }

    private static PacketSyncDeviceTeleportQuote roundTrip(PacketSyncDeviceTeleportQuote original) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            original.toBytes(buffer);
            PacketSyncDeviceTeleportQuote result = new PacketSyncDeviceTeleportQuote();
            result.fromBytes(buffer);
            return result;
        } finally {
            buffer.release();
        }
    }

    private static PacketDeviceTeleportRequest roundTrip(PacketDeviceTeleportRequest original) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            original.toBytes(buffer);
            PacketDeviceTeleportRequest result = new PacketDeviceTeleportRequest();
            result.fromBytes(buffer);
            return result;
        } finally {
            buffer.release();
        }
    }
}
