package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

public class DeviceTerminalDeliverySnapshotTest {

    @Test
    public void alternatingTerminalsRetainTheirOwnContinuationAndOnePlayerThrottle() {
        DeviceTerminalRequestQueue.PlayerState player = new DeviceTerminalRequestQueue.PlayerState();
        java.util.UUID a = java.util.UUID.randomUUID(), b = java.util.UUID.randomUUID();
        DeviceTerminalRequestQueue.SentState first = player.terminal(a);
        first.prepareSnapshot(terminal(1024), -1, false, true);
        first.pagesSent = 2;
        DeviceTerminalRequestQueue.SentState second = player.terminal(b);
        second.prepareSnapshot(terminal(512), -1, false, true);
        second.pagesSent = 4;
        assertSame(first, player.terminal(a));
        assertEquals(2, player.terminal(a).pagesSent);
        assertEquals(4, player.terminal(b).pagesSent);
        assertTrue(player.admit(20));
        assertFalse(player.admit(20));
        assertFalse(player.admit(24));
        assertTrue(player.admit(25));
        player.terminal(java.util.UUID.randomUUID());
        player.terminal(java.util.UUID.randomUUID());
        player.terminal(a); // recently read A must survive a fifth source.
        player.terminal(java.util.UUID.randomUUID());
        assertEquals(4, player.terminals.size());
        assertSame(first, player.terminal(a));
        assertFalse(player.terminals.containsKey(b));
    }

    @Test
    public void continuousHundredMachineSamplesCannotRestartEightPageContinuation() {
        DeviceTerminalDataStore.TerminalData data = terminal(1024);
        DeviceTerminalRequestQueue.SentState state = new DeviceTerminalRequestQueue.SentState();
        state.prepareSnapshot(data, -1, false, true);
        List<Entry> delivery = state.snapshot;
        long deliveredVersion = state.version;
        int sampleCursor = 0;
        for (int tick = 0; tick <= 15; tick++) {
            if (tick % 5 == 0) {
                state.prepareSnapshot(data, deliveredVersion, false, true);
                assertSame(delivery, state.snapshot);
                assertEquals(deliveredVersion, state.version);
                assertEquals(tick / 5 * 2, state.pagesSent);
                // The real sender advances two 128-entry pages per admitted request.
                for (int row = state.pagesSent * 128; row < (state.pagesSent + 2) * 128; row++) {
                    assertEquals("0:" + row + ":64:0", state.snapshot.get(row).key);
                    assertEquals(row, state.snapshot.get(row).instIn);
                }
                state.pagesSent += 2;
                state.complete = state.pagesSent == state.pageTotal;
            }
            for (int sample = 0; sample < 100; sample++) {
                int row = sampleCursor++ % 1024;
                data.records.get("0:" + row + ":64:0").instIn += 10000;
                data.version++;
            }
        }
        assertTrue(state.complete);
        assertEquals(8, state.pagesSent);
        assertEquals(1023, state.snapshot.get(1023).instIn);
        state.prepareSnapshot(data, deliveredVersion, true, true);
        assertNotSame(delivery, state.snapshot);
        assertEquals(data.version, state.version);
        assertEquals(0, state.pagesSent);
        assertTrue(state.snapshot.get(0).instIn > 0);
    }

    @Test
    public void lostCacheAndMissingTailReplaySameGenerationUntilClientConfirmsComplete() {
        DeviceTerminalDataStore.TerminalData data = terminal(1024);
        DeviceTerminalRequestQueue.SentState state = new DeviceTerminalRequestQueue.SentState();
        state.prepareSnapshot(data, -1, false, true);
        List<Entry> original = state.snapshot;
        state.pagesSent = 6;
        data.version = 9999;
        data.records.get("0:1023:64:0").instIn = 7777;
        state.prepareSnapshot(data, -1, false, true);
        assertSame(original, state.snapshot);
        assertEquals(0, state.pagesSent);
        assertEquals(1023, state.snapshot.get(1023).instIn);
        state.pagesSent = 8;
        state.complete = true;
        state.prepareSnapshot(data, state.version, false, true);
        assertSame(original, state.snapshot);
        assertFalse(state.complete);
        assertEquals(0, state.pagesSent);
        state.pagesSent = 8;
        state.complete = true;
        state.prepareSnapshot(data, state.version, true, true);
        assertEquals(9999, state.version);
        assertEquals(7777, state.snapshot.get(1023).instIn);
    }

    @Test
    public void snapshotIsBoundedImmutableAndStableCompletePollDoesNotCopyAgain() {
        DeviceTerminalDataStore.TerminalData data = terminal(1100);
        DeviceTerminalRequestQueue.SentState state = new DeviceTerminalRequestQueue.SentState();
        state.prepareSnapshot(data, -1, false, true);
        assertEquals(1024, state.entryTotal);
        assertEquals(1024, state.snapshot.size());
        assertEquals(8, state.pageTotal);
        try {
            state.snapshot.clear();
            fail("Delivery must be immutable");
        } catch (UnsupportedOperationException expected) {}
        List<Entry> original = state.snapshot;
        state.complete = true;
        state.pagesSent = 8;
        state.prepareSnapshot(data, state.version, true, true);
        assertSame(original, state.snapshot);
        assertTrue(state.complete);
    }

    private static DeviceTerminalDataStore.TerminalData terminal(int machines) {
        DeviceTerminalDataStore.TerminalData data = new DeviceTerminalDataStore.TerminalData();
        for (int row = 0; row < machines; row++) {
            String key = "0:" + row + ":64:0";
            DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
            record.name = "machine " + row;
            record.instIn = row;
            record.x = row;
            record.y = 64;
            data.boundKeys.add(key);
            data.records.put(key, record);
        }
        return data;
    }
}
