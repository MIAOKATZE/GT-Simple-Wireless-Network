// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.quantum;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;

/** Main-thread explicit-action feedback. Never used by polling or as an authorization predicate. */
public final class QuantumSecurityFeedback {

    private static final Map<String, Long> SENT = new LinkedHashMap<>();
    private static final long COOLDOWN_NANOS = 2_000_000_000L;

    private QuantumSecurityFeedback() {}

    public static void send(EntityPlayer player, String key, int dim, int x, int y, int z) {
        if (allow(player.getUniqueID() + ":" + dim + ":" + x + ":" + y + ":" + z, key, System.nanoTime()))
            player.addChatMessage(new ChatComponentTranslation(key));
    }

    static boolean allow(String target, String key, long now) {
        String identity = target + ":" + key;
        Long previous = SENT.get(identity);
        if (previous != null && now - previous >= 0 && now - previous < COOLDOWN_NANOS) return false;
        SENT.remove(identity);
        if (SENT.size() >= 512) SENT.remove(
            SENT.keySet()
                .iterator()
                .next());
        SENT.put(identity, now);
        return true;
    }
}
