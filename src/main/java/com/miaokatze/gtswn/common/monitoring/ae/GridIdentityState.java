package com.miaokatze.gtswn.common.monitoring.ae;

import java.util.UUID;

/** Constant-size identity state. An origin is shared only by native storage transfers, not by coordinates. */
final class GridIdentityState {

    static final class Origin {

        final UUID id;
        int liveGrids;
        int unsettledGrids;
        boolean retired;

        Origin(UUID id) {
            this.id = id;
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
            origin.retired = true;
            incoming.retired = true;
            attach(new Origin(null));
        }
    }

    Origin transfer() {
        if (origin == null) attach(new Origin(null));
        return origin;
    }

    Origin split() {
        if (published && origin != null) origin.retired = true;
        changed();
        return transfer();
    }

    void invalidate() {
        if (origin != null) origin.retired = true;
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
        if (origin != null && origin.liveGrids > 1) origin.retired = true;
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
