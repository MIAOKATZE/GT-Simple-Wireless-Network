// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketDeviceTerminalPaymentSettingsTest {

    @Test
    public void reserveCountRoundTripsAndPreviousNineByteSettingsRemainReadable() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            new PacketSyncDeviceTerminalSettings(3, true, 10, 640).toBytes(bytes);
            PacketSyncDeviceTerminalSettings current = new PacketSyncDeviceTerminalSettings();
            current.fromBytes(bytes);
            assertEquals(640, current.teleportAnchors);
            bytes.clear();
            bytes.writeInt(3)
                .writeBoolean(true)
                .writeInt(10);
            PacketSyncDeviceTerminalSettings legacy = new PacketSyncDeviceTerminalSettings();
            legacy.fromBytes(bytes);
            assertEquals(3, legacy.teleportCost);
            assertTrue(legacy.canEdit);
            assertEquals(10, legacy.samplingSeconds);
            assertEquals(0, legacy.teleportAnchors);
        } finally {
            bytes.release();
        }
    }
}
