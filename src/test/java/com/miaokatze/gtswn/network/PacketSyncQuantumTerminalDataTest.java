package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketSyncQuantumTerminalDataTest {

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
            buffer.setIndex(0, bytes - 8);
            decoded.fromBytes(buffer);
            assertSnapshot(decoded.getData(), 0, 0);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void litePacketIs38BytesAndReadsOld30BytePayload() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new PacketSyncQuantumTerminalDataLite(sample()).toBytes(buffer);
            assertEquals(38, buffer.readableBytes());
            PacketSyncQuantumTerminalDataLite decoded = new PacketSyncQuantumTerminalDataLite();
            decoded.fromBytes(buffer);
            assertSnapshot(decoded.getData(), 3, 2);
            assertEquals(0, buffer.readableBytes());
            buffer.setIndex(0, 30);
            decoded.fromBytes(buffer);
            assertSnapshot(decoded.getData(), 0, 0);
        } finally {
            buffer.release();
        }
    }
}
