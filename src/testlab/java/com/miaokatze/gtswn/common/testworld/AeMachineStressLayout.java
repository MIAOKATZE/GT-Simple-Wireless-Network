package com.miaokatze.gtswn.common.testworld;

import java.util.ArrayList;
import java.util.List;

/** Shared pure geometry for the explicit native machine workloads. */
final class AeMachineStressLayout {

    // Preserve the original static 10k contract for existing callers; scenarios use their selected profile.
    static final int RADIUS = 80, PAIRS = 5000, NODES = 10000, MACHINES = 1024, STORAGE_PAIRS = 256;
    static final int FULL_CELLS = STORAGE_PAIRS * 10, EMPTY_CELLS = STORAGE_PAIRS, ITEM_TYPES = 256;
    static final int CONTROLLERS = AeStressControllerLayout.CONTROLLERS;
    static final int CHANNELS = NODES + MACHINES * 2 + STORAGE_PAIRS * 2;
    static final int CHANNEL_BUDGET = AeStressControllerLayout.CHANNEL_BUDGET;
    static final int ENTRY_X = 0, ENTRY_Y = 65, ENTRY_Z = 1;

    enum Profile {

        TEN_THOUSAND("10k", NODES, MACHINES, STORAGE_PAIRS),
        TWENTY_FIVE_HUNDRED("2500", 2500, 256, 64);

        final String suffix;
        final int nodes, pairs, machines, storagePairs, fullCells, emptyCells, channels, disturbedFirst;

        Profile(String suffix, int nodes, int machines, int storagePairs) {
            if (nodes <= 0 || nodes > NODES
                || nodes % 250 != 0
                || machines <= 0
                || machines > nodes / 2
                || storagePairs <= 0
                || storagePairs > machines
                || storagePairs * 10 < ITEM_TYPES)
                throw new IllegalArgumentException("Native machine profile exceeds geometry, groups or palette bounds");
            this.suffix = suffix;
            this.nodes = nodes;
            this.pairs = nodes / 2;
            this.machines = machines;
            this.storagePairs = storagePairs;
            this.fullCells = storagePairs * 10;
            this.emptyCells = storagePairs;
            this.channels = nodes + machines * 2 + storagePairs * 2;
            this.disturbedFirst = nodes * 9 / 10;
            if (channels > CHANNEL_BUDGET || disturbedFirst + 8 > nodes || disturbedFirst < machines * 2)
                throw new IllegalArgumentException(
                    "Native machine profile lacks channel or isolated disturbance capacity");
        }
    }

    enum Kind {
        CONTROLLER,
        POWER,
        ENTRY,
        NODE,
        INTERFACE,
        DRIVE,
        CHEST,
        MACHINE,
        HOPPER_EAST,
        HOPPER_NORTH,
        SOURCE_LOCK
    }

    static final class Placement {

        final Kind kind;
        final int x, y, z, index;

        Placement(Kind kind, int x, int y, int z, int index) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.index = index;
        }
    }

    final List<Placement> placements = new ArrayList<>();
    final Profile profile;

    AeMachineStressLayout() {
        this(Profile.TEN_THOUSAND);
    }

    AeMachineStressLayout(Profile profile) {
        this.profile = java.util.Objects.requireNonNull(profile, "profile");
        for (int[] p : AeStressControllerLayout.coordinates()) add(Kind.CONTROLLER, p[0], p[1], p[2], -1);
        add(Kind.POWER, AeStressControllerLayout.X - 1, AeStressControllerLayout.Y, AeStressControllerLayout.Z, -1);
        add(Kind.ENTRY, ENTRY_X, ENTRY_Y, ENTRY_Z, -1);
        for (int pair = 0; pair < profile.pairs; pair++) {
            int x = x(pair), y = y(pair), z = z(pair);
            for (int side = 0; side < 2; side++) {
                add(Kind.NODE, x + side * 2, y, z, pair * 2 + side);
                add(Kind.INTERFACE, x + side * 2, y - side, z + 1 - side, pair * 2 + side);
            }
            add(Kind.SOURCE_LOCK, x, y - 2, z + 1, pair);
            add(Kind.HOPPER_EAST, x, y - 1, z + 1, pair * 3);
            add(Kind.HOPPER_EAST, x + 1, y - 1, z + 1, pair * 3 + 1);
            add(Kind.HOPPER_NORTH, x + 2, y - 1, z + 1, pair * 3 + 2);
            if (pair < profile.machines) add(Kind.MACHINE, x + 1, y, z, pair);
            if (pair < profile.storagePairs) {
                add(Kind.DRIVE, x - 1, y, z, pair);
                add(Kind.CHEST, x + 1, y - 1, z, pair);
            }
        }
    }

    static int x(int pair) {
        return -75 + pair % 25 * 6;
    }

    static int y(int pair) {
        return 80 + pair / 625 * 6;
    }

    static int z(int pair) {
        return -75 + pair / 25 % 25 * 6;
    }

    private void add(Kind kind, int x, int y, int z, int index) {
        placements.add(new Placement(kind, x, y, z, index));
    }
}
