package com.miaokatze.gtswn.common.util;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Server-thread queue: payloads never retain a player or an inventory stack. */
final class DelayedPlayerMessages<T> {

    private final ArrayDeque<Entry<T>> pending = new ArrayDeque<>();
    private final int capacity;
    private long tick;

    DelayedPlayerMessages(int capacity) {
        this.capacity = capacity;
    }

    void schedule(UUID player, T payload, int delay) {
        // Discard the oldest unseen notice on pathological bursts; it consumes no hint count.
        if (pending.size() >= capacity) pending.removeFirst();
        pending.addLast(new Entry<>(player, payload, tick + delay));
    }

    void advance(BiConsumer<UUID, T> delivery) {
        tick++;
        Iterator<Entry<T>> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Entry<T> entry = iterator.next();
            if (entry.due > tick) continue;
            iterator.remove();
            delivery.accept(entry.player, entry.payload);
        }
    }

    void cancel(UUID player) {
        pending.removeIf(entry -> entry.player.equals(player));
    }

    void clear() {
        pending.clear();
        tick = 0;
    }

    private static final class Entry<T> {

        final UUID player;
        final T payload;
        final long due;

        Entry(UUID player, T payload, long due) {
            this.player = player;
            this.payload = payload;
            this.due = due;
        }
    }
}
