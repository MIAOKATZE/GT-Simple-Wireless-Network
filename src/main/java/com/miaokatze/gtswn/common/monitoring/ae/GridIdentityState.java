package com.miaokatze.gtswn.common.monitoring.ae;

import java.util.UUID;
import java.util.function.Consumer;

/** Constant-size identity state. An origin is shared only by native storage transfers, not by coordinates. */
final class GridIdentityState {

    static final class Origin {

        final UUID id;
        int liveGrids;
        int unsettledGrids;
        boolean retired;
        private Consumer<UUID> retirement;

        Origin(UUID id) {
            this.id = id;
        }

        void persistRetirement(Consumer<UUID> listener) {
            retirement = listener;
            if (retired && id != null) listener.accept(id);
        }

        void retire() {
            if (retired) return;
            retired = true;
            if (id != null && retirement != null) retirement.accept(id);
        }
    }

    private Origin origin;
    private int nodes;
    private boolean quiet;
    private boolean ready;
    private boolean published;
    private boolean stable;

    void join(Origin incoming) {
        changed();
        if (incoming == null || incoming == origin) return;
        if (origin == null) {
            attach(incoming);
        } else if (origin.id == null && !origin.retired) {
            attach(incoming); // A new, never-published cable grid adds no prior authorization.
        } else if (incoming.id != null || incoming.retired) {
            origin.retire();
            incoming.retire();
            attach(new Origin(null));
        }
    }

    Origin transfer() {
        if (origin == null) attach(new Origin(null));
        return origin;
    }

    Origin split() {
        // A weakly retained empty native grid can transfer storage through onSplit after an unload.
        // Only a donor with surviving nodes is an immediate published split boundary.
        if (published && nodes > 0 && origin != null) origin.retire();
        changed();
        return transfer();
    }

    void invalidate() {
        if (origin != null) origin.retire();
        changed();
    }

    void addNode() {
        if (nodes++ == 0 && origin != null) {
            origin.liveGrids++;
            if (!stable) origin.unsettledGrids++;
        }
        changed();
    }

    void removeNode() {
        if (nodes == 1 && origin != null) {
            origin.liveGrids--;
            if (!stable) origin.unsettledGrids--;
        }
        if (nodes > 0) nodes--;
        changed();
    }

    void update(boolean booting) {
        if (booting || nodes == 0) {
            changed();
            return;
        }
        if (!quiet) {
            quiet = true;
            return;
        }
        setStable(true);
        if (origin != null && origin.unsettledGrids > 0) return;
        if (origin != null && origin.liveGrids > 1) origin.retire();
        if (origin == null || origin.retired || origin.id == null) attach(new Origin(UUID.randomUUID()));
        ready = true;
        published = true;
    }

    UUID current(boolean booting) {
        return !booting && ready
            && origin != null
            && !origin.retired
            && origin.liveGrids == 1
            && origin.unsettledGrids == 0 ? origin.id : null;
    }

    UUID saved() {
        // Saving uncertain topology must not resurrect old consent after a restart.
        return origin != null && !origin.retired && origin.liveGrids <= 1 ? origin.id : null;
    }

    private void changed() {
        quiet = false;
        ready = false;
        setStable(false);
    }

    private void setStable(boolean value) {
        if (stable == value) return;
        if (nodes > 0 && origin != null) origin.unsettledGrids += value ? -1 : 1;
        stable = value;
    }

    private void attach(Origin next) {
        if (nodes > 0 && origin != null) {
            origin.liveGrids--;
            if (!stable) origin.unsettledGrids--;
        }
        origin = next;
        published = false;
        if (nodes > 0) {
            origin.liveGrids++;
            if (!stable) origin.unsettledGrids++;
        }
    }
}
