package com.miaokatze.gtswn.network.telemetry;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;

import org.junit.Test;

import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

public class TelemetryQueueTest {

    public static class Payload implements IMessage {

        int length, marker;

        public Payload() {}

        Payload(int length, int marker) {
            this.length = length;
            this.marker = marker;
        }

        public void toBytes(ByteBuf buffer) {
            buffer.writeInt(marker);
            buffer.writeZero(length - 4);
        }

        public void fromBytes(ByteBuf buffer) {
            length = buffer.readableBytes();
            marker = buffer.readInt();
            buffer.skipBytes(length - 4);
        }
    }

    private static final class Connection extends NetworkManager {

        Connection() {
            super(false);
        }

        @Override
        public boolean isChannelOpen() {
            return true;
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(
            unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(field.get(null), type));
    }

    private static EntityPlayerMP player(int id) throws Exception {
        EntityPlayerMP player = allocate(EntityPlayerMP.class);
        player.setEntityId(id);
        NetHandlerPlayServer handler = allocate(NetHandlerPlayServer.class);
        Field connection = NetHandlerPlayServer.class.getDeclaredField("netManager");
        connection.setAccessible(true);
        connection.set(handler, new Connection());
        player.playerNetServerHandler = handler;
        return player;
    }

    @Test
    public void latestCoalescesFrozenBytesAndOnlySendAdvancesCallback() throws Exception {
        BiConsumer<EntityPlayerMP, IMessage> original = TelemetryTransport.sender;
        List<Integer> sent = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        try {
            TelemetryTransport.configure(1024, 1024, 2);
            TelemetryTransport.sender = (player, packet) -> sent.add(((Payload) packet).marker);
            EntityPlayerMP player = player(1001);
            assertTrue(TelemetryTransport.trySend(player, new Payload(TelemetryTransport.MAX_MESSAGE_BYTES, 1)));
            assertFalse(TelemetryTransport.offer(player, "latest", new Payload(100, 2), accepted::incrementAndGet));
            Payload newest = new Payload(100, 3);
            assertFalse(TelemetryTransport.offer(player, "latest", newest, accepted::incrementAndGet));
            newest.marker = 999;
            assertEquals(0, accepted.get());
            assertEquals(1, TelemetryTransport.diagnostics()[4]);
            TelemetryTransport service = new TelemetryTransport();
            service.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            service.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            assertEquals(1, accepted.get());
            assertEquals(Integer.valueOf(3), sent.get(1));
            assertEquals(0, TelemetryTransport.diagnostics()[5]);
        } finally {
            TelemetryTransport.sender = original;
            TelemetryTransport.configure(65536, 1048576, 64);
        }
    }

    @Test
    public void largeReservationDoesNotBlockSmallCurrentAndControlsStillBypassDebt() throws Exception {
        BiConsumer<EntityPlayerMP, IMessage> original = TelemetryTransport.sender;
        List<Integer> sent = new ArrayList<>();
        try {
            TelemetryTransport.configure(1024, 1024, 4);
            TelemetryTransport.sender = (player, packet) -> sent.add(((Payload) packet).marker);
            EntityPlayerMP one = player(1002), two = player(1003);
            TelemetryTransport.trySend(one, new Payload(TelemetryTransport.MAX_MESSAGE_BYTES, 1));
            TelemetryTransport.offer(two, "large", new Payload(8192, 2), null);
            TelemetryTransport service = new TelemetryTransport();
            service.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            assertTrue(TelemetryTransport.trySend(one, new Payload(4, 3)));
            TelemetryTransport.control(one, new Payload(4, 4));
            assertEquals(Integer.valueOf(3), sent.get(1));
            assertEquals(Integer.valueOf(4), sent.get(2));
            for (int i = 1; i < 200; i++) service.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            assertTrue(sent.contains(2));
        } finally {
            TelemetryTransport.sender = original;
            TelemetryTransport.configure(65536, 1048576, 64);
        }
    }

