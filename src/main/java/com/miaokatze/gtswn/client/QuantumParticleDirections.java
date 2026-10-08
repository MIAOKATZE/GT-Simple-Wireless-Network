package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, precomputed direction policy in Forge's DOWN/UP/NORTH/SOUTH/WEST/EAST order. */
public final class QuantumParticleDirections {

    private static final List<List<Direction>> AIR_FACES = new ArrayList<>();
    private static final List<Direction> DIAGONALS;

    static {
        Direction[] axes = { new Direction(0, -1, 0, 0), new Direction(0, 1, 0, 1), new Direction(0, 0, -1, 2),
            new Direction(0, 0, 1, 3), new Direction(-1, 0, 0, 4), new Direction(1, 0, 0, 5) };
        for (int mask = 0; mask < 64; mask++) {
            List<Direction> faces = new ArrayList<>();
            for (int side = 0; side < 6; side++) {
                if ((mask & (1 << side)) != 0) faces.add(axes[side]);
            }
            AIR_FACES.add(Collections.unmodifiableList(faces));
        }
        List<Direction> diagonals = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (Math.abs(x) + Math.abs(y) + Math.abs(z) >= 2) diagonals.add(new Direction(x, y, z, -1));
                }
            }
        }
        DIAGONALS = Collections.unmodifiableList(diagonals);
    }

    private QuantumParticleDirections() {}

    public static List<Direction> nodeDirections(int connectedMask) {
        int mask = connectedMask & 63;
        return mask == 63 ? DIAGONALS : openFaces((~mask) & 63);
    }

    public static List<Direction> openFaces(int airMask) {
        return AIR_FACES.get(airMask & 63);
    }

    public static final class Direction {

        public final int x, y, z, side;

        private Direction(int x, int y, int z, int side) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.side = side;
        }
    }
}
