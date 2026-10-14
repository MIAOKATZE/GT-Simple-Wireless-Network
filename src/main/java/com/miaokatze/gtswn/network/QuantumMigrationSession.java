package com.miaokatze.gtswn.network;

/** Server-clock credential. Client completion is only intent, never elapsed time. */
final class QuantumMigrationSession {

    static final int REQUIRED_TICKS = 100;
    static final int HEARTBEAT_TIMEOUT = 10;
    private final long started;
    private long lastValidated;
    private long heartbeat;
    private boolean held;
    private boolean complete;
    private boolean canceled;

    QuantumMigrationSession(long tick) {
        started = tick;
        lastValidated = tick;
        heartbeat = tick;
    }

    void heartbeat(long tick) {
        if (!canceled) {
            heartbeat = tick;
            held = true;
        }
    }

    void complete() {
        complete = true;
    }

    void cancel() {
        canceled = true;
    }

    boolean validate(long tick, boolean valid) {
        if (!valid || tick < lastValidated || tick - lastValidated > 1 || tick - heartbeat > HEARTBEAT_TIMEOUT)
            cancel();
        lastValidated = tick;
        return !canceled;
    }

    boolean consume(long tick) {
        if (canceled || !held || !complete || lastValidated != tick || tick - started < REQUIRED_TICKS) return false;
        canceled = true;
        return true;
    }
}
