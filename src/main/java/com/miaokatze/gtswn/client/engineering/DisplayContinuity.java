package com.miaokatze.gtswn.client.engineering;

/**
 * One bounded, memory-only historical display. It is deliberately not a transport baseline:
 * opening/closing drops receipt authority without manufacturing a new value or timestamp.
 */
public final class DisplayContinuity<T> {

    private Object scope;
    private T value;
    private long received;
    private boolean activeReceipt;

    public void open(Object currentScope) {
        validate(currentScope);
        scope = currentScope;
        activeReceipt = false;
    }

    public void receive(Object currentScope, T authorizedValue, long now) {
        if (currentScope == null) {
            clear();
            return;
        }
        validate(currentScope);
        scope = currentScope;
        value = authorizedValue;
        received = now;
        activeReceipt = authorizedValue != null;
    }

    public T display(Object currentScope) {
        validate(currentScope);
        return value;
    }

    public void close() {
        activeReceipt = false;
    }

    public void clear() {
        scope = null;
        value = null;
        received = 0;
        activeReceipt = false;
    }

    public boolean live(Object currentScope, long now, long freshness, boolean paused) {
        return display(currentScope) != null && activeReceipt
            && !paused
            && now >= received
            && now - received < freshness;
    }

    public long receivedAt() {
        return received;
    }

    public String status(Object currentScope, long now, long freshness, boolean paused) {
        if (display(currentScope) == null) return "waiting";
        if (paused) return "paused";
        return live(currentScope, now, freshness, false) ? "live" : "stale";
    }

    private void validate(Object currentScope) {
        if (currentScope == null || scope != null && !scope.equals(currentScope)) clear();
    }

    /** Always-on lifecycle guard, independent of whether a dashboard is open or rendering. */
    public static final class Session {

        private Object world, player, connection;

        public boolean update(Object currentWorld, Object currentPlayer, Object currentConnection) {
            boolean changed = world != currentWorld || player != currentPlayer || connection != currentConnection;
            world = currentWorld;
            player = currentPlayer;
            connection = currentConnection;
            return changed;
        }

        public boolean ownsWorld(Object value) {
            return world != null && world == value;
        }

        public boolean ownsConnection(Object value) {
            return connection != null && connection == value;
        }

        public void clear() {
            world = player = connection = null;
        }
    }

}
