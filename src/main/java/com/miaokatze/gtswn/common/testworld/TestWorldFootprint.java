package com.miaokatze.gtswn.common.testworld;

/** Chunk-aligned bounds for authored test fixtures and the full-height reset scan. */
final class TestWorldFootprint {

    static final int DEFAULT_RADIUS = 32;
    static final int MAX_RADIUS = 80;
    final int radius;
    final int width;
    final int chunkWidth;

    TestWorldFootprint(int radius) {
        if (radius < DEFAULT_RADIUS || radius > MAX_RADIUS || radius % 16 != 0)
            throw new IllegalArgumentException("Test footprint radius must be a multiple of 16 in 32..80");
        this.radius = radius;
        width = radius * 2;
        chunkWidth = width / 16;
    }

    /** Old saves default to 32; malformed values round outward within the supported safety bound. */
    static int savedRadius(int value) {
        int bounded = Math.max(DEFAULT_RADIUS, Math.min(MAX_RADIUS, value));
        return (bounded + 15) / 16 * 16;
    }

    int chunkCount() {
        return chunkWidth * chunkWidth;
    }

    int blockCount() {
        return width * width * 256;
    }

    int chunkX(int index) {
        return -radius / 16 + index / chunkWidth;
    }

    int chunkZ(int index) {
        return -radius / 16 + index % chunkWidth;
    }

    int blockX(int cursor) {
        return -radius + (cursor >>> 8) / width;
    }

    int blockZ(int cursor) {
        return -radius + (cursor >>> 8) % width;
    }

    static boolean containsFixture(int radius, int x, int y, int z) {
        return x >= -radius && x < radius && z >= -radius && z < radius && y >= 64 && y <= 127;
    }

    String describe() {
        return "x/z=" + -radius + ".." + (radius - 1) + ", y=0..255, chunks=" + chunkCount();
    }
}
