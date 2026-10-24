package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketDeviceTerminalActionTest {

    @Test
    public void everyListActionCarriesTheOpenedTerminalAndTargetIdentity() throws Exception {
        UUID id = UUID.randomUUID();
        for (byte action = 0; action <= 3; action++) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                new PacketDeviceTerminalAction(id, action, (byte) 1, (byte) 0, (byte) 2, "0:1:64:2")
                    .targetMetaTileId(123)
                    .toBytes(bytes);
                PacketDeviceTerminalAction decoded = new PacketDeviceTerminalAction();
                decoded.fromBytes(bytes);
                assertEquals(id, field(decoded, "terminalId"));
                assertEquals(action, ((Byte) field(decoded, "action")).byteValue());
                if (action >= 2) assertEquals(123, field(decoded, "targetMetaTileId"));
                assertEquals(0, bytes.readableBytes());
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    public void legacyUnanchoredActionsAndTruncatedTargetsAreRejected() throws Exception {
        for (byte action = 0; action <= 3; action++) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                new PacketDeviceTerminalAction(action, (byte) 0, (byte) 0, (byte) 0, "0:1:64:2").toBytes(bytes);
                PacketDeviceTerminalAction decoded = new PacketDeviceTerminalAction();
                decoded.fromBytes(bytes);
                assertEquals((byte) -1, ((Byte) field(decoded, "action")).byteValue());
                bytes.clear();
                new PacketDeviceTerminalAction(UUID.randomUUID(), action, (byte) 0, (byte) 0, (byte) 0, "0:1:64:2")
                    .toBytes(bytes);
                bytes.writerIndex(bytes.writerIndex() - 1);
                decoded.fromBytes(bytes);
                assertEquals((byte) -1, ((Byte) field(decoded, "action")).byteValue());
            } finally {
                bytes.release();
            }
        }
    }

    private static Object field(PacketDeviceTerminalAction packet, String name) throws Exception {
        java.lang.reflect.Field field = PacketDeviceTerminalAction.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(packet);
    }

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
