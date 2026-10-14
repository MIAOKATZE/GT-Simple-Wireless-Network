package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketDeviceTerminalActionTest {

    @Test
    public void modeCyclePreservesItsExactTerminalAndAllowsExplicitBootstrapId() throws Exception {
        for (UUID id : new UUID[] { UUID.randomUUID(), new UUID(0, 0) }) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                PacketDeviceTerminalAction.cycleRecognition(id, "0:1:64:2")
                    .toBytes(bytes);
                PacketDeviceTerminalAction decoded = new PacketDeviceTerminalAction();
                decoded.fromBytes(bytes);
                java.lang.reflect.Field field = PacketDeviceTerminalAction.class.getDeclaredField("terminalId");
                field.setAccessible(true);
                assertEquals(id, field.get(decoded));
                field = PacketDeviceTerminalAction.class.getDeclaredField("action");
                field.setAccessible(true);
                assertEquals(PacketDeviceTerminalAction.ACTION_CYCLE_RECOGNITION, field.getByte(decoded));
                assertEquals(0, bytes.readableBytes());
            } finally {
                bytes.release();
            }
        }
    }
}
