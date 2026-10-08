package com.miaokatze.gtswn.client.render;

/** Pure geometry for the beveled core and the AE glass cable sized visual arms. */
final class QuantumNodeGeometry {

    static final double CORE_MIN = 5 / 16D;
    static final double CORE_MAX = 11 / 16D;
    static final double BEVEL = .012D;
    static final double ARM_MIN = 6 / 16D;
    static final double ARM_MAX = 10 / 16D;

    static final double[][] CORE_RING = { { CORE_MIN + BEVEL, CORE_MIN }, { CORE_MAX - BEVEL, CORE_MIN },
        { CORE_MAX, CORE_MIN + BEVEL }, { CORE_MAX, CORE_MAX - BEVEL }, { CORE_MAX - BEVEL, CORE_MAX },
        { CORE_MIN + BEVEL, CORE_MAX }, { CORE_MIN, CORE_MAX - BEVEL }, { CORE_MIN, CORE_MIN + BEVEL } };

    private QuantumNodeGeometry() {}

    /** ForgeDirection ordinal order: DOWN, UP, NORTH, SOUTH, WEST, EAST. */
    static double[] armBounds(int side) {
        switch (side) {
            case 0:
                return new double[] { ARM_MIN, 0, ARM_MIN, ARM_MAX, CORE_MIN, ARM_MAX };
            case 1:
                return new double[] { ARM_MIN, CORE_MAX, ARM_MIN, ARM_MAX, 1, ARM_MAX };
            case 2:
                return new double[] { ARM_MIN, ARM_MIN, 0, ARM_MAX, ARM_MAX, CORE_MIN };
            case 3:
                return new double[] { ARM_MIN, ARM_MIN, CORE_MAX, ARM_MAX, ARM_MAX, 1 };
            case 4:
                return new double[] { 0, ARM_MIN, ARM_MIN, CORE_MIN, ARM_MAX, ARM_MAX };
            case 5:
                return new double[] { CORE_MAX, ARM_MIN, ARM_MIN, 1, ARM_MAX, ARM_MAX };
            default:
                throw new IllegalArgumentException("Invalid arm direction: " + side);
        }
    }

    /** Face ring leaves exactly the arm cross section open; winding faces outwards. */
    static double[][][] connectionRing(int side) {
        double[][] outer;
        if (side < 2) {
            outer = new double[8][3];
            for (int i = 0; i < outer.length; i++) {
                double[] point = CORE_RING[side == 0 ? i : (8 - i) % 8];
                outer[i] = new double[] { point[0], side == 0 ? CORE_MIN : CORE_MAX, point[1] };
            }
        } else {
            int index = side == 2 ? 0 : side == 3 ? 4 : side == 4 ? 6 : 2;
            double[] a = CORE_RING[index], b = CORE_RING[index + 1];
            outer = new double[][] { { a[0], CORE_MIN, a[1] }, { a[0], CORE_MAX, a[1] }, { b[0], CORE_MAX, b[1] },
                { b[0], CORE_MIN, b[1] } };
        }
        int axis = side < 2 ? 1 : side < 4 ? 2 : 0;
        double[][] inner = new double[outer.length][3];
        for (int i = 0; i < outer.length; i++) {
            inner[i] = outer[i].clone();
            for (int coordinate = 0; coordinate < 3; coordinate++) {
                if (coordinate != axis) {
                    inner[i][coordinate] = Math.max(ARM_MIN, Math.min(ARM_MAX, inner[i][coordinate]));
                }
            }
        }
        double[][][] faces = new double[outer.length][4][];
        for (int i = 0; i < outer.length; i++) {
            int next = (i + 1) % outer.length;
            faces[i] = new double[][] { outer[i], outer[next], inner[next], inner[i] };
        }
        return faces;
    }
}
