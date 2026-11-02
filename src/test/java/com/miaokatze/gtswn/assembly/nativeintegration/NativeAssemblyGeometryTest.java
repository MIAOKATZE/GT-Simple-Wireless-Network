package com.miaokatze.gtswn.assembly.nativeintegration;

import static org.junit.Assert.*;

import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

public class NativeAssemblyGeometryTest {

    @Test
    public void everyAllowedRotationAcceptsBothNativeScanDirections() {
        for (ExtendedFacing facing : ExtendedFacing.VALUES) {
            if (facing.getFlip()
                .isVerticallyFliped()) continue;
            for (int direction : new int[] { -1, 1 }) {
                int[][] positions = column(facing, direction, 1, 5);
                assertTrue(facing.toString(), NativeAssemblyGeometry.isOrderedColumn(positions, facing, 5, true));
            }
        }
    }

    @Test
    public void rotatedNativeLengthCanUseWorldY() {
        ExtendedFacing facing = ExtendedFacing.NORTH_CLOCKWISE_NONE;
        assertEquals(ForgeDirection.DOWN, NativeAssemblyGeometry.lengthAxis(facing));
        assertTrue(NativeAssemblyGeometry.isOrderedColumn(column(facing, 1, 1, 5), facing, 5, true));
    }

    @Test
    public void neverRepairsBranchesDuplicatesReversalsOrShortColumns() {
        ExtendedFacing facing = ExtendedFacing.DEFAULT;
        assertFalse(NativeAssemblyGeometry.isOrderedColumn(column(facing, 1, 1, 4), facing, 5, true));
        int[][] branch = column(facing, 1, 1, 5);
        branch[3][1]++;
        assertFalse(NativeAssemblyGeometry.isOrderedColumn(branch, facing, 5, true));
        int[][] duplicate = column(facing, 1, 1, 5);
        duplicate[3] = duplicate[2].clone();
        assertFalse(NativeAssemblyGeometry.isOrderedColumn(duplicate, facing, 5, true));
        int[][] reversal = column(facing, 1, 1, 5);
        reversal[4] = reversal[2].clone();
        assertFalse(NativeAssemblyGeometry.isOrderedColumn(reversal, facing, 5, true));
    }

    @Test
    public void onlyFluidColumnsMayLeaveGaps() {
        ExtendedFacing facing = ExtendedFacing.SOUTH_COUNTER_CLOCKWISE_NONE;
        int[][] positions = column(facing, -1, 2, 5);
        assertFalse(NativeAssemblyGeometry.isOrderedColumn(positions, facing, 5, true));
        assertTrue(NativeAssemblyGeometry.isOrderedColumn(positions, facing, 1, false));
    }

    @Test
    public void fluidMembersMustReachTheItemColumnThroughActualFaces() {
        assertTrue(
            NativeAssemblyGeometry.isFaceConnected(
                new int[][] { { 0, 0, 0 }, { 1, 0, 0 }, { 2, 0, 0 }, { 3, 0, 0 }, { 4, 0, 0 }, { 0, 0, 1 },
                    { 2, 0, 1 } }));
        assertFalse(
            NativeAssemblyGeometry.isFaceConnected(
                new int[][] { { 0, 0, 0 }, { 1, 0, 0 }, { 2, 0, 0 }, { 3, 0, 0 }, { 4, 0, 0 }, { 5, 0, 1 } }));
        assertFalse(NativeAssemblyGeometry.isFaceConnected(new int[][] { { 0, 0, 0 }, { 1, 1, 0 } }));
    }

    private static int[][] column(ExtendedFacing facing, int direction, int spacing, int count) {
        ForgeDirection axis = NativeAssemblyGeometry.lengthAxis(facing);
        int[][] positions = new int[count][3];
        for (int i = 0; i < count; i++) {
            int step = direction * spacing * i;
            positions[i] = new int[] { 13 + axis.offsetX * step, 78 + axis.offsetY * step, -24 + axis.offsetZ * step };
        }
        return positions;
    }
}