    @Test
    public void queueCountBoundsCancellationAndClearAreExact() throws Exception {
        BiConsumer<EntityPlayerMP, IMessage> original = TelemetryTransport.sender;
        try {
            TelemetryTransport.configure(1024, 1024, 1);
            TelemetryTransport.sender = (player, packet) -> {};
            EntityPlayerMP player = player(1004);
            TelemetryTransport.trySend(player, new Payload(TelemetryTransport.MAX_MESSAGE_BYTES, 1));
            TelemetryTransport.offer(player, "a", new Payload(100, 2), null);
            TelemetryTransport.offer(player, "b", new Payload(100, 3), null);
            assertEquals(1, TelemetryTransport.diagnostics()[4]);
            assertEquals(100, TelemetryTransport.diagnostics()[5]);
            assertTrue(TelemetryTransport.cancel(player, "a"));
            assertEquals(0, TelemetryTransport.diagnostics()[5]);
            TelemetryTransport.clear();
            assertArrayEquals(new long[6], TelemetryTransport.diagnostics());
        } finally {
            TelemetryTransport.sender = original;
            TelemetryTransport.configure(65536, 1048576, 64);
        }
    }

    @Test
    public void authorizationMayCancelItsOwnOrSiblingEntryWithoutIteratorOrAccountingCorruption() throws Exception {
        BiConsumer<EntityPlayerMP, IMessage> original = TelemetryTransport.sender;
        try {
            for (boolean cancelSelf : new boolean[] { false, true }) {
                TelemetryTransport.configure(1024, 1024, 4);
                List<Integer> sent = new ArrayList<>();
                TelemetryTransport.sender = (ignored, packet) -> sent.add(((Payload) packet).marker);
                EntityPlayerMP player = player(cancelSelf ? 1101 : 1102);
                assertTrue(TelemetryTransport.trySend(player, new Payload(TelemetryTransport.MAX_MESSAGE_BYTES, 1)));
                java.util.concurrent.atomic.AtomicBoolean revalidate = new java.util.concurrent.atomic.AtomicBoolean();
                assertFalse(TelemetryTransport.offer(player, "self", new Payload(100, 2), () -> {
                    if (revalidate.get()) {
                        TelemetryTransport.cancel(player, "sibling");
                        if (cancelSelf) TelemetryTransport.cancel(player, "self");
                    }
                    return true;
                }, null));
                assertFalse(TelemetryTransport.offer(player, "sibling", new Payload(100, 3), null));
                revalidate.set(true);
                TelemetryTransport service = new TelemetryTransport();
                service.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
                service.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
                assertEquals(cancelSelf ? 1 : 2, sent.size());
                assertFalse(sent.contains(3));
                assertEquals(0, TelemetryTransport.diagnostics()[4]);
                assertEquals(0, TelemetryTransport.diagnostics()[5]);
            }
        } finally {
            TelemetryTransport.sender = original;
            TelemetryTransport.configure(65536, 1048576, 64);
        }
    }

    @Test
    public void admissionDistinguishesRetainedFromRejectedAndRetryEventuallySends() throws Exception {
        BiConsumer<EntityPlayerMP, IMessage> original = TelemetryTransport.sender;
        List<Integer> sent = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        try {
            TelemetryTransport.configure(1024, 1024, 1);
            TelemetryTransport.sender = (player, packet) -> sent.add(((Payload) packet).marker);
            EntityPlayerMP player = player(1201);
            assertTrue(TelemetryTransport.trySend(player, new Payload(TelemetryTransport.MAX_MESSAGE_BYTES, 1)));
            assertEquals(
                TelemetryTransport.Admission.QUEUED,
                TelemetryTransport.offerStatus(player, "other", new Payload(100, 2), () -> true, null));
            assertEquals(
                TelemetryTransport.Admission.REJECTED,
                TelemetryTransport
                    .offerStatus(player, "quantum", new Payload(100, 3), () -> true, accepted::incrementAndGet));
            assertEquals(0, accepted.get());
            TelemetryTransport service = new TelemetryTransport();
            service.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            service.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            assertEquals(
                TelemetryTransport.Admission.QUEUED,
                TelemetryTransport
                    .offerStatus(player, "quantum", new Payload(100, 3), () -> true, accepted::incrementAndGet));
            for (int i = 0; i < 2; i++) service.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            assertEquals(1, accepted.get());
            assertEquals(Integer.valueOf(3), sent.get(2));
        } finally {
            TelemetryTransport.sender = original;
            TelemetryTransport.configure(65536, 1048576, 64);
        }
    }

