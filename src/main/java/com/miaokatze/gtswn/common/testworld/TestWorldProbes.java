package com.miaokatze.gtswn.common.testworld;

import java.math.BigInteger;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.quantum.QuantumNodeBridgeRelay;
import com.miaokatze.gtswn.common.util.DebugTestGate;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/**
 * Opt-in observation of real business transitions. No balances, names, terminal IDs or player IDs
 * are retained. The runner owns the bounded sink and must enroll fixture positions and synthetic EU
 * owners before invoking business code. Hooks never decide whether an operation is allowed.
 */
public final class TestWorldProbes {

    public enum Type {
        EU_COMMIT,
        EU_REJECT,
        EU_DOWNLINK,
        EU_UPLINK,
        WIRELESS_NODE_ADD,
        WIRELESS_NODE_REMOVE,
        WIRELESS_NODE_PRUNE,
        DEVICE_REGISTER,
        DEVICE_REMOVE,
        DEVICE_FLOW,
        PANEL_REBUILD,
        PANEL_SAMPLE,
        PANEL_EU_SAMPLE,
        QUANTUM_CONTROLLER_ADD,
        QUANTUM_CONTROLLER_REMOVE,
        QUANTUM_NODE_ANCHOR,
        QUANTUM_NODE_CONNECT,
        QUANTUM_NODE_DISCONNECT,
        QUANTUM_NODE_REJECT,
        QUANTUM_MIGRATE,
        QUANTUM_MIGRATE_REJECT,
        INCORPORATION_ADD,
        INCORPORATION_REMOVE,
        INCORPORATION_CONNECT,
        INCORPORATION_DISCONNECT,
        INCORPORATION_REJECT,
        AE_CONNECTION_ATTEMPT,
        QUEUE_ENQUEUE,
        QUEUE_EXECUTE
    }

    public interface Sink {

        void record(String type, String detail);
    }

    private static final Object LOCK = new Object();
    private static volatile boolean active;
    private static long generation;
    /** Process-lifetime accepted native gate observations; deliberately survives start/stop/reset. */
    private static long nativeAeConnectionAttempts;
    private static Session session;

    private static final class Session {

        final Sink sink;
        final int dimension;
        final long token;
        final Set<String> positions = new HashSet<>();
        final Set<UUID> owners = new HashSet<>();
        final EnumMap<Type, Long> counts = new EnumMap<>(Type.class);

        Session(Sink sink, int dimension, long token) {
            this.sink = sink;
            this.dimension = dimension;
            this.token = token;
        }
    }

    private TestWorldProbes() {}

    /** Called by the server-side test runner only, once per scenario. */
    public static void start(Sink sink, int dimension) {
        if (!DebugTestGate.isEnabled()) return;
        if (sink == null) throw new IllegalArgumentException("Missing test probe sink");
        synchronized (LOCK) {
            active = false;
            DebugTestGate.setProbeSessionActive(false);
            session = new Session(sink, dimension, ++generation);
            active = true;
            DebugTestGate.setProbeSessionActive(true);
        }
    }

    public static void stop() {
        DebugTestGate.setProbeSessionActive(false);
        synchronized (LOCK) {
            active = false;
            session = null;
        }
    }

    public static boolean enabled() {
        return DebugTestGate.probesActive();
    }

    public static void addPosition(int x, int y, int z) {
        if (!active) return;
        synchronized (LOCK) {
            if (session != null) session.positions.add(position(x, y, z));
        }
    }

    /** Enroll a fresh, independent test UUID, never the command sender's UUID. */
    public static void addOwner(UUID owner) {
        if (!active) return;
        synchronized (LOCK) {
            if (session != null && owner != null) session.owners.add(owner);
        }
    }

    /** Monotonic within this scenario, independent of report-buffer truncation. */
    public static long count(Type type) {
        if (!active) return 0L;
        synchronized (LOCK) {
            return session == null ? 0L : session.counts.getOrDefault(type, 0L);
        }
    }

    /** Includes zero observations so a report distinguishes an installed hook from exercised coverage. */
    public static Map<Type, Long> counts() {
        synchronized (LOCK) {
            EnumMap<Type, Long> result = new EnumMap<>(Type.class);
            for (Type type : Type.values())
                result.put(type, session == null ? 0L : session.counts.getOrDefault(type, 0L));
            return Collections.unmodifiableMap(result);
        }
    }

    /** Only accepted, enrolled fixture endpoints contribute; session transitions never reset this counter. */
    public static long nativeAeConnectionAttempts() {
        synchronized (LOCK) {
            return nativeAeConnectionAttempts;
        }
    }

    /**
     * One GridConnection construction reaching its native security gate, before its verdict is known.
     * Constructors failing validation before that gate are not observed. Either endpoint may establish
     * fixture provenance; both enrolled endpoints still count once. No world/chunk/grid lookup occurs.
     */
    public static void aeConnectionAttempt(IGridNode first, IGridNode second) {
        if (!active) return;
        synchronized (LOCK) {
            Session scope = session;
            TileEntity tile = acceptedEndpoint(scope, first);
            if (tile == null) tile = acceptedEndpoint(scope, second);
            if (tile == null) return;
            publish(
                scope,
                Type.AE_CONNECTION_ATTEMPT,
                "dim=" + tile.getWorldObj().provider.dimensionId
                    + ";pos="
                    + position(tile.xCoord, tile.yCoord, tile.zCoord)
                    + ";stage=native_security_gate");
        }
    }

