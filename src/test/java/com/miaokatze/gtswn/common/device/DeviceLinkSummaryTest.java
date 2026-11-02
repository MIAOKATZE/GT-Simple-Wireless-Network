package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class DeviceLinkSummaryTest {

    @Test
    public void summaryAndMachineIdentitySurviveLegacyAndDeltaWirePaths() {
        DeviceLinkSummary expected = new DeviceLinkSummary(DeviceLinkSummary.KNOWN, 6, 9, 2, 3);
        UUID owner = UUID.randomUUID();
        Entry row = row(expected, owner);
        PacketSyncDeviceTerminalData packet = new PacketSyncDeviceTerminalData(
            UUID.randomUUID(),
            1,
            0,
            1,
            1,
            Collections.singletonList(row));
        ByteBuf buffer = Unpooled.buffer();
        packet.toBytes(buffer);
        PacketSyncDeviceTerminalData decoded = new PacketSyncDeviceTerminalData();
        decoded.fromBytes(buffer);
        Entry legacy = decoded.getEntries()
            .get(0);
        assertEquals(expected, legacy.linkSummary);
        assertEquals(owner, legacy.machineOwner);
        assertEquals(3, legacy.machineKind);
        assertEquals(4, legacy.machineTier);
        Entry base = row(DeviceLinkSummary.UNVERIFIED, null);
        DeviceTerminalDeltaCodec.Encoded encoded = DeviceTerminalDeltaCodec
            .encode(Collections.singletonList(base), Collections.singletonList(row));
        Entry compact = DeviceTerminalDeltaCodec.decode(Collections.singletonList(base), encoded.delta, encoded.bytes)
            .get(0);
        assertEquals(expected, compact.linkSummary);
        assertEquals(owner, compact.machineOwner);
        encoded = DeviceTerminalDeltaCodec.encode(Collections.singletonList(row), Collections.singletonList(base));
        assertEquals(
            DeviceLinkSummary.UNVERIFIED,
            DeviceTerminalDeltaCodec.decode(Collections.singletonList(row), encoded.delta, encoded.bytes)
                .get(0).linkSummary);
    }

    @Test
    public void oldConstructorsAndNewRecordsAreUnverifiedNotZeroConnected() {
        Entry row = new Entry("0:1:64:0", "machine", (byte) 0, (byte) 0, 0, 0, 0, 0, 0, 1, 64, 0, "", "");
        assertEquals(DeviceLinkSummary.UNVERIFIED, row.linkSummary);
        assertEquals(DeviceLinkSummary.UNVERIFIED, new DeviceTerminalDataStore.MachineRecord().linkSummary);
        assertEquals(12, row(new DeviceLinkSummary(DeviceLinkSummary.KNOWN, 6, 9, 2, 3), null).linkSummary.total());
    }

    @Test
    public void malformedCountsAreRejectedInsteadOfShrinkingTheDenominator() {
        for (int[] values : new int[][] { { -1, 1, 0, 0 }, { 2, 1, 0, 0 }, { 0, 0, 2, 1 },
            { 0, Integer.MAX_VALUE, 0, 1 } }) {
            try {
                new DeviceLinkSummary(DeviceLinkSummary.KNOWN, values[0], values[1], values[2], values[3]);
                fail();
            } catch (IllegalArgumentException expected) {}
        }
        try {
            new DeviceLinkSummary(DeviceLinkSummary.UNKNOWN, 0, 1, 0, 0);
            fail();
        } catch (IllegalArgumentException expected) {}
    }

    private static Entry row(DeviceLinkSummary summary, UUID owner) {
        return new Entry(
            "0:1:64:0",
            "machine",
            (byte) 0,
            (byte) 0,
            0,
            0,
            0,
            0,
            0,
            1,
            64,
            0,
            "",
            "",
            1,
            (byte) 0,
            (byte) 3,
            "",
            (byte) 15,
            false,
            (byte) 1,
            (byte) 0,
            summary,
            owner,
            (byte) 3,
            (byte) 4);
    }
}
