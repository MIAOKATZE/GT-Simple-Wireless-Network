package com.miaokatze.gtswn.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;
import com.miaokatze.gtswn.network.telemetry.MonitorCapabilities;
import com.miaokatze.gtswn.network.telemetry.TelemetryTransport;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** ID46. Actual held inventory is inspected on the server; this protocol never accepts copied item NBT. */
public final class C2SPacketMonitorProfileAction implements IMessage {

    public static final int LIST = 0, IMPORT_HELD = 1, RECOVER = 2, SHARE = 3, REVOKE = 4, OFFLINE = 5, WEB = 6,
        RENAME = 7;
    public long requestId;
    public int action, page, interval = 20;
    public UUID profile = new UUID(0, 0), reader = new UUID(0, 0);
    public boolean enabled, valid;
    public String name = "";

    public C2SPacketMonitorProfileAction() {}

    public C2SPacketMonitorProfileAction(long requestId, int action, int page, UUID profile, UUID reader,
        boolean enabled, int interval) {
        this.requestId = requestId;
        this.action = action;
        this.page = page;
        if (profile != null) this.profile = profile;
        if (reader != null) this.reader = reader;
        this.enabled = enabled;
        this.interval = interval;
        valid = true;
    }

    public static C2SPacketMonitorProfileAction rename(long requestId, int page, UUID profile, String name) {
        C2SPacketMonitorProfileAction packet = new C2SPacketMonitorProfileAction(
            requestId,
            RENAME,
            page,
            profile,
            null,
            false,
            20);
        packet.name = com.miaokatze.gtswn.common.monitoring.MonitorProfile.normalizeName(name);
        return packet;
    }

    public void toBytes(ByteBuf out) {
        out.writeLong(requestId);
        out.writeByte(action);
        out.writeInt(page);
        S2CPacketMonitorProfiles.writeUuid(out, profile);
        S2CPacketMonitorProfiles.writeUuid(out, reader);
        out.writeBoolean(enabled);
        out.writeInt(interval);
        if (action == RENAME) S2CPacketMonitorProfiles.writeName(out, name);
    }

    public void fromBytes(ByteBuf in) {
        valid = false;
        try {
            if (in.readableBytes() < 50 || in.readableBytes() > 244) return;
            requestId = in.readLong();
            action = in.readUnsignedByte();
            page = in.readInt();
            profile = S2CPacketMonitorProfiles.readUuid(in);
            reader = S2CPacketMonitorProfiles.readUuid(in);
            enabled = in.readBoolean();
            interval = in.readInt();
            name = action == RENAME ? S2CPacketMonitorProfiles.readName(in) : "";
            valid = !in.isReadable() && requestId > 0
                && action <= RENAME
                && page >= 0
                && page <= MonitoringProfiles.MAX_PROFILES / S2CPacketMonitorProfiles.PAGE_SIZE
                && interval >= 20
                && interval <= 72000;
        } catch (RuntimeException malformed) {
            valid = false;
        }
    }

    public static final class Handler implements IMessageHandler<C2SPacketMonitorProfileAction, IMessage> {

        public IMessage onMessage(C2SPacketMonitorProfileAction packet, MessageContext context) {
            if (context.side.isServer() && packet.valid)
                ServerQueue.enqueue(context.getServerHandler().playerEntity, packet);
            return null;
        }
    }

    /** FIFO controls, bounded per-connection and globally. Duplicate IDs never execute a mutation twice. */
    static final class RequestIds {

        private long completed;

        boolean accept(long request) {
            if (request <= completed) return false;
            completed = request;
            return true;
        }
    }

    static final class Outcome {

        final long requestId;
        final int page;
        final boolean open, success;
        final String message;

        Outcome(long requestId, int page, boolean open, boolean success, String message) {
            this.requestId = requestId;
            this.page = page;
            this.open = open;
            this.success = success;
            this.message = message == null ? "" : message.substring(0, Math.min(160, message.length()));
        }
    }

    /** Immutable operation results, not private snapshots. A failed send cannot repeat a mutation. */
    static final class Results {

        private final RequestIds ids = new RequestIds();
        private final java.util.LinkedHashMap<Long, Outcome> recent = new java.util.LinkedHashMap<>();

        Outcome apply(C2SPacketMonitorProfileAction packet, Runnable mutation) {
            if (!ids.accept(packet.requestId)) {
                Outcome old = recent.get(packet.requestId);
                return old != null ? old
                    : new Outcome(
                        packet.requestId,
                        packet.page,
                        false,
                        false,
                        "Request result expired; no action repeated. Refresh current state.");
            }
            boolean success = false;
            String message;
            try {
                mutation.run();
                success = true;
                message = packet.action == LIST ? "Refreshed." : "Profile updated.";
            } catch (IllegalArgumentException denied) {
                message = denied.getMessage();
            } catch (RuntimeException unavailable) {
                message = "Profile action unavailable; refresh before trying a new action.";
            }
            Outcome result = new Outcome(
                packet.requestId,
                packet.action == IMPORT_HELD ? MonitoringProfiles.MAX_PROFILES / S2CPacketMonitorProfiles.PAGE_SIZE
                    : packet.page,
                false,
                success,
                message);
            recent.put(packet.requestId, result);
            while (recent.size() > 16) recent.remove(
                recent.keySet()
                    .iterator()
                    .next());
            return result;
        }
    }

    /** Retains one ordered response until the common telemetry byte budget admits it. */
    static final class Delivery {

        Outcome response;

        boolean flush(java.util.function.Predicate<Outcome> admit) {
            if (response == null) return true;
            if (!admit.test(response)) return false;
            response = null;
            return true;
        }
    }

