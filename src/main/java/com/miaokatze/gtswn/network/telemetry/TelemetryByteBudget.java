package com.miaokatze.gtswn.network.telemetry;

/** Deterministic token bucket; rates are application-encoded bytes per second (20 server ticks). */
public final class TelemetryByteBudget {

    private final long rate, capacity;
    private long tokens, lastTick, remainder;

    public TelemetryByteBudget(long rate, long capacity) {
        if (rate <= 0 || capacity <= 0) throw new IllegalArgumentException("Positive budget required");
        this.rate = rate;
        this.capacity = capacity;
        tokens = capacity;
    }

    public boolean available(int bytes, long tick) {
        if (tick < lastTick) {
            tokens = capacity;
            remainder = 0;
        }
        long elapsed = Math.max(0, tick - lastTick);
        long units = Math.min(elapsed, 1200L) * rate + remainder;
        tokens = Math.min(capacity, tokens + units / 20);
        remainder = units % 20;
        lastTick = tick;
        return bytes >= 0 && bytes <= tokens;
    }

    public void consume(int bytes) {
        if (bytes < 0 || bytes > tokens) throw new IllegalArgumentException("Unadmitted bytes");
        tokens -= bytes;
    }

    public long remaining() {
        return tokens;
    }
}
