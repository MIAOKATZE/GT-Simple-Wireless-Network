package com.miaokatze.gtswn.common.engineering;

/** MC chunks and GT ore-seed regions, including the legacy axis transition. */
public final class EngineeringGrid {

    public static final int MAX_CELLS = 64;
    public static final int MAX_BLOCK = 30_000_000;
    private static volatile boolean axisSymmetrical;

    private EngineeringGrid() {}

    public static int cellOfBlock(int block, boolean gt) {
        return cellOfBlock(block, gt, axisSymmetrical);
    }

    public static void applyPattern(boolean axis) {
        axisSymmetrical = axis;
    }

    public static int cellOfBlock(int block, boolean gt, boolean axis) {
        int chunk = Math.floorDiv(block, 16);
        return !gt ? chunk : Math.floorDiv(chunk < 0 && axis ? chunk - 1 : chunk, 3);
    }

    public static int minBlock(int cell, boolean gt) {
        return minBlock(cell, gt, axisSymmetrical);
    }

    public static int minBlock(int cell, boolean gt, boolean axis) {
        return Math.toIntExact(minChunk(cell, gt, axis) * 16);
    }

    public static int maxBlockExclusive(int cell, boolean gt) {
        return maxBlockExclusive(cell, gt, axisSymmetrical);
    }

    public static int maxBlockExclusive(int cell, boolean gt, boolean axis) {
        return minBlock(Math.addExact(cell, 1), gt, axis);
    }

    private static long minChunk(long cell, boolean gt, boolean axis) {
        return !gt ? cell : 3L * cell + (axis && cell < 0 ? 1 : 0);
    }

    public static boolean valid(int minX, int minZ, int maxX, int maxZ, boolean gt) {
        long width = (long) maxX - minX + 1, height = (long) maxZ - minZ + 1;
        return width > 0 && height > 0
            && width <= MAX_CELLS
            && height <= MAX_CELLS
            && width * height <= MAX_CELLS
            && minChunk(minX, gt, false) * 16 >= -MAX_BLOCK
            && minChunk(minZ, gt, false) * 16 >= -MAX_BLOCK
            && minChunk((long) maxX + 1, gt, false) * 16 <= MAX_BLOCK
            && minChunk((long) maxZ + 1, gt, false) * 16 <= MAX_BLOCK;
    }
}
