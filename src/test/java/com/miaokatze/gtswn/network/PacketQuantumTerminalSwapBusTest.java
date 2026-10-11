package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketQuantumTerminalSwapBusTest {

    @Test
    public void coordinatesFaceAndExplicitCoreRemovalIntentRoundTrip() {
        for (boolean remove : new boolean[] { false, true }) {
            for (int side = 0; side < 6; side++) {
                ByteBuf input = Unpooled.buffer();
                ByteBuf output = Unpooled.buffer();
                try {
                    new PacketQuantumTerminalSwapBus(-17, 42, 300, side, remove).toBytes(input);
                    assertEquals(17, input.readableBytes());
                    PacketQuantumTerminalSwapBus decoded = new PacketQuantumTerminalSwapBus();
                    decoded.fromBytes(input);
                    decoded.toBytes(output);
                    assertEquals(-17, output.readInt());
                    assertEquals(42, output.readInt());
                    assertEquals(300, output.readInt());
                    assertEquals(side, output.readInt());
                    assertEquals(remove, output.readBoolean());
                } finally {
                    input.release();
                    output.release();
                }
            }
        }
    }
}
