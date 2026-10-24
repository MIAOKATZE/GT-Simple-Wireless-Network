package com.miaokatze.gtswn.client.engineering;

import java.util.Objects;
import java.util.UUID;

/** Mod-owned, once-per-player discovery. World, dimension and terminal source never identify completion. */
final class FactoryDashboardHintState {

    private UUID player;
    private boolean completed;

    void open(UUID nextPlayer, boolean persisted) {
        Objects.requireNonNull(nextPlayer, "player");
        if (nextPlayer.equals(player)) return;
        player = nextPlayer;
        completed = persisted;
    }

    boolean visible() {
        return player != null && !completed;
    }

    boolean acknowledge(UUID currentPlayer) {
        if (!visible() || !player.equals(currentPlayer)) return false;
        completed = true;
        return true;
    }

    void close() {
        player = null;
        completed = false;
    }
}
