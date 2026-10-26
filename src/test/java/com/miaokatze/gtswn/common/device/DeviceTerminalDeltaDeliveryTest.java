package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketRequestDeviceTerminalDelta;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalDelta;

public class DeviceTerminalDeltaDeliveryTest {

    @Test
    public void completeAckIsRequiredAndLiveSamplingDoesNotRestartFrozenPages() {
        UUID id = UUID.randomUUID(), session = UUID.randomUUID();
        DeviceTerminalDeltaDelivery delivery = new DeviceTerminalDeltaDelivery(id);
        delivery.prepare(request(id, session, 0, -1, -1), "source", 1, () -> rows(1024, 10));
        long epoch = delivery.epoch();
        for (int page = 0; page < 8; page++) {
            if (page > 0) delivery.prepare(
                request(id, session, epoch, -1, 1),
                "source",
                2 + page,
                () -> { throw new AssertionError("Must not recapture in-flight target"); });
            PacketSyncDeviceTerminalDelta next = delivery.next();
            assertEquals(1, next.getVersion());
            assertEquals(page, next.getPageIndex());
            delivery.sent(next, page * 5);
            assertEquals(-1, delivery.acknowledgedVersion());
        }
        delivery.prepare(request(id, session, epoch, 1, -1), "source", 20, () -> rows(1024, 99));
        assertEquals(1, delivery.acknowledgedVersion());
        assertEquals(20, delivery.targetVersion());
        assertEquals(
            PacketSyncDeviceTerminalDelta.DELTA,
            delivery.next()
                .getKind());
    }

    @Test
    public void fabricatedAckBeforeAllPagesSentCannotEstablishBase() {
        UUID id = UUID.randomUUID(), session = UUID.randomUUID();
        DeviceTerminalDeltaDelivery delivery = new DeviceTerminalDeltaDelivery(id);
        delivery.prepare(request(id, session, 0, -1, -1), "source", 1, () -> rows(1024, 10));
        long epoch = delivery.epoch();
        delivery.sent(delivery.next(), 0);
        delivery.prepare(request(id, session, epoch, 1, -1), "source", 2, () -> rows(1024, 99));
        assertTrue(delivery.epoch() > epoch);
        assertEquals(-1, delivery.acknowledgedVersion());
        assertEquals(
            PacketSyncDeviceTerminalDelta.FULL,
            delivery.next()
                .getKind());
    }

    @Test
    public void missingTailReplaysFrozenTargetAndLostCacheChangesEpoch() {
        UUID id = UUID.randomUUID(), session = UUID.randomUUID();
        DeviceTerminalDeltaDelivery delivery = new DeviceTerminalDeltaDelivery(id);
        delivery.prepare(request(id, session, 0, -1, -1), "source", 1, () -> rows(256, 10));
        long epoch = delivery.epoch();
        delivery.sent(delivery.next(), 0);
        delivery.sent(delivery.next(), 0);
        delivery.prepare(
            request(id, session, epoch, -1, 1),
            "source",
            99,
            () -> { throw new AssertionError("Missing tail must replay frozen target"); });
        assertEquals(0, delivery.nextPageIndex());
        assertEquals(
            1,
            delivery.next()
                .getVersion());
        delivery.prepare(request(id, session, 0, -1, -1), "source", 99, () -> rows(1, 99));
        assertTrue(delivery.epoch() > epoch);
        assertEquals(
            99,
            delivery.next()
                .getVersion());
    }

    @Test
    public void sourceAuthorizationRevisionSessionOrRollbackResetsDictionary() {
        UUID id = UUID.randomUUID(), session = UUID.randomUUID();
        DeviceTerminalDeltaDelivery delivery = new DeviceTerminalDeltaDelivery(id);
        delivery.prepare(request(id, session, 0, -1, -1), "source:acl1", 50, () -> rows(1, 10));
        delivery.sent(delivery.next(), 0);
        delivery.prepare(request(id, session, delivery.epoch(), 50, -1), "source:acl1", 50, () -> rows(1, 10));
        assertNotNull(delivery.keepAlive(100, 20));
        long epoch = delivery.epoch();
        delivery.prepare(request(id, session, epoch, 50, -1), "source:acl2", 51, () -> rows(1, 20));
        assertTrue(delivery.epoch() > epoch);
        assertEquals(
            PacketSyncDeviceTerminalDelta.FULL,
            delivery.next()
                .getKind());
        epoch = delivery.epoch();
        delivery.prepare(request(id, UUID.randomUUID(), epoch, 50, -1), "source:acl2", 2, () -> rows(1, 30));
        assertTrue(delivery.epoch() > epoch);
        assertEquals(
            2,
            delivery.next()
                .getVersion());
    }

    @Test
    public void budgetRejectionDoesNotAdvanceAndNoChangeKeepaliveDoesNotRecapture() {
        UUID id = UUID.randomUUID(), session = UUID.randomUUID();
        DeviceTerminalDeltaDelivery delivery = new DeviceTerminalDeltaDelivery(id);
        delivery.prepare(request(id, session, 0, -1, -1), "source", 1, () -> rows(128, 10));
        PacketSyncDeviceTerminalDelta pending = delivery.next();
        assertEquals(
            0,
            delivery.next()
                .getPageIndex());
        assertEquals(0, delivery.nextPageIndex());
        delivery.sent(pending, 0);
        delivery.prepare(
            request(id, session, delivery.epoch(), 1, -1),
            "source",
            1,
            () -> { throw new AssertionError("Stable ACK cannot recapture"); });
        assertFalse(delivery.hasPage());
        assertNull(delivery.keepAlive(19, 20));
        assertNotNull(delivery.keepAlive(20, 20));
    }

    private static PacketRequestDeviceTerminalDelta request(UUID id, UUID session, long epoch, long ack, long pending) {
        return new PacketRequestDeviceTerminalDelta(id, session, epoch, ack, pending);
    }

    public static List<Entry> rows(int count, long input) {
        List<Entry> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(
            new Entry(
                "0:" + i + ":64:0",
                "Machine " + i,
                (byte) 1,
                (byte) 0,
                input,
                0,
                input,
                0,
                0,
                i,
                64,
                0,
                "recipe input",
                "recipe output"));
        return rows;
    }
}
