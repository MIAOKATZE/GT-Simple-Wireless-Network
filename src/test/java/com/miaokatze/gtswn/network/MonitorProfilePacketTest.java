package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.UUID;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class MonitorProfilePacketTest {

    @Test
    public void actionRoundTripDoesNotCarryItemNbtOrSecrets() {
        UUID profile = UUID.randomUUID(), reader = UUID.randomUUID();
        C2SPacketMonitorProfileAction original = new C2SPacketMonitorProfileAction(
            31,
            3,
            2,
            profile,
            reader,
            true,
            200);
        ByteBuf bytes = Unpooled.buffer();
        try {
            original.toBytes(bytes);
            assertEquals(50, bytes.readableBytes());
            C2SPacketMonitorProfileAction decoded = new C2SPacketMonitorProfileAction();
            decoded.fromBytes(bytes);
            assertTrue(decoded.valid);
            assertEquals(31, decoded.requestId);
            assertEquals(profile, decoded.profile);
            assertEquals(reader, decoded.reader);
            assertEquals(200, decoded.interval);
        } finally {
            bytes.release();
        }
    }

    @Test
    public void rejectsMalformedActionsAndIntervals() {
        for (int length : new int[] { 0, 49, 51, 4096 }) {
            ByteBuf bytes = Unpooled.buffer()
                .writeZero(length);
            try {
                C2SPacketMonitorProfileAction packet = new C2SPacketMonitorProfileAction();
                packet.fromBytes(bytes);
                assertFalse(packet.valid);
            } finally {
                bytes.release();
            }
        }
        for (int interval : new int[] { 0, 19, 72001, Integer.MAX_VALUE }) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                new C2SPacketMonitorProfileAction(1, 5, 0, null, null, true, interval).toBytes(bytes);
                C2SPacketMonitorProfileAction decoded = new C2SPacketMonitorProfileAction();
                decoded.fromBytes(bytes);
                assertFalse(decoded.valid);
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    public void duplicateAndOutOfOrderMutationIdsNeverExecuteAgain() {
        C2SPacketMonitorProfileAction.RequestIds ids = new C2SPacketMonitorProfileAction.RequestIds();
        assertFalse(ids.accept(0));
        assertTrue(ids.accept(11));
        assertFalse(ids.accept(11));
        assertFalse(ids.accept(10));
        assertTrue(ids.accept(12));
        assertFalse(ids.accept(11));
    }

    @Test
    public void boundedProfileMetadataRoundTripAndTrailingGarbageRejected() {
        S2CPacketMonitorProfiles packet = new S2CPacketMonitorProfiles();
        packet.requestId = 9;
        packet.success = true;
        packet.total = 1;
        packet.message = "Ready";
        S2CPacketMonitorProfiles.Entry entry = new S2CPacketMonitorProfiles.Entry();
        entry.id = UUID.randomUUID();
        entry.owner = UUID.randomUUID();
        entry.interval = 100;
        entry.sources = 2;
        entry.readers = 3;
        entry.editable = true;
        packet.entries = Arrays.asList(entry);
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.toBytes(bytes);
            S2CPacketMonitorProfiles decoded = new S2CPacketMonitorProfiles();
            decoded.fromBytes(bytes.duplicate());
            assertTrue(decoded.valid);
            assertEquals(entry.id, decoded.entries.get(0).id);
            assertEquals(2, decoded.entries.get(0).sources);
            assertTrue(decoded.entries.get(0).editable);
            bytes.writeByte(0);
            decoded.fromBytes(bytes);
            assertFalse(decoded.valid);
        } finally {
            bytes.release();
        }
    }

    @Test
    public void overlargeSnapshotRejectedBeforeAllocating() {
        ByteBuf bytes = Unpooled.buffer()
            .writeZero(8193);
        try {
            S2CPacketMonitorProfiles packet = new S2CPacketMonitorProfiles();
            packet.fromBytes(bytes);
            assertFalse(packet.valid);
        } finally {
            bytes.release();
        }
    }

    @Test
    public void boundedNamesRoundTripAndInvalidRenamePayloadsFailClosed() {
        C2SPacketMonitorProfileAction packet = C2SPacketMonitorProfileAction
            .rename(19, 0, UUID.randomUUID(), "主工厂 power");
        ByteBuf bytes = Unpooled.buffer();
        try {
            packet.toBytes(bytes);
            C2SPacketMonitorProfileAction decoded = new C2SPacketMonitorProfileAction();
            decoded.fromBytes(bytes.duplicate());
            assertTrue(decoded.valid);
            assertEquals("主工厂 power", decoded.name);
            bytes.writeByte(0);
            decoded.fromBytes(bytes);
            assertFalse(decoded.valid);
        } finally {
            bytes.release();
        }
    }

    @Test
    public void budgetDenialRetainsOrderedOutcomeAndReplaysOriginalFailureWithoutMutation() {
        C2SPacketMonitorProfileAction.Results results = new C2SPacketMonitorProfileAction.Results();
        C2SPacketMonitorProfileAction.Delivery delivery = new C2SPacketMonitorProfileAction.Delivery();
        C2SPacketMonitorProfileAction request = new C2SPacketMonitorProfileAction(10, 1, 0, null, null, false, 20);
        final int[] calls = { 0 };
        delivery.response = results.apply(request, () -> {
            calls[0]++;
            throw new IllegalArgumentException("No native permission");
        });
        for (int flood = 0; flood < 100; flood++) {
            assertFalse(delivery.flush(outcome -> false));
            assertSame(delivery.response, results.apply(request, () -> calls[0]++));
        }
        assertEquals(1, calls[0]);
        assertFalse(delivery.response.success);
        assertEquals("No native permission", delivery.response.message);
        assertTrue(delivery.flush(outcome -> {
            assertFalse(outcome.success);
            return true;
        }));
        assertNull(delivery.response);
        assertFalse(results.apply(request, () -> calls[0]++).success);
        assertEquals(1, calls[0]);
    }

    @Test
    public void expiredReplayNeverReappliesOrInventsSuccess() {
        C2SPacketMonitorProfileAction.Results results = new C2SPacketMonitorProfileAction.Results();
        final int[] calls = { 0 };
        for (int id = 1; id <= 20; id++)
            results.apply(new C2SPacketMonitorProfileAction(id, 0, 0, null, null, false, 20), () -> calls[0]++);
        C2SPacketMonitorProfileAction.Outcome old = results
            .apply(new C2SPacketMonitorProfileAction(1, 1, 0, null, null, false, 20), () -> calls[0]++);
        assertEquals(20, calls[0]);
        assertFalse(old.success);
        assertTrue(old.message.contains("expired"));
    }
}
