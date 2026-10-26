package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;

import org.junit.Test;

import com.miaokatze.gtswn.network.telemetry.TelemetryTransport;

import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class DeviceFrozenPageBudgetTest {

    public static class Payload implements IMessage {

        int size, marker;

        public Payload() {}

        Payload(int size, int marker) {
            this.size = size;
            this.marker = marker;
        }

        public void toBytes(ByteBuf out) {
            out.writeInt(marker);
            out.writeZero(size - 4);
        }

        public void fromBytes(ByteBuf in) {
            size = in.readableBytes();
            marker = in.readInt();
            in.skipBytes(size - 4);
        }
    }

    private static class Connection extends NetworkManager {

        Connection() {
            super(false);
        }

        @Override
        public boolean isChannelOpen() {
            return true;
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field f = u.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return type.cast(
            u.getMethod("allocateInstance", Class.class)
                .invoke(f.get(null), type));
    }

    private static EntityPlayerMP player() throws Exception {
        EntityPlayerMP p = allocate(EntityPlayerMP.class);
        p.setEntityId(2981);
        NetHandlerPlayServer h = allocate(NetHandlerPlayServer.class);
        Field f = NetHandlerPlayServer.class.getDeclaredField("netManager");
        f.setAccessible(true);
        f.set(h, new Connection());
        p.playerNetServerHandler = h;
        return p;
    }

    @Test
    public void retainedFrozenPageAndCurrentUpdatesBothProgress() throws Exception {
        Field sender = TelemetryTransport.class.getDeclaredField("sender");
        sender.setAccessible(true);
        Object old = sender.get(null);
        AtomicInteger pageSends = new AtomicInteger(), currentSends = new AtomicInteger(),
            callbacks = new AtomicInteger();
        try {
            sender.set(null, (BiConsumer<EntityPlayerMP, IMessage>) (p, msg) -> {
                int marker = ((Payload) msg).marker;
                if (marker == 2) pageSends.incrementAndGet();
                if (marker == 3) currentSends.incrementAndGet();
            });
            TelemetryTransport.configure(65536, 65536, 4);
            EntityPlayerMP p = player();
            assertTrue(TelemetryTransport.trySend(p, new Payload(TelemetryTransport.MAX_MESSAGE_BYTES, 1)));
            DeviceTerminalRequestQueue.SentState state = new DeviceTerminalRequestQueue.SentState();
            state.terminalId = UUID.randomUUID();
            assertFalse(
                DeviceTerminalRequestQueue
                    .queueFrozenPage(p, state, new Payload(8192, 2), () -> true, callbacks::incrementAndGet));
            assertNotNull(state.pendingPage);
            assertEquals(0, callbacks.get());
            TelemetryTransport t = new TelemetryTransport();
            for (int i = 0; i < 100; i++) {
                TelemetryTransport.offer(p, "current", new Payload(4096, 3), null);
                t.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            }
            assertEquals(1, pageSends.get());
            assertEquals(1, callbacks.get());
            assertTrue(currentSends.get() > 0);
            assertNull(state.pendingPage);
        } finally {
            sender.set(null, old);
            TelemetryTransport.configure(65536, 1048576, 64);
        }
    }

    @Test
    public void revocationDropsQueuedPageWithoutAdvancingProducer() throws Exception {
        Field sender = TelemetryTransport.class.getDeclaredField("sender");
        sender.setAccessible(true);
        Object old = sender.get(null);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicBoolean authorized = new AtomicBoolean(true);
        try {
            sender.set(null, (BiConsumer<EntityPlayerMP, IMessage>) (p, msg) -> {});
            TelemetryTransport.configure(65536, 65536, 4);
            EntityPlayerMP p = player();
            TelemetryTransport.trySend(p, new Payload(TelemetryTransport.MAX_MESSAGE_BYTES, 1));
            DeviceTerminalRequestQueue.SentState state = new DeviceTerminalRequestQueue.SentState();
            state.terminalId = UUID.randomUUID();
            DeviceTerminalRequestQueue
                .queueFrozenPage(p, state, new Payload(8192, 2), authorized::get, callbacks::incrementAndGet);
            authorized.set(false);
            new TelemetryTransport().tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            assertNull(state.pendingPage);
            assertEquals(0, callbacks.get());
            assertEquals(0, TelemetryTransport.diagnostics()[4]);
        } finally {
            sender.set(null, old);
            TelemetryTransport.configure(65536, 1048576, 64);
        }
    }

    @Test
    public void changedSessionCancelsPendingPageAndThrowingAuthorizationCannotStrandIt() throws Exception {
        Field sender = TelemetryTransport.class.getDeclaredField("sender");
        sender.setAccessible(true);
        Object old = sender.get(null);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicBoolean unavailable = new AtomicBoolean(false);
        try {
            sender.set(null, (BiConsumer<EntityPlayerMP, IMessage>) (p, msg) -> {});
            TelemetryTransport.configure(65536, 65536, 4);
            EntityPlayerMP p = player();
            TelemetryTransport.trySend(p, new Payload(TelemetryTransport.MAX_MESSAGE_BYTES, 1));
            DeviceTerminalRequestQueue.SentState state = new DeviceTerminalRequestQueue.SentState();
            state.terminalId = UUID.randomUUID();
            DeviceTerminalRequestQueue.prepareSession(p, state, UUID.randomUUID());
            DeviceTerminalRequestQueue
                .queueFrozenPage(p, state, new Payload(8192, 2), () -> true, callbacks::incrementAndGet);
            DeviceTerminalRequestQueue.prepareSession(p, state, UUID.randomUUID());
            assertNull(state.pendingPage);
            assertEquals(0, TelemetryTransport.diagnostics()[4]);
            assertEquals(0, callbacks.get());
            DeviceTerminalRequestQueue.queueFrozenPage(p, state, new Payload(8192, 2), () -> {
                if (unavailable.get()) throw new IllegalStateException("source unavailable");
                return true;
            }, callbacks::incrementAndGet);
            unavailable.set(true);
            new TelemetryTransport().tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            assertNull(state.pendingPage);
            assertEquals(0, callbacks.get());
            assertEquals(0, TelemetryTransport.diagnostics()[4]);
        } finally {
            sender.set(null, old);
            TelemetryTransport.configure(65536, 1048576, 64);
        }
    }
}
