package com.miaokatze.gtswn.client;

/** Client input lifecycle, independent of server authorization and elapsed server time. */
final class QuantumMigrationGesture {

    enum Step {
        IDLE,
        BEGIN,
        HOLD,
        COMPLETE
    }

    private boolean pressed;
    private boolean active;
    private long session;
    private int ticks;

    boolean press(long id) {
        if (pressed) return false;
        pressed = true;
        active = true;
        session = id;
        ticks = 0;
        return true;
    }

    Step tick() {
        if (!active) return Step.IDLE;
        if (ticks++ == 0) return Step.BEGIN;
        return ticks == 101 ? Step.COMPLETE : Step.HOLD;
    }

    boolean acknowledge(long id) {
        if (!active || session != id) return false;
        cancel();
        return true;
    }

    void cancel() {
        active = false;
    }

    void release() {
        cancel();
        pressed = false;
    }

    long session() {
        return session;
    }

    int ticks() {
        return ticks;
    }
}
