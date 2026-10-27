package com.miaokatze.gtswn.network.telemetry;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;

import com.miaokatze.gtswn.network.GTSWNPacketHandler;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Main-thread, bounded latest-value telemetry. Controls use the channel's separate FIFO immediately.
 * Byte counters describe serialized mod payloads per recipient, not TCP/NIC traffic.
 */
public final class TelemetryTransport {

    public static final int MAX_MESSAGE_BYTES = 2_097_152;
    /** Size-based QoS: small current values/deltas precede large history/full snapshots. */
    public static final int CURRENT_MAX_BYTES = 4096;
    private static int playerRate = 65_536, globalRate = 1_048_576, pendingLimit = 64;
    private static long tick, sentBytes, controlBytes, coalesced, rejected, queuedBytes;
    private static final long MAX_QUEUED_BYTES = 32L * 1024 * 1024, MAX_PLAYER_QUEUED_BYTES = 4L * 1024 * 1024;
    private static EntityPlayerMP reservedPlayer;
    private static String reservedKey;
    private static long bulkQueued;
    static java.util.function.BiConsumer<EntityPlayerMP, IMessage> sender = (player,
        packet) -> GTSWNPacketHandler.NETWORK.sendTo(packet, player);
    static java.util.function.BiConsumer<EntityPlayerMP, S35PacketUpdateTileEntity> nativeSender = (player,
        packet) -> player.playerNetServerHandler.sendPacket(packet);

    public enum Admission {
        SENT,
        QUEUED,
        REJECTED
    }

    private static TelemetryByteBudget global = bucket(globalRate);
    private static TelemetryByteBudget currentShare = currentBucket(globalRate);
    private static final LinkedHashMap<EntityPlayerMP, PlayerQueue> PLAYERS = new LinkedHashMap<>();

    private static TelemetryByteBudget bucket(int rate) {
        return new TelemetryByteBudget(rate, Math.max(MAX_MESSAGE_BYTES, (long) rate * 2));
    }

    private static TelemetryByteBudget currentBucket(int rate) {
        return new TelemetryByteBudget(Math.max(1, rate / 2), Math.max(CURRENT_MAX_BYTES, Math.min(65536, rate / 2)));
    }

    public static void configure(int perPlayerBytes, int globalBytes, int maxPendingPerPlayer) {
        playerRate = Math.max(1024, Math.min(16_777_216, perPlayerBytes));
        globalRate = Math.max(1024, Math.min(268_435_456, globalBytes));
        pendingLimit = Math.max(1, Math.min(256, maxPendingPerPlayer));
        clear();
    }