    private static TileEntity acceptedEndpoint(Session scope, IGridNode node) {
        if (scope == null || node == null) return null;
        IGridHost host = node.getMachine();
        TileEntity tile = host instanceof QuantumNodeBridgeRelay ? ((QuantumNodeBridgeRelay) host).owner()
            : host instanceof TileEntity ? (TileEntity) host : null;
        World world = tile == null ? null : tile.getWorldObj();
        return world != null && !world.isRemote
            && TestDimension.isTest(world)
            && accepts(scope, world.provider.dimensionId, tile.xCoord, tile.yCoord, tile.zCoord) ? tile : null;
    }

    public static void at(TileEntity tile, Type type, String detail) {
        if (!active) return;
        if (tile != null) at(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord, type, detail);
    }

    public static void at(World world, int x, int y, int z, Type type, String detail) {
        if (!active) return;
        if (world == null || world.isRemote || world.provider == null) return;
        at(world.provider.dimensionId, x, y, z, type, detail);
    }

    public static void at(int dimension, int x, int y, int z, Type type, String detail) {
        if (!active) return;
        synchronized (LOCK) {
            Session scope = session;
            if (!accepts(scope, dimension, x, y, z)) return;
            publish(scope, type, "dim=" + dimension + ";pos=" + position(x, y, z) + ";" + detail);
        }
    }

    /** Global GT transactions lack world arguments and require an explicitly enrolled synthetic owner. */
    public static void eu(UUID owner, BigInteger amount, boolean committed) {
        if (!active) return;
        synchronized (LOCK) {
            Session scope = session;
            if (scope == null || owner == null || !scope.owners.contains(owner) || amount == null) return;
            publish(scope, committed ? Type.EU_COMMIT : Type.EU_REJECT, "signedEU=" + amount);
        }
    }

    /** Resolve only the actual monitored controller, never a storage owner or player identity. */
    public static void flow(Object controller, int channel, double amount) {
        if (!active) return;
        Object tile = controller instanceof IMetaTileEntity ? ((IMetaTileEntity) controller).getBaseMetaTileEntity()
            : controller;
        if (tile instanceof TileEntity)
            at((TileEntity) tile, Type.DEVICE_FLOW, "channel=" + channel + ";amount=" + amount);
    }

    /** Registry keys contain only dimension and block coordinates. Bad keys are ignored by observation. */
    public static void deviceKey(String key, Type type) {
        if (!active) return;
        if (key == null) return;
        String[] parts = key.split(":", -1);
        if (parts.length != 4) return;
        try {
            at(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]),
                type,
                "");
        } catch (NumberFormatException ignored) {
            // Business code owns validation; tracing cannot reject or alter a registry mutation.
        }
    }

    /** Capture at enqueue time; execution in another run/scenario cannot inherit its provenance. */
    public static long queueToken(World world, int x, int y, int z) {
        if (!active) return 0L;
        if (world == null || world.isRemote || world.provider == null) return 0L;
        return queueToken(world.provider.dimensionId, x, y, z);
    }

    static long queueToken(int dimension, int x, int y, int z) {
        if (!active) return 0L;
        synchronized (LOCK) {
            return accepts(session, dimension, x, y, z) ? session.token : 0L;
        }
    }

    public static void queue(long token, World world, int x, int y, int z, boolean executed) {
        if (!active) return;
        if (token == 0L || world == null || world.isRemote || world.provider == null) return;
        queue(token, world.provider.dimensionId, x, y, z, executed);
    }

    static void queue(long token, int dimension, int x, int y, int z, boolean executed) {
        if (!active) return;
        if (token == 0L) return;
        synchronized (LOCK) {
            Session scope = session;
            if (scope == null || scope.token != token || !accepts(scope, dimension, x, y, z)) return;
            publish(
                scope,
                executed ? Type.QUEUE_EXECUTE : Type.QUEUE_ENQUEUE,
                "queue=panel;dim=" + scope.dimension + ";pos=" + position(x, y, z));
        }
    }

    private static boolean accepts(Session scope, int dimension, int x, int y, int z) {
        return scope != null && scope.dimension == dimension && scope.positions.contains(position(x, y, z));
    }

    private static String position(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    private static void publish(Session scope, Type type, String detail) {
        scope.counts.put(type, scope.counts.getOrDefault(type, 0L) + 1L);
        if (type == Type.AE_CONNECTION_ATTEMPT) nativeAeConnectionAttempts++;
        try {
            scope.sink.record(type.name(), detail);
        } catch (RuntimeException ignored) {
            // Diagnostics must never change transaction success, queue draining or tile lifecycle.
        }
    }
}