    @Test
    public void nativePanelQueueFreezesPayloadCountsActualBytesAndUsesOnlyNativeDispatch() throws Exception {
        BiConsumer<EntityPlayerMP, IMessage> original = TelemetryTransport.sender;
        BiConsumer<EntityPlayerMP, net.minecraft.network.play.server.S35PacketUpdateTileEntity> nativeOriginal = TelemetryTransport.nativeSender;
        List<net.minecraft.network.play.server.S35PacketUpdateTileEntity> nativeSent = new ArrayList<>();
        try {
            TelemetryTransport.configure(1024, 1024, 2);
            TelemetryTransport.sender = (player, packet) -> { assertTrue(packet instanceof Payload); };
            TelemetryTransport.nativeSender = (player, packet) -> nativeSent.add(packet);
            EntityPlayerMP player = player(1202);
            assertTrue(TelemetryTransport.trySend(player, new Payload(TelemetryTransport.MAX_MESSAGE_BYTES, 1)));
            net.minecraft.nbt.NBTTagCompound data = new net.minecraft.nbt.NBTTagCompound();
            data.setInteger("euSyncKind", 0);
            data.setLong("euSyncSeq", 10);
            data.setString("cachedEu", "1234");
            net.minecraft.network.play.server.S35PacketUpdateTileEntity packet = new net.minecraft.network.play.server.S35PacketUpdateTileEntity(
                1,
                2,
                3,
                1,
                data);
            int bytes = TelemetryTransport.encodedBytes(new TelemetryTransport.NativePanelFrame(packet));
            assertFalse(TelemetryTransport.trySendNativePanel(player, packet));
            assertEquals(
                TelemetryTransport.Admission.QUEUED,
                TelemetryTransport.offerNativePanel(player, "panel", packet, () -> true, null));
            assertEquals(bytes, TelemetryTransport.diagnostics()[5]);
            packet.func_148857_g()
                .setLong("euSyncSeq", 11);
            bytes = TelemetryTransport.encodedBytes(new TelemetryTransport.NativePanelFrame(packet));
            assertEquals(
                TelemetryTransport.Admission.QUEUED,
                TelemetryTransport.offerNativePanel(player, "panel", packet, () -> true, null));
            assertEquals(1, TelemetryTransport.diagnostics()[4]);
            packet.func_148857_g()
                .setString("cachedEu", "mutated");
            TelemetryTransport service = new TelemetryTransport();
            for (int i = 0; i <= bytes / 51 + 1; i++) service.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            assertEquals(1, nativeSent.size());
            assertEquals(
                "1234",
                nativeSent.get(0)
                    .func_148857_g()
                    .getString("cachedEu"));
            assertEquals(
                11,
                nativeSent.get(0)
                    .func_148857_g()
                    .getLong("euSyncSeq"));
            assertEquals(
                0,
                nativeSent.get(0)
                    .func_148857_g()
                    .getInteger("euSyncKind"));
            assertEquals(TelemetryTransport.MAX_MESSAGE_BYTES + bytes, TelemetryTransport.diagnostics()[0]);
            assertEquals(0, TelemetryTransport.diagnostics()[5]);
        } finally {
            TelemetryTransport.sender = original;
            TelemetryTransport.nativeSender = nativeOriginal;
            TelemetryTransport.configure(65536, 1048576, 64);
        }
    }