    public static int encodedBytes(IMessage packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            packet.toBytes(buffer);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    private static FrozenMessage freeze(IMessage message) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            message.toBytes(buffer);
            if (buffer.readableBytes() > MAX_MESSAGE_BYTES) return null;
            IMessage copy = message.getClass()
                .getDeclaredConstructor()
                .newInstance();
            copy.fromBytes(buffer);
            // A queued packet must be independently serializable; rejected/truncated decodes never enter the queue.
            ByteBuf check = Unpooled.buffer();
            int bytes;
            try {
                copy.toBytes(check);
                bytes = check.readableBytes();
                if (bytes > MAX_MESSAGE_BYTES) return null;
            } finally {
                check.release();
            }
            return new FrozenMessage(copy, bytes);
        } catch (ReflectiveOperationException | RuntimeException invalid) {
            return null;
        } finally {
            buffer.release();
        }
    }

    private static final class FrozenMessage {

        final IMessage packet;
        final int bytes;

        FrozenMessage(IMessage packet, int bytes) {
            this.packet = packet;
            this.bytes = bytes;
        }
    }

    private static PlayerQueue player(EntityPlayerMP player) {
        PlayerQueue result = PLAYERS.get(player);
        Object connection = player.playerNetServerHandler.netManager;
        if (result == null || result.connection != connection) {
            if (PLAYERS.size() >= 1024 && result == null) return null;
            if (result != null) {
                queuedBytes -= result.bytes;
                bulkQueued -= result.bulkCount;
            }
            if (reservedPlayer == player) {
                reservedPlayer = null;
                reservedKey = null;
            }
            result = new PlayerQueue(connection);
            PLAYERS.put(player, result);
        }
        return result;
    }

    /** True only when handed to the native channel. accepted means channel enqueue, NOT a client baseline ACK. */
    public static boolean offer(EntityPlayerMP player, String key, IMessage packet, Runnable accepted) {
        return offer(player, key, packet, () -> true, accepted);
    }

    public static boolean offer(EntityPlayerMP player, String key, IMessage packet,
        java.util.function.BooleanSupplier authorized, Runnable accepted) {
        return offerStatus(player, key, packet, authorized, accepted) == Admission.SENT;
    }

    /** QUEUED means retained; only REJECTED requires producer retry. Callbacks still mean actual channel enqueue. */
    public static Admission offerStatus(EntityPlayerMP player, String key, IMessage packet,
        java.util.function.BooleanSupplier authorized, Runnable accepted) {
        if (!MonitorCapabilities.connected(player) || key == null || authorized == null || !authorized.getAsBoolean())
            return Admission.REJECTED;
        FrozenMessage frozen = freeze(packet);
        if (frozen == null) {
            rejected++;
            return Admission.REJECTED;
        }
        int bytes = frozen.bytes;
        if (bytes <= 0 || bytes > MAX_MESSAGE_BYTES) {
            rejected++;
            return Admission.REJECTED;
        }
        PlayerQueue queue = player(player);
        if (queue == null) {
            rejected++;
            return Admission.REJECTED;
        }
        boolean bulk = bytes > CURRENT_MAX_BYTES;
        boolean firstInLane = !queue.pending.containsKey(key) && first(queue, bulk) == null;
        if (firstInLane && (!bulk || reservedPlayer == null && bulkQueued == 0) && admit(queue, bytes, bulk)) {
            send(player, frozen.packet, bytes, accepted);
            return Admission.SENT;
        }
        if (!queue.pending.containsKey(key)
            && (queue.pending.size() >= pendingLimit || bulk && queue.bulkCount >= Math.max(1, pendingLimit - 1))) {
            rejected++;
            return Admission.REJECTED;
        }
        Pending previous = queue.pending.get(key);
        long growth = bytes - (previous == null ? 0 : previous.bytes);
        if (queue.bytes + growth > MAX_PLAYER_QUEUED_BYTES || queuedBytes + growth > MAX_QUEUED_BYTES) {
            rejected++;
            return Admission.REJECTED;
        }
        queue.pending.put(key, new Pending(frozen.packet, bytes, authorized, accepted));
        int bulkGrowth = (bulk ? 1 : 0) - (previous != null && previous.bulk ? 1 : 0);
        queue.bulkCount += bulkGrowth;
        bulkQueued += bulkGrowth;
        queue.bytes += growth;
        queuedBytes += growth;
        if (previous != null) coalesced++;
        return Admission.QUEUED;
    }

    /** Internal native envelope, never sent as an FML discriminator. Callers supply complete S35 snapshots. */
    public static Admission offerNativePanel(EntityPlayerMP player, String key, S35PacketUpdateTileEntity packet,
        java.util.function.BooleanSupplier authorized, Runnable accepted) {
        return offerStatus(player, key, new NativePanelFrame(packet), authorized, accepted);
    }

    public static boolean trySendNativePanel(EntityPlayerMP player, S35PacketUpdateTileEntity packet) {
        return trySend(player, new NativePanelFrame(packet));
    }

    public static final class NativePanelFrame implements IMessage {

        private byte[] bytes = new byte[0];

        public NativePanelFrame() {}

        NativePanelFrame(S35PacketUpdateTileEntity packet) {
            ByteBuf buffer = Unpooled.buffer();
            try {
                packet.writePacketData(new PacketBuffer(buffer));
                bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
            } catch (java.io.IOException failure) {
                throw new IllegalArgumentException("Invalid native panel frame", failure);
            } finally {
                buffer.release();
            }
        }

        S35PacketUpdateTileEntity packet() {
            ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
            try {
                S35PacketUpdateTileEntity packet = new S35PacketUpdateTileEntity();
                packet.readPacketData(new PacketBuffer(buffer));
                return packet;
            } catch (java.io.IOException failure) {
                throw new IllegalArgumentException("Invalid frozen native frame", failure);
            } finally {
                buffer.release();
            }
        }

        public void toBytes(ByteBuf out) {
            out.writeBytes(bytes);
        }

        public void fromBytes(ByteBuf in) {
            if (in.readableBytes() > MAX_MESSAGE_BYTES) throw new IllegalArgumentException("Oversized native frame");
            bytes = new byte[in.readableBytes()];
            in.readBytes(bytes);
        }
    }

    /** Frozen paginated streams retain their own progress if this returns false. */
    public static boolean trySend(EntityPlayerMP player, IMessage packet) {
        if (!MonitorCapabilities.connected(player)) return false;
        FrozenMessage frozen = freeze(packet);
        if (frozen == null) {
            rejected++;
            return false;
        }
        int bytes = frozen.bytes;
        if (bytes <= 0 || bytes > MAX_MESSAGE_BYTES) {
            rejected++;
            return false;
        }
        PlayerQueue queue = player(player);
        boolean bulk = bytes > CURRENT_MAX_BYTES;
        // Small complete pages can bypass pending bulk; a frozen producer retains large pages until their turn.
        if (queue == null || bulk && reservedPlayer != null
            || !bulk && first(queue, false) != null
            || !admit(queue, bytes, bulk)) return false;
        send(player, frozen.packet, bytes, null);
        return true;
    }

    public static boolean cancel(EntityPlayerMP player, String key) {
        PlayerQueue queue = PLAYERS.get(player);
        if (queue != null) {
            Pending removed = queue.pending.remove(key);
            if (removed != null) {
                accountRemoval(queue, removed);
                if (player == reservedPlayer && key.equals(reservedKey)) {
                    reservedPlayer = null;
                    reservedKey = null;
                }
                return true;
            }
        }
        return false;
    }

    /** ACK/revocation/control order is the native reliable FIFO, independent of telemetry debt. */
    public static void control(EntityPlayerMP player, IMessage message) {
        if (!MonitorCapabilities.connected(player)) return;
        int bytes = encodedBytes(message);
        if (bytes <= 0 || bytes > MAX_MESSAGE_BYTES) {
            rejected++;
            return;
        }
        sender.accept(player, message);
        controlBytes += bytes;
    }

    private static boolean admit(PlayerQueue queue, int bytes, boolean bulk) {
        boolean local = queue.budget.available(bytes, tick), shared = global.available(bytes, tick);
        if (!local || !shared) return false;
        // These are admission guards INSIDE the total buckets, not additional bandwidth. While bulk waits,
        // current may use half the refill plus a bounded burst; the other half accumulates for the large frame.
        boolean sharedGuard = !bulk && bulkQueued > 0;
        boolean localGuard = !bulk && queue.bulkCount > 0;
        if (sharedGuard && !currentShare.available(bytes, tick)
            || localGuard && !queue.currentShare.available(bytes, tick)) return false;
        queue.budget.consume(bytes);
        global.consume(bytes);
        if (sharedGuard) currentShare.consume(bytes);
        if (localGuard) queue.currentShare.consume(bytes);
        return true;
    }

    private static Map.Entry<String, Pending> first(PlayerQueue queue, boolean bulk) {
        for (Map.Entry<String, Pending> entry : queue.pending.entrySet())
            if (entry.getValue().bulk == bulk) return entry;
        return null;
    }

    private static void accountRemoval(PlayerQueue queue, Pending value) {
        queue.bytes -= value.bytes;
        queuedBytes -= value.bytes;
        if (value.bulk) {
            queue.bulkCount--;
            bulkQueued--;
        }
    }

    /** False only while this exact retained frame still waits for credit. */
    private static boolean attempt(EntityPlayerMP player, PlayerQueue queue, String key, Pending value) {
        boolean authorized;
        try {
            authorized = value.authorized.getAsBoolean();
        } catch (RuntimeException unavailable) {
            authorized = false;
        }
        // Revalidation may cancel/replace this entry or clear the whole queue. Never reuse its iterator.
        if (PLAYERS.get(player) != queue || queue.pending.get(key) != value) return true;
        if (!authorized) {
            queue.pending.remove(key);
            accountRemoval(queue, value);
            rejected++;
            return true;
        }
        if (!admit(queue, value.bytes, value.bulk)) return false;
        queue.pending.remove(key);
        accountRemoval(queue, value);
        send(player, value.packet, value.bytes, value.accepted);
        return true;
    }

    private static void drainCurrent() {
        int remaining = PLAYERS.size();
        while (remaining-- > 0 && !PLAYERS.isEmpty()) {
            Iterator<Map.Entry<EntityPlayerMP, PlayerQueue>> iterator = PLAYERS.entrySet()
                .iterator();
            Map.Entry<EntityPlayerMP, PlayerQueue> entry = iterator.next();
            EntityPlayerMP player = entry.getKey();
            PlayerQueue queue = entry.getValue();
            iterator.remove();
            PLAYERS.put(player, queue);
            Map.Entry<String, Pending> current = first(queue, false);
            if (current != null) attempt(player, queue, current.getKey(), current.getValue());
        }
        // A full pass returns the map to its starting order; advance one extra position to share scarce current credit.
        if (PLAYERS.size() > 1) {
            Iterator<Map.Entry<EntityPlayerMP, PlayerQueue>> iterator = PLAYERS.entrySet()
                .iterator();
            Map.Entry<EntityPlayerMP, PlayerQueue> entry = iterator.next();
            EntityPlayerMP player = entry.getKey();
            PlayerQueue queue = entry.getValue();
            iterator.remove();
            PLAYERS.put(player, queue);
        }
    }

    private static void drainBulk() {
        int remaining = PLAYERS.size();
        while (remaining-- > 0) {
            PlayerQueue queue = PLAYERS.get(reservedPlayer);
            Pending value = queue == null ? null : queue.pending.get(reservedKey);
            if (value == null || !value.bulk) {
                reservedPlayer = null;
                reservedKey = null;
                for (Map.Entry<EntityPlayerMP, PlayerQueue> entry : PLAYERS.entrySet()) {
                    Map.Entry<String, Pending> candidate = first(entry.getValue(), true);
                    if (candidate == null) continue;
                    reservedPlayer = entry.getKey();
                    reservedKey = candidate.getKey();
                    queue = entry.getValue();
                    value = candidate.getValue();
                    break;
                }
            }
            if (reservedPlayer == null) return;
            EntityPlayerMP player = reservedPlayer;
            String key = reservedKey;
            if (!attempt(player, queue, key, value)) return;
            // Rotate successful/revoked bulk turns. Current traffic never loses its separate turn.
            reservedPlayer = null;
            reservedKey = null;
            if (PLAYERS.get(player) == queue) {
                PLAYERS.remove(player);
                PLAYERS.put(player, queue);
            }
        }
    }

    private static void send(EntityPlayerMP player, IMessage packet, int bytes, Runnable accepted) {
        if (packet instanceof NativePanelFrame) nativeSender.accept(player, ((NativePanelFrame) packet).packet());
        else sender.accept(player, packet);
        sentBytes += bytes;
        if (accepted != null) accepted.run();
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tick++;
        Iterator<Map.Entry<EntityPlayerMP, PlayerQueue>> iterator = PLAYERS.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            Map.Entry<EntityPlayerMP, PlayerQueue> entry = iterator.next();
            EntityPlayerMP player = entry.getKey();
            PlayerQueue queue = entry.getValue();
            if (MonitorCapabilities.connected(player) && queue.connection == player.playerNetServerHandler.netManager)
                continue;
            queuedBytes -= queue.bytes;
            bulkQueued -= queue.bulkCount;
            iterator.remove();
            if (reservedPlayer == player) {
                reservedPlayer = null;
                reservedKey = null;
            }
        }
        // Alternate first access to fresh credit: current progresses under continuous bulk, while a MAX-sized
        // bulk frame can still claim the full bucket (current-first on every tick would prevent that forever).
        if ((tick & 1) == 0) drainBulk();
        drainCurrent();
        if ((tick & 1) != 0) drainBulk();
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (reservedPlayer == event.player) {
            reservedPlayer = null;
            reservedKey = null;
        }
        PlayerQueue removed = PLAYERS.remove(event.player);
        if (removed != null) {
            queuedBytes -= removed.bytes;
            bulkQueued -= removed.bulkCount;
        }
    }

    public static long[] diagnostics() {
        long queued = 0;
        for (PlayerQueue queue : PLAYERS.values()) queued += queue.pending.size();
        return new long[] { sentBytes, controlBytes, coalesced, rejected, queued, queuedBytes };
    }

    public static void clear() {
        PLAYERS.clear();
        reservedPlayer = null;
        reservedKey = null;
        bulkQueued = 0;
        tick = sentBytes = controlBytes = coalesced = rejected = queuedBytes = 0;
        global = bucket(globalRate);
        currentShare = currentBucket(globalRate);
    }

    private static final class PlayerQueue {

        long bytes;
        int bulkCount;
        final Object connection;
        final TelemetryByteBudget budget = bucket(playerRate);
        final TelemetryByteBudget currentShare = currentBucket(playerRate);
        final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();

        PlayerQueue(Object connection) {
            this.connection = connection;
        }
    }

    private static final class Pending {

        final IMessage packet;
        final int bytes;
        final Runnable accepted;
        final boolean bulk;
        final java.util.function.BooleanSupplier authorized;

        Pending(IMessage packet, int bytes, java.util.function.BooleanSupplier authorized, Runnable accepted) {
            this.packet = packet;
            this.bytes = bytes;
            this.authorized = authorized;
            this.accepted = accepted;
            this.bulk = bytes > CURRENT_MAX_BYTES;
        }
    }
}
