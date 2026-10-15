package com.miaokatze.gtswn.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Server-owned monotonic cooldown; UUIDs survive logout and dimension changes until the interval expires. */
final class QuantumMigrationAnnouncements {

    static final long INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(3);
    private final Map<UUID, Long> announced = new HashMap<>();

    boolean reserve(UUID player, long now) {
        Long previous = announced.get(player);
        if (previous != null && now - previous < INTERVAL_NANOS) return false;
        announced.put(player, now);
        return true;
    }

    void expire(long now) {
        announced.entrySet()
            .removeIf(entry -> now - entry.getValue() >= INTERVAL_NANOS);
    }

    void clear() {
        announced.clear();
    }
}
