package com.miaokatze.gtswn.assembly.nativeintegration;

import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

/** Geometry only: registration order comes from the successful native structure scan. */
public final class NativeAssemblyGeometry {

    private NativeAssemblyGeometry() {}

    public static ForgeDirection lengthAxis(ExtendedFacing facing) {
        return facing.getRelativeLeftInWorld();
    }

    /** Bounded to the controller's registered members; never searches or loads world blocks. */
    public static boolean isFaceConnected(int[][] positions) {
        if (positions.length == 0) return false;
        boolean[] seen = new boolean[positions.length];
        int[] queue = new int[positions.length];
        int head = 0, tail = 1;
        seen[0] = true;
        while (head < tail) {
            int[] current = positions[queue[head++]];
            for (int i = 0; i < positions.length; i++) {
                if (seen[i]) continue;
                int[] next = positions[i];
                if (Math.abs(current[0] - next[0]) + Math.abs(current[1] - next[1]) + Math.abs(current[2] - next[2])
                    == 1) {
                    seen[i] = true;
                    queue[tail++] = i;
                }
            }
        }
        return tail == positions.length;
    }

    /**
     * Checks a single ordered column, accepting either of the native scan directions. Fluid positions may have gaps;
     * item positions must use contiguous=true. This never sorts or repairs an ambiguous native input list.
     */
    public static boolean isOrderedColumn(int[][] positions, ExtendedFacing facing, int minimum, boolean contiguous) {
        return isOrderedColumn(positions, lengthAxis(facing), minimum, contiguous);
    }

    public static boolean isOrderedColumn(int[][] positions, ForgeDirection axis, int minimum, boolean contiguous) {
        if (positions.length < minimum) return false;
        if (axis == ForgeDirection.UNKNOWN) return false;
        int sign = 0;
        for (int i = 1; i < positions.length; i++) {
            int dx = positions[i][0] - positions[i - 1][0];
            int dy = positions[i][1] - positions[i - 1][1];
            int dz = positions[i][2] - positions[i - 1][2];
            int step = dx * axis.offsetX + dy * axis.offsetY + dz * axis.offsetZ;
            if (step == 0 || (contiguous && Math.abs(step) != 1)
                || dx != step * axis.offsetX
                || dy != step * axis.offsetY
                || dz != step * axis.offsetZ) return false;
            int stepSign = Integer.signum(step);
            if (sign != 0 && sign != stepSign) return false;
            sign = stepSign;
        }
        return true;
    }
}
