package com.miaokatze.gtswn.common.panel;

/** Short observation lease. World replacement and clock rollback cannot keep an old lease alive. */
final class AEMonitorInterest {

    static final long GRACE_TICKS = 100;
    private Object world;
    private long observedTick = Long.MIN_VALUE;
    private long until = Long.MIN_VALUE;

    void renew(Object currentWorld, long tick) {
        renew(currentWorld, tick, GRACE_TICKS);
    }

    void renew(Object currentWorld, long tick, long duration) {
        observe(currentWorld, tick);
        long bounded = Math.max(0L, Math.min(1200L, duration));
        long renewed = tick > Long.MAX_VALUE - bounded ? Long.MAX_VALUE : tick + bounded;
        until = Math.max(until, renewed);
    }

    boolean active(Object currentWorld, long tick) {
        observe(currentWorld, tick);
        return currentWorld != null && tick <= until;
    }

    private void observe(Object currentWorld, long tick) {
        if (world != currentWorld || tick < observedTick) until = Long.MIN_VALUE;
        world = currentWorld;
        observedTick = tick;
    }
}
