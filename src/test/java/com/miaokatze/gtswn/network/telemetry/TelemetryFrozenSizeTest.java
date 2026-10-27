package com.miaokatze.gtswn.network.telemetry;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.function.BiConsumer;

import net.minecraft.entity.player.EntityPlayerMP;

import org.junit.Test;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class TelemetryFrozenSizeTest {

    public static class Payload implements IMessage {

        static int writes;
        int marker;

        public Payload() {}

        public void toBytes(ByteBuf buffer) {
            writes++;
            buffer.writeInt(marker);
        }

        public void fromBytes(ByteBuf buffer) {
            marker = buffer.readInt();
        }
    }

    @Test
    public void validatedFrozenSizeIsReusedWithoutEncodingAgain() throws Exception {
        Method fixture = TelemetryQueueTest.class.getDeclaredMethod("player", int.class);
        fixture.setAccessible(true);
        EntityPlayerMP player = (EntityPlayerMP) fixture.invoke(null, 30001);
        BiConsumer<EntityPlayerMP, IMessage> original = TelemetryTransport.sender;
        try {
            TelemetryTransport.configure(65536, 1048576, 64);
            Payload input = new Payload();
            input.marker = 73;
            Payload.writes = 0;
            TelemetryTransport.sender = (recipient, message) -> {
                assertNotSame(input, message);
                assertEquals(73, ((Payload) message).marker);
                assertEquals(2, Payload.writes);
                assertEquals(4, TelemetryTransport.encodedBytes(message));
            };
            assertEquals(
                TelemetryTransport.Admission.SENT,
                TelemetryTransport.offerStatus(player, "frozen-size", input, () -> true, null));
            assertEquals(4, TelemetryTransport.diagnostics()[0]);
        } finally {
            TelemetryTransport.sender = original;
            TelemetryTransport.configure(65536, 1048576, 64);
        }
    }
}
