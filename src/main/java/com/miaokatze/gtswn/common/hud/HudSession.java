package com.miaokatze.gtswn.common.hud;

/** Identity and request credentials shared with the Netty receiver through immutable snapshots. */
public final class HudSession {

    public static final class Snapshot {

        final Object world, player, connection;

        Snapshot(Object world, Object player, Object connection) {
            this.world = world;
            this.player = player;
            this.connection = connection;
        }

        boolean matches(Object world, Object player, Object connection) {
            return this.world == world && this.player == player && this.connection == connection;
        }
    }

    private volatile Snapshot current = new Snapshot(null, null, null);
    private long nextRequest;
    private long lastAcceptedRequest;

    public Snapshot capture(Object world, Object player, Object connection) {
        Snapshot snapshot = current;
        return world != null && player != null && connection != null && snapshot.matches(world, player, connection)
            ? snapshot
            : null;
    }

    boolean change(Object world, Object player, Object connection) {
        if (current.matches(world, player, connection)) return false;
        current = new Snapshot(world, player, connection);
        invalidateRequest();
        return true;
    }

    boolean sameConnection(Object connection) {
        return connection != null && current.connection == connection;
    }

    boolean ownsWorld(Object world) {
        return world != null && current.world == world;
    }

    Object connection() {
        return current.connection;
    }

    long request() {
        return ++nextRequest;
    }

    void invalidateRequest() {
        lastAcceptedRequest = nextRequest;
    }

    boolean consume(Snapshot snapshot, Object world, Object player, Object connection, long request) {
        if (snapshot == null || snapshot != current
            || !snapshot.matches(world, player, connection)
            || request <= lastAcceptedRequest
            || request > nextRequest) return false;
        lastAcceptedRequest = request;
        return true;
    }
}
