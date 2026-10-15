package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class QuantumMigrationAnnouncementsTest {

    private static final UUID PLAYER = new UUID(1, 2);
    private static final long THREE_MINUTES = TimeUnit.MINUTES.toNanos(3);

    @Test
    public void repeatedStartsCannotAnnounceAgainBeforeExactlyThreeMinutes() {
        QuantumMigrationAnnouncements announcements = new QuantumMigrationAnnouncements();
        assertTrue(announcements.reserve(PLAYER, 0));
        for (int seconds = 1; seconds < 180; seconds++) {
            long now = TimeUnit.SECONDS.toNanos(seconds);
            announcements.expire(now);
            assertFalse(announcements.reserve(PLAYER, now));
        }
        assertFalse(announcements.reserve(PLAYER, THREE_MINUTES - 1));
        assertTrue(announcements.reserve(PLAYER, THREE_MINUTES));
        assertFalse(announcements.reserve(PLAYER, THREE_MINUTES + 1));
    }

    @Test
    public void uuidCooldownIsIndependentOfSessionDimensionAndOtherPlayers() {
        QuantumMigrationAnnouncements announcements = new QuantumMigrationAnnouncements();
        assertTrue(announcements.reserve(PLAYER, 42));
        // A new player/session object with the same UUID keeps the original cooldown.
        assertFalse(announcements.reserve(new UUID(1, 2), 43));
        assertTrue(announcements.reserve(new UUID(3, 4), 43));
        announcements.expire(THREE_MINUTES + 42);
        assertTrue(announcements.reserve(PLAYER, THREE_MINUTES + 42));
        assertFalse(announcements.reserve(new UUID(3, 4), THREE_MINUTES + 42));
    }

    @Test
    public void monotonicClockWrapAndServerLifecycleCleanupAreSupported() {
        QuantumMigrationAnnouncements announcements = new QuantumMigrationAnnouncements();
        long start = Long.MAX_VALUE - THREE_MINUTES / 2;
        assertTrue(announcements.reserve(PLAYER, start));
        assertFalse(announcements.reserve(PLAYER, start + THREE_MINUTES - 1));
        announcements.expire(start + THREE_MINUTES);
        assertTrue(announcements.reserve(PLAYER, start + THREE_MINUTES));
        announcements.clear();
        assertTrue(announcements.reserve(PLAYER, 0));
    }
}
