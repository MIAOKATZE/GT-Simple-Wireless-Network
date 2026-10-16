package com.miaokatze.gtswn.common.quantum;

import java.util.List;

import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;

/** Exact native list membership facts, independent of pathing orientation and security authorization. */
public final class QuantumConnectionMembership {

    /** Native nodes whose actual list mutations emit the membership facts below. */
    public interface TrackedNode {
    }

    public interface TrackedConnection {

        void gtswn$attached(IGridNode node);

        void gtswn$detached(IGridNode node);

        boolean gtswn$contains(IGridNode node);
    }

    private QuantumConnectionMembership() {}

    public static boolean contains(IGridNode node, IGridConnection connection) {
        if (node == null) return false;
        return node instanceof TrackedNode && connection instanceof TrackedConnection
            ? ((TrackedConnection) connection).gtswn$contains(node)
            : node.getConnections()
                .contains(connection);
    }

    /** Preserve the original list result/exception; publish membership before the native notification. */
    public static boolean add(List<IGridConnection> connections, IGridConnection connection, IGridNode node) {
        boolean changed = connections.add(connection);
        if (changed && connection instanceof TrackedConnection) ((TrackedConnection) connection).gtswn$attached(node);
        return changed;
    }

    public static boolean remove(List<IGridConnection> connections, IGridConnection connection, IGridNode node) {
        boolean changed = connections.remove(connection);
        if (changed && connection instanceof TrackedConnection) ((TrackedConnection) connection).gtswn$detached(node);
        return changed;
    }
}