    /** Import/recovery target the real item present at receipt, not a later item selected during backpressure. */
    static final class Operation {

        final C2SPacketMonitorProfileAction packet;
        private final HeldTerminalReceipt receipt;

        Operation(EntityPlayerMP player, C2SPacketMonitorProfileAction packet) {
            this.packet = packet;
            receipt = packet.action == IMPORT_HELD || packet.action == RECOVER ? new HeldTerminalReceipt(player) : null;
        }

        void requireCurrentHeld(EntityPlayerMP player) {
            if (receipt != null && !receipt.matches(player))
                throw new IllegalArgumentException("Held terminal changed; retry with the intended terminal.");
        }
    }

    public static final class ServerQueue {

        private static final int MAX_PLAYERS = 128, MAX_PENDING = 16;
        private static final Map<EntityPlayerMP, Pending> PLAYERS = new IdentityHashMap<>();
        private static int cursor;

        private static final class Pending {

            final Object connection;
            final ArrayDeque<Operation> queue = new ArrayDeque<>();
            final Results results = new Results();
            final Delivery delivery = new Delivery();
            boolean openRequested;

            Pending(Object connection) {
                this.connection = connection;
            }
        }

        static synchronized void enqueue(EntityPlayerMP player, C2SPacketMonitorProfileAction packet) {
            if (!MonitorCapabilities.serverSupports(player, MonitorCapabilities.PROFILES)) return;
            Object connection = player.playerNetServerHandler.netManager;
            Pending pending = PLAYERS.get(player);
            if (pending == null || pending.connection != connection) {
                if (pending == null && PLAYERS.size() >= MAX_PLAYERS) return;
                pending = new Pending(connection);
                PLAYERS.put(player, pending);
            }
            if (pending.queue.size() < MAX_PENDING) pending.queue.addLast(new Operation(player, packet));
        }

        public static synchronized void open(EntityPlayerMP player) {
            if (!MonitorCapabilities.serverSupports(player, MonitorCapabilities.PROFILES)) {
                player.addChatMessage(
                    new net.minecraft.util.ChatComponentText(
                        "Profile UI needs a compatible client; retry after connection negotiation."));
                return;
            }
            Object connection = player.playerNetServerHandler.netManager;
            Pending pending = PLAYERS.get(player);
            if (pending == null || pending.connection != connection) {
                if (pending == null && PLAYERS.size() >= MAX_PLAYERS) return;
                pending = new Pending(connection);
                PLAYERS.put(player, pending);
            }
            pending.openRequested = true; // Opening is a coalescible read, not a mutation.
        }

        private static boolean flush(EntityPlayerMP player, Pending pending) {
            // Rebuild visible metadata from the current actor ACL on each actual send attempt.
            // Nothing is sent through control(): full pages consume the configured telemetry budgets.
            return pending.delivery.flush(
                result -> TelemetryTransport.trySend(
                    player,
                    S2CPacketMonitorProfiles
                        .snapshot(player, result.requestId, result.page, result.open, result.success, result.message)));
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            synchronized (ServerQueue.class) {
                List<EntityPlayerMP> players = new ArrayList<>(PLAYERS.keySet());
                if (players.isEmpty()) return;
                int count = players.size(), processed = 0;
                // At most 32 controls per server tick, with rotating fairness between connections.
                for (int visit = 0; visit < count && processed < 32; visit++) {
                    EntityPlayerMP player = players.get(Math.floorMod(cursor++, count));
                    Pending pending = PLAYERS.get(player);
                    if (pending == null) continue;
                    if (!MonitorCapabilities.connected(player)
                        || player.playerNetServerHandler.netManager != pending.connection) {
                        PLAYERS.remove(player);
                        continue;
                    }
                    if (!MonitorCapabilities.serverSupports(player, MonitorCapabilities.PROFILES)) continue;
                    if (pending.delivery.response != null) {
                        processed++;
                        flush(player, pending);
                        continue;
                    }
                    if (pending.openRequested) {
                        processed++;
                        pending.openRequested = false;
                        pending.delivery.response = new Outcome(
                            0,
                            0,
                            true,
                            true,
                            "Import the real held terminal; items are not consumed.");
                        flush(player, pending);
                        continue;
                    }
                    if (pending.queue.isEmpty()) continue;
                    processed++;
                    Operation operation = pending.queue.removeFirst();
                    pending.delivery.response = pending.results.apply(operation.packet, () -> {
                        operation.requireCurrentHeld(player);
                        execute(player, operation.packet);
                    });
                    flush(player, pending);
                }
            }
        }

        private static void execute(EntityPlayerMP player, C2SPacketMonitorProfileAction packet) {
            MonitoringProfiles store = MonitoringProfiles.get(player.worldObj);
            UUID actor = player.getUniqueID();
            switch (packet.action) {
                case LIST:
                    break;
                case IMPORT_HELD:
                    store.importHeld(player);
                    break;
                case RECOVER:
                    store.recover(player, packet.profile);
                    break;
                case SHARE:
                    store.share(actor, packet.profile, packet.reader);
                    break;
                case REVOKE:
                    store.revoke(actor, packet.profile, packet.reader);
                    break;
                case OFFLINE:
                    store.setOffline(actor, packet.profile, packet.enabled, packet.interval);
                    break;
                case WEB:
                    store.setWebEnabled(actor, packet.profile, packet.enabled);
                    break;
                case RENAME:
                    store.rename(actor, packet.profile, packet.name);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown action");
            }
        }

        @SubscribeEvent
        public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            synchronized (ServerQueue.class) {
                PLAYERS.remove(event.player);
            }
        }

        public static synchronized void clear() {
            PLAYERS.clear();
            cursor = 0;
        }
    }
}
