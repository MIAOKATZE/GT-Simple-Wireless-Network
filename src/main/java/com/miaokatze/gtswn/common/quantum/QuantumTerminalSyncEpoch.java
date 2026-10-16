package com.miaokatze.gtswn.common.quantum;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** A session token plus real source events; it never schedules work or enumerates players/items. */
public final class QuantumTerminalSyncEpoch {

    private static final AtomicLong EPOCH = new AtomicLong(sessionToken());

    private QuantumTerminalSyncEpoch() {}

    private static long sessionToken() {
        long token = UUID.randomUUID()
            .getMostSignificantBits();
        return token == 0 ? 1 : token;
    }

    public static long current() {
        return EPOCH.get();
    }

    /** Integrated servers may reopen saves in the same JVM; persisted items must pull once in that new session. */
    public static void beginSession() {
        long previous = EPOCH.get(), next;
        do {
            next = sessionToken();
        } while (next == previous);
        EPOCH.set(next);
    }

    public static void changed() {
        if (EPOCH.incrementAndGet() == 0) EPOCH.incrementAndGet();
    }
}