    @Test
    public void continuousCurrentAndMaximumBulkBothProgressWithinSameTotalBudget() throws Exception {
        BiConsumer<EntityPlayerMP, IMessage> original = TelemetryTransport.sender;
        try {
            for (boolean samePlayer : new boolean[] { true, false }) {
                TelemetryTransport.configure(65536, 65536, 4);
                EntityPlayerMP bulkPlayer = player(samePlayer ? 1401 : 1402);
                EntityPlayerMP currentPlayer = samePlayer ? bulkPlayer : player(1403);
                AtomicInteger now = new AtomicInteger(), lastCurrent = new AtomicInteger(),
                    maxAge = new AtomicInteger();
                AtomicInteger currents = new AtomicInteger(), bulks = new AtomicInteger();
                final long[] total = { 0 };
                TelemetryTransport.sender = (player, message) -> {
                    Payload packet = (Payload) message;
                    total[0] += packet.length;
                    if (packet.marker == 2) bulks.incrementAndGet();
                    if (packet.marker == 3) {
                        maxAge.set(Math.max(maxAge.get(), now.get() - lastCurrent.getAndSet(now.get())));
                        currents.incrementAndGet();
                    }
                };
                assertTrue(
                    TelemetryTransport.trySend(bulkPlayer, new Payload(TelemetryTransport.MAX_MESSAGE_BYTES, 1)));
                assertEquals(
                    TelemetryTransport.Admission.QUEUED,
                    TelemetryTransport.offerStatus(
                        bulkPlayer,
                        "history",
                        new Payload(TelemetryTransport.MAX_MESSAGE_BYTES, 2),
                        () -> true,
                        null));
                TelemetryTransport service = new TelemetryTransport();
                for (int i = 1; i <= 1600; i++) {
                    now.set(i);
                    TelemetryTransport.offer(currentPlayer, "current", new Payload(4096, 3), null);
                    service.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
                    assertTrue(
                        "Shared tokens overspent",
                        total[0] <= TelemetryTransport.MAX_MESSAGE_BYTES + (long) i * 65536 / 20);
                }
                assertEquals("Maximum legal bulk must eventually fit", 1, bulks.get());
                assertTrue("Current must progress while bulk accumulates credit", currents.get() > 200);
                assertTrue("Current cannot wait behind the entire bulk accumulation", maxAge.get() <= 10);
                assertEquals(total[0], TelemetryTransport.diagnostics()[0]);
                assertTrue(TelemetryTransport.diagnostics()[4] <= 4);
            }
        } finally {
            TelemetryTransport.sender = original;
            TelemetryTransport.configure(65536, 1048576, 64);
        }
    }

    @Test
    public void bulkLaneReservesCurrentQueueRoomAndReplacementAccountingIsExact() throws Exception {
        BiConsumer<EntityPlayerMP, IMessage> original = TelemetryTransport.sender;
        try {
            TelemetryTransport.configure(1024, 1024, 2);
            TelemetryTransport.sender = (player, packet) -> {};
            EntityPlayerMP player = player(1404);
            TelemetryTransport.trySend(player, new Payload(TelemetryTransport.MAX_MESSAGE_BYTES, 1));
            assertEquals(
                TelemetryTransport.Admission.QUEUED,
                TelemetryTransport.offerStatus(player, "bulk", new Payload(8192, 2), () -> true, null));
            assertEquals(
                TelemetryTransport.Admission.REJECTED,
                TelemetryTransport.offerStatus(player, "second-bulk", new Payload(8192, 3), () -> true, null));
            assertEquals(
                TelemetryTransport.Admission.QUEUED,
                TelemetryTransport.offerStatus(player, "current", new Payload(100, 4), () -> true, null));
            assertEquals(2, TelemetryTransport.diagnostics()[4]);
            assertEquals(8292, TelemetryTransport.diagnostics()[5]);
            // Same-key projection changes can move between lanes without duplicating an entry or leaking credits.
            TelemetryTransport.offer(player, "bulk", new Payload(200, 5), null);
            assertEquals(300, TelemetryTransport.diagnostics()[5]);
            TelemetryTransport.cancel(player, "bulk");
            TelemetryTransport.cancel(player, "current");
            assertEquals(0, TelemetryTransport.diagnostics()[4]);
            assertEquals(0, TelemetryTransport.diagnostics()[5]);
            TelemetryTransport.clear();
            assertArrayEquals(new long[6], TelemetryTransport.diagnostics());
        } finally {
            TelemetryTransport.sender = original;
            TelemetryTransport.configure(65536, 1048576, 64);
        }
    }

}
