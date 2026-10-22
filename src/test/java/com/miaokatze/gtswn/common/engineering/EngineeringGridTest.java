package com.miaokatze.gtswn.common.engineering;

import static org.junit.Assert.*;

import org.junit.Test;

public class EngineeringGridTest {

    @Test
    public void equalAndLegacyPatternsMatchNegativeChunkBoundaries() {
        int[] equal = { -2, -2, -2, -1, -1, -1, 0, 0, 0, 1, 1, 1, 2 };
        int[] legacy = { -3, -2, -2, -2, -1, -1, 0, 0, 0, 1, 1, 1, 2 };
        for (int chunk = -6; chunk <= 6; chunk++) {
            assertEquals(equal[chunk + 6], EngineeringGrid.cellOfBlock(chunk * 16, true, false));
            assertEquals(legacy[chunk + 6], EngineeringGrid.cellOfBlock(chunk * 16, true, true));
        }
        assertEquals(-32, EngineeringGrid.minBlock(-1, true, true));
        assertEquals(0, EngineeringGrid.maxBlockExclusive(-1, true, true));
        assertEquals(-80, EngineeringGrid.minBlock(-2, true, true));
        assertEquals(-32, EngineeringGrid.maxBlockExclusive(-2, true, true));
    }

    @Test
    public void everyBlockHasExactlyOneCellAndAdjacentCellsNeverOverlap() {
        for (boolean gt : new boolean[] { false, true }) for (boolean axis : new boolean[] { false, true }) {
            for (int block = -1000; block <= 1000; block++) {
                int cell = EngineeringGrid.cellOfBlock(block, gt, axis);
                assertTrue(EngineeringGrid.minBlock(cell, gt, axis) <= block);
                assertTrue(EngineeringGrid.maxBlockExclusive(cell, gt, axis) > block);
                assertEquals(
                    EngineeringGrid.maxBlockExclusive(cell, gt, axis),
                    EngineeringGrid.minBlock(cell + 1, gt, axis));
            }
        }
    }

    @Test
    public void viewBoundsRejectHugeOverflowingOrExcessiveRegions() {
        assertTrue(EngineeringGrid.valid(-4, -4, 3, 3, true));
        assertFalse(EngineeringGrid.valid(-4, -4, 4, 3, true));
        assertFalse(EngineeringGrid.valid(2, 0, 1, 0, false));
        assertFalse(EngineeringGrid.valid(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0, true));
        assertFalse(EngineeringGrid.valid(Integer.MIN_VALUE, 0, Integer.MIN_VALUE, 0, false));
        assertFalse(EngineeringGrid.valid(-1_000_000, 0, 1_000_000, 0, false));
    }
}
