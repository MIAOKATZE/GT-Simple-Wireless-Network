package com.miaokatze.gtswn.client.render;

import java.util.LinkedHashMap;
import java.util.Map;

/** Transient client events, never inferred from loading a cover or persisted in NBT. */
public final class LinkNodeInstallAnimation {

    public static final int DURATION_TICKS = 48;
    public static final int WAIT_TICKS = 100;
    public static final int LIMIT = 256;
    private final Map<String, Event> events = new LinkedHashMap<>();
    private final Map<String, Long> latest = new LinkedHashMap<>();

    public boolean accept(String key, long nonce, long tick, boolean energy) {
        Long previous = latest.get(key);
        if (previous != null && nonce <= previous) return false;
        latest.put(key, nonce);
        trim(latest);
        events.put(key, new Event(tick, energy));
        trim(events);
        return true;
    }

    /** A matching cover starts the clock; later sync replacements keep its elapsed time. */
    public void resolve(String key, Object cover, boolean energy, long tick) {
        Event event = events.get(key);
        if (event == null || cover == null || energy != event.energy) return;
        event.cover = cover;
        if (event.start < 0) event.start = tick;
    }

    public double elapsed(String key, Object cover, long tick, float partial) {
        Event event = events.get(key);
        if (event == null) return DURATION_TICKS;
        if (event.start < 0 || event.cover != cover) return 0;
        return Math.max(0, tick - event.start + partial);
    }

    public void prune(long tick) {
        events.values()
            .removeIf(
                event -> event.start < 0 ? tick - event.received >= WAIT_TICKS : tick - event.start >= DURATION_TICKS);
    }

    public boolean contains(String key) {
        return events.containsKey(key);
    }

    public void clear() {
        events.clear();
        latest.clear();
    }

    public static double alpha(int ring, double elapsedTicks) {
        double t = Math.max(0, Math.min(1, (elapsedTicks - ring * 10) / 18));
        return t * t * (3 - 2 * t);
    }

    public static String key(int x, int y, int z, int side) {
        return x + ":" + y + ":" + z + ":" + side;
    }

    private static <T> void trim(Map<String, T> map) {
        if (map.size() > LIMIT) map.remove(
            map.keySet()
                .iterator()
                .next());
    }

    private static final class Event {

        private final long received;
        private final boolean energy;
        private long start = -1;
        private Object cover;

        private Event(long received, boolean energy) {
            this.received = received;
            this.energy = energy;
        }
    }
}
