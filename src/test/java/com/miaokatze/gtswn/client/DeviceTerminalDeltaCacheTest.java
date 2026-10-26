package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import java.util.List;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;

import com.miaokatze.gtswn.common.device.DeviceTerminalDeltaCodec;
import com.miaokatze.gtswn.common.device.DeviceTerminalDeltaDeliveryTest;
import com.miaokatze.gtswn.network.PacketRequestDeviceTerminalDelta;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalDelta;

public class DeviceTerminalDeltaCacheTest {

    @Before
    public void reset() {
        DeviceTerminalClientCache.clear();
    }

    @Test
    public void partialPagesNeverBecomeAckBaseAndOutOfOrderPagesCommitAtomically() {
        UUID id = UUID.randomUUID();
        UUID session = request(id).getSession();
        List<Entry> rows = rows(300, 10);
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, -1, 1, 0, null, rows));
        assertNull(DeviceTerminalClientCache.getSnapshot(id));
        assertEquals(-1, request(id).getKnownVersion());
        assertEquals(1, request(id).getPendingVersion());
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, -1, 1, 2, null, rows));
        assertNull(DeviceTerminalClientCache.getSnapshot(id));
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, -1, 1, 1, null, rows));
        assertEquals(300, DeviceTerminalClientCache.getSnapshot(id).entries.size());
        assertEquals(1, request(id).getKnownVersion());
        assertFalse(DeviceTerminalClientCache.hasMorePending(id));
    }

    @Test
    public void legacyDisplayPrefixCannotBecomeCompactDictionaryAndCacheIsFourTerminalBounded() {
        UUID id = UUID.randomUUID();
        DeviceTerminalClientCache.receivePage(id, 7, 0, 2, rows(128, 10));
        assertNotNull(DeviceTerminalClientCache.getSnapshot(id));
        assertEquals(-1, request(id).getKnownVersion());
        UUID session = request(id).getSession();
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, -1, 8, 0, null, rows(256, 20)));
        assertEquals(-1, request(id).getKnownVersion());
        assertNull(DeviceTerminalClientCache.getSnapshot(id));
        for (int i = 0; i < 4; i++) {
            UUID other = UUID.randomUUID();
            DeviceTerminalClientCache.receiveDelta(page(other, session, 2 + i, -1, 1, 0, null, rows(1, i)));
        }
        assertEquals(0, request(id).getEpoch());
        assertEquals(-1, request(id).getKnownVersion());
    }

    @Test
    public void deltaUsesCompleteRowsNotDisplayPrefixAndDuplicateOldPagesAreInert() {
        UUID id = UUID.randomUUID();
        UUID session = request(id).getSession();
        List<Entry> base = rows(256, 10), target = rows(256, 99);
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, -1, 1, 0, null, base));
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, -1, 1, 1, null, base));
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, 1, 2, 1, base, target));
        assertEquals(1, request(id).getKnownVersion());
        assertEquals(10, DeviceTerminalClientCache.getSnapshot(id).entries.get(0).instIn);
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, 1, 2, 0, base, target));
        assertEquals(2, request(id).getKnownVersion());
        assertEquals(99, DeviceTerminalClientCache.getSnapshot(id).entries.get(0).instIn);
        long receipt = DeviceTerminalClientCache.receiptRevision(id);
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, -1, 1, 0, null, base));
        assertEquals(receipt, DeviceTerminalClientCache.receiptRevision(id));
        assertEquals(2, request(id).getKnownVersion());
    }

    @Test
    public void mismatchRequestsResetAndReconnectRejectsOldSession() {
        UUID id = UUID.randomUUID();
        UUID session = request(id).getSession();
        List<Entry> base = rows(1, 10), target = rows(1, 20);
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, -1, 1, 0, null, base));
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, 9, 10, 0, base, target));
        assertEquals(-1, request(id).getKnownVersion());
        assertFalse(DeviceTerminalClientCache.isFresh(id, System.currentTimeMillis()));
        DeviceTerminalClientCache.clear();
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, -1, 1, 0, null, base));
        assertNull(DeviceTerminalClientCache.getSnapshot(id));
    }

    @Test
    public void revocationPurgesSensitiveRowsAndLateEpochCannotRestoreThem() {
        UUID id = UUID.randomUUID();
        UUID session = request(id).getSession();
        List<Entry> base = rows(1, 10);
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, -1, 1, 0, null, base));
        DeviceTerminalClientCache.receiveDelta(
            new PacketSyncDeviceTerminalDelta(
                id,
                session,
                2,
                -1,
                0,
                PacketSyncDeviceTerminalDelta.REVOKED,
                0,
                1,
                0,
                new byte[0]));
        assertNull(DeviceTerminalClientCache.getSnapshot(id));
        DeviceTerminalClientCache.receiveDelta(page(id, session, 1, -1, 1, 0, null, base));
        assertNull(DeviceTerminalClientCache.getSnapshot(id));
        DeviceTerminalClientCache.receiveDelta(page(id, session, 3, -1, 0, 0, null, rows(0, 0)));
        assertNotNull(DeviceTerminalClientCache.getSnapshot(id));
        assertEquals(0, request(id).getKnownVersion());
    }

    @Test
    public void malformedPayloadAndDuplicateRowsAcrossPagesCannotCommit() {
        UUID id = UUID.randomUUID();
        UUID session = request(id).getSession();
        List<Entry> rows = rows(256, 10);
        PacketSyncDeviceTerminalDelta first = page(id, session, 1, -1, 1, 0, null, rows);
        DeviceTerminalClientCache.receiveDelta(first);
        DeviceTerminalClientCache.receiveDelta(
            new PacketSyncDeviceTerminalDelta(
                id,
                session,
                1,
                -1,
                1,
                PacketSyncDeviceTerminalDelta.FULL,
                1,
                2,
                256,
                first.getPayload()));
        assertEquals(-1, request(id).getKnownVersion());
        assertNull(DeviceTerminalClientCache.getSnapshot(id));
        DeviceTerminalClientCache.receiveDelta(
            new PacketSyncDeviceTerminalDelta(
                id,
                session,
                2,
                -1,
                2,
                PacketSyncDeviceTerminalDelta.FULL,
                0,
                1,
                0,
                new byte[] { 1 }));
        assertEquals(-1, request(id).getKnownVersion());
    }

    private static PacketRequestDeviceTerminalDelta request(UUID id) {
        return DeviceTerminalClientCache.compactRequestMetadata(id);
    }

    private static List<Entry> rows(int count, long input) {
        return DeviceTerminalDeltaDeliveryTest.rows(count, input);
    }

    private static PacketSyncDeviceTerminalDelta page(UUID id, UUID session, long epoch, long baseVersion, long version,
        int page, List<Entry> base, List<Entry> target) {
        int from = page * 128;
        DeviceTerminalDeltaCodec.Encoded encoded = DeviceTerminalDeltaCodec
            .encode(base, target.subList(from, Math.min(target.size(), from + 128)));
        return new PacketSyncDeviceTerminalDelta(
            id,
            session,
            epoch,
            baseVersion,
            version,
            encoded.delta ? PacketSyncDeviceTerminalDelta.DELTA : PacketSyncDeviceTerminalDelta.FULL,
            page,
            Math.max(1, (target.size() + 127) / 128),
            target.size(),
            encoded.bytes);
    }
}
