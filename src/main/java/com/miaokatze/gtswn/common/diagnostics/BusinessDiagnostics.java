package com.miaokatze.gtswn.common.diagnostics;

import java.math.BigInteger;
import java.util.UUID;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import appeng.api.networking.IGridNode;

/** Passive observation port. No session, fixture ownership, authorization or business state. */
public final class BusinessDiagnostics {

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

    public interface Listener {

        void at(int dimension, int x, int y, int z, String type, String detail);

        void eu(UUID owner, BigInteger amount, boolean committed);

        void flow(Object controller, int channel, double amount);

        void aeConnectionAttempt(IGridNode first, IGridNode second);

        void deviceKey(String key, String type);

        long queueToken(World world, int x, int y, int z);

        void queue(long token, World world, int x, int y, int z, boolean executed);
    }

    private static volatile Listener listener;

    private BusinessDiagnostics() {}

    public static void install(Listener value) {
        listener = value;
    }

    public static void at(TileEntity tile, Type type, String detail) {
        if (tile != null) at(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord, type, detail);
    }

    public static void at(World world, int x, int y, int z, Type type, String detail) {
        if (world != null && !world.isRemote && world.provider != null)
            at(world.provider.dimensionId, x, y, z, type, detail);
    }

    public static void at(int dimension, int x, int y, int z, Type type, String detail) {
        Listener current = listener;
        if (current != null) try {
            current.at(dimension, x, y, z, type.name(), detail);
        } catch (RuntimeException | LinkageError ignored) {}
    }

    public static void eu(UUID owner, BigInteger amount, boolean committed) {
        Listener current = listener;
        if (current != null) try {
            current.eu(owner, amount, committed);
        } catch (RuntimeException | LinkageError ignored) {}
    }

    public static void flow(Object controller, int channel, double amount) {
        Listener current = listener;
        if (current != null) try {
            current.flow(controller, channel, amount);
        } catch (RuntimeException | LinkageError ignored) {}
    }

    public static void aeConnectionAttempt(IGridNode first, IGridNode second) {
        Listener current = listener;
        if (current != null) try {
            current.aeConnectionAttempt(first, second);
        } catch (RuntimeException | LinkageError ignored) {}
    }

    public static void deviceKey(String key, Type type) {
        Listener current = listener;
        if (current != null) try {
            current.deviceKey(key, type.name());
        } catch (RuntimeException | LinkageError ignored) {}
    }

    public static long queueToken(World world, int x, int y, int z) {
        Listener current = listener;
        if (current != null) try {
            return current.queueToken(world, x, y, z);
        } catch (RuntimeException | LinkageError ignored) {}
        return 0L;
    }

    public static void queue(long token, World world, int x, int y, int z, boolean executed) {
        Listener current = listener;
        if (current != null) try {
            current.queue(token, world, x, y, z, executed);
        } catch (RuntimeException | LinkageError ignored) {}
    }
}
