package com.miaokatze.gtswn.network;

/** A player's repeat-scan window, scoped to the exact world instance and wall-clock expiry. */
final class LinkNodeRevealSession {

    private final Object world;
    private final long expiresAt;

    LinkNodeRevealSession(Object world, long expiresAt) {
        this.world = world;
        this.expiresAt = expiresAt;
    }

    boolean isActive(Object currentWorld, long now) {
        return currentWorld == world && now < expiresAt;
    }

    static byte visibleTypes(byte actualTypes, byte wantedType, boolean revealAll) {
        return revealAll ? actualTypes : (byte) (actualTypes & (1 << wantedType));
    }
}
