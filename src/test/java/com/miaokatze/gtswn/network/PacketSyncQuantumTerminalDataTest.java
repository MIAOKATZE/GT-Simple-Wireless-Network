package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketSyncQuantumTerminalDataTest {

    @Test
    public void responseCapabilitiesDoNotMutateSharedAnchorSnapshot() {
        QuantumNetworkData shared = new QuantumNetworkData();
        shared.online = true;
        for (boolean allowed : new boolean[] { true, false }) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                new PacketSyncQuantumTerminalDataLite(shared, false, allowed, allowed ? 14L : 0L).toBytes(buffer);
                PacketSyncQuantumTerminalDataLite decoded = new PacketSyncQuantumTerminalDataLite();
                decoded.fromBytes(buffer);
                assertEquals(allowed, decoded.getData().canChangeNetworkSafety);
                assertFalse(decoded.getData().globalSafetyEnabled);
                assertFalse(shared.canChangeNetworkSafety);
                assertTrue(shared.globalSafetyEnabled);
                assertEquals(0L, shared.networkSafetyRequestToken);
            } finally {
                buffer.release();
            }
        }
    }

    private static QuantumNetworkData sample() {
        QuantumNetworkData data = new QuantumNetworkData();
        data.online = true;
        data.anchorDim = -7;
        data.anchorX = -34;
        data.anchorY = 64;
        data.anchorZ = 91;
        data.totalChannels = 192;
        data.usedChannels = 13;
        data.quantumNodeCount = 4;
        data.incorporationCount = 3;
        data.incorporationChannels = 2;
        data.globalSafetyEnabled = false;
        data.networkSafetyEnabled = false;
        data.canChangeNetworkSafety = true;
        data.networkSafetyIdentity = "controller-generation";
        data.networkSafetyRequestToken = 572L;
        return data;
    }

    private static void assertSnapshot(QuantumNetworkData decoded, int count, int channels) {
        assertEquals(-7, decoded.anchorDim);
        assertEquals(-34, decoded.anchorX);
        assertEquals(13, decoded.usedChannels);
        assertEquals(192, decoded.totalChannels);
        assertEquals(4, decoded.quantumNodeCount);
        assertEquals(count, decoded.incorporationCount);
        assertEquals(channels, decoded.incorporationChannels);
    }

    @Test
    public void fullPacketRoundTripAndOldPayloadFallback() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new PacketSyncQuantumTerminalData(sample()).toBytes(buffer);
            int bytes = buffer.readableBytes();
            PacketSyncQuantumTerminalData decoded = new PacketSyncQuantumTerminalData();
            decoded.fromBytes(buffer);
            assertSnapshot(decoded.getData(), 3, 2);
            assertEquals(0, buffer.readableBytes());
            assertFalse(decoded.getData().globalSafetyEnabled);
            assertFalse(decoded.getData().networkSafetyEnabled);
            assertTrue(decoded.getData().canChangeNetworkSafety);
            assertEquals("controller-generation", decoded.getData().networkSafetyIdentity);
            assertEquals(572L, decoded.getData().networkSafetyRequestToken);
            buffer.setIndex(0, bytes - 3 - 1 - "controller-generation".length() - 8 - 8 - 1);
            decoded.fromBytes(buffer);
            assertSnapshot(decoded.getData(), 0, 0);
            assertTrue(decoded.getData().globalSafetyEnabled);
            assertFalse(decoded.getData().networkSafetyEnabled);
            assertFalse(decoded.getData().canChangeNetworkSafety);
            assertFalse(decoded.getData().managementOnly);
            assertEquals("", decoded.getData().networkSafetyIdentity);
            assertEquals(0L, decoded.getData().networkSafetyRequestToken);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void litePacketCarriesSafetyAndReadsOld30BytePayload() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new PacketSyncQuantumTerminalDataLite(sample()).toBytes(buffer);
            assertEquals(51 + "controller-generation".length(), buffer.readableBytes());
            PacketSyncQuantumTerminalDataLite decoded = new PacketSyncQuantumTerminalDataLite();
            decoded.fromBytes(buffer);
            assertSnapshot(decoded.getData(), 3, 2);
            assertEquals(0, buffer.readableBytes());
            assertFalse(decoded.getData().globalSafetyEnabled);
            assertFalse(decoded.getData().networkSafetyEnabled);
            assertTrue(decoded.getData().canChangeNetworkSafety);
            assertEquals("controller-generation", decoded.getData().networkSafetyIdentity);
            assertEquals(572L, decoded.getData().networkSafetyRequestToken);
            buffer.setIndex(0, 38);
            decoded.fromBytes(buffer);
            assertSnapshot(decoded.getData(), 3, 2);
            assertFalse(decoded.getData().networkSafetyEnabled);
            assertFalse(decoded.getData().canChangeNetworkSafety);
            assertFalse(decoded.getData().managementOnly);
            buffer.setIndex(0, 30);
            decoded.fromBytes(buffer);
            assertSnapshot(decoded.getData(), 0, 0);
            assertTrue(decoded.getData().globalSafetyEnabled);
            assertFalse(decoded.getData().networkSafetyEnabled);
            assertFalse(decoded.getData().canChangeNetworkSafety);
            assertFalse(decoded.getData().managementOnly);
            assertEquals("", decoded.getData().networkSafetyIdentity);
            assertEquals(0L, decoded.getData().networkSafetyRequestToken);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void defaultLocalSafetyIsOffAndManagementMarkerSurvivesBothResponses() {
        QuantumNetworkData data = new QuantumNetworkData();
        assertTrue(data.globalSafetyEnabled);
        assertFalse(data.networkSafetyEnabled);
        assertFalse(data.canChangeNetworkSafety);
        assertFalse(data.managementOnly);
        data.online = true;
        data.managementOnly = true;
        data.networkSafetyEnabled = true;
        data.networkSafetyIdentity = "controller-generation";
        for (boolean full : new boolean[] { false, true }) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                QuantumNetworkData decoded;
                if (full) {
                    new PacketSyncQuantumTerminalData(data, true, true, 61L).toBytes(buffer);
                    PacketSyncQuantumTerminalData response = new PacketSyncQuantumTerminalData();
                    response.fromBytes(buffer);
                    decoded = response.getData();
                } else {
                    new PacketSyncQuantumTerminalDataLite(data, true, true, 61L).toBytes(buffer);
                    PacketSyncQuantumTerminalDataLite response = new PacketSyncQuantumTerminalDataLite();
                    response.fromBytes(buffer);
                    decoded = response.getData();
                }
                assertTrue(decoded.managementOnly);
                assertTrue(decoded.networkSafetyEnabled);
                assertTrue(decoded.canChangeNetworkSafety);
                assertEquals(61L, decoded.networkSafetyRequestToken);
                assertEquals(0, decoded.usedChannels);
                assertEquals(0, decoded.totalMachines);
                assertTrue(decoded.entries.isEmpty());
                assertFalse(data.canChangeNetworkSafety);
                assertEquals(0L, data.networkSafetyRequestToken);
            } finally {
                buffer.release();
            }
        }
    }
}
