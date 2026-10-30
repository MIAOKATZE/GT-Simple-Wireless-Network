package com.miaokatze.gtswn.network;

import java.util.Map;

import com.miaokatze.gtswn.common.covers.WirelessNodeIndexCodec;

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

    /** Filter valid nodes before nearest-node truncation, without suppressing stale-index pruning. */
    static WirelessNodeIndexCodec.Probe visibleProbe(WirelessNodeIndexCodec.Probe probe, Map<Long, Byte> actualTypes,
        byte wantedType, boolean revealAll) {
        return (packed, x, y, z, type) -> {
            WirelessNodeIndexCodec.ProbeResult result = probe.probe(packed, x, y, z, type);
            if (result == WirelessNodeIndexCodec.ProbeResult.VALID
                && visibleTypes(actualTypes.get(packed), wantedType, revealAll) == 0) {
                return WirelessNodeIndexCodec.ProbeResult.UNLOADED;
            }
            return result;
        };
    }

    static byte visibleTypes(byte actualTypes, byte wantedType, boolean revealAll) {
        return revealAll ? actualTypes : (byte) (actualTypes & (1 << wantedType));
    }
}
