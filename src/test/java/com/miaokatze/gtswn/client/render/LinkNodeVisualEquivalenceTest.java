package com.miaokatze.gtswn.client.render;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Reference formulas deliberately retain the uncached renderer's arithmetic and first-cell ownership. */
public class LinkNodeVisualEquivalenceTest {

    @Test
    public void cachedRasterIsPixelExactAcrossPhasesColorsAndReusableBuffers() {
        for (int size : new int[] { 16, 32, 64 }) {
            int[] base = new int[size * size], glow = new int[size * size];
            for (double seconds : new double[] { 0, .45, 4.999, 5.01, 7.9, 10.45, 12345.67 }) {
                for (boolean energy : new boolean[] { false, true }) {
                    for (int rgb : new int[] { -1, 0, 0x12ABEF }) {
                        for (int percent : new int[] { 25, 50, 75, 100 }) {
                            LinkNodeVisuals.writePixels(size, seconds, energy, rgb, percent, base, glow);
                            assertArrayEquals(referencePixels(size, seconds, energy, false, rgb, percent), base);
                            assertArrayEquals(referencePixels(size, seconds, energy, true, rgb, percent), glow);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void cachedCornersAndScalarCoordinatesKeepEveryHighVertexBitExact() {
        for (LinkNodeVisuals.Cell cell : LinkNodeVisuals.CELLS) {
            for (int edge = 0; edge <= 6; edge++) {
                double angle = edge * Math.PI / 3;
                assertBits(cell.u + Math.cos(angle) * LinkNodeVisuals.CELL_RADIUS, cell.surfaceU(edge));
                assertBits(cell.v + Math.sin(angle) * LinkNodeVisuals.CELL_RADIUS, cell.surfaceV(edge));
                assertBits(cell.u + Math.cos(angle) * (LinkNodeVisuals.CELL_RADIUS * .93), cell.wallU(edge));
                assertBits(cell.v + Math.sin(angle) * (LinkNodeVisuals.CELL_RADIUS * .93), cell.wallV(edge));
                for (int side = 0; side < 6; side++) {
                    for (double depth : new double[] { .001, 1D / 16, 1D / 8 }) {
                        for (double relief : new double[] { -1, -.4, 0, .3, 1 }) {
                            double surface = Math.max(.001, LinkNodeVisuals.surfaceDepth(cell, depth, relief));
                            comparePoint(side, cell.u, cell.v, surface);
                            comparePoint(side, cell.surfaceU(edge), cell.surfaceV(edge), surface);
                            comparePoint(side, cell.wallU(edge), cell.wallV(edge), surface);
                            comparePoint(side, cell.wallU(edge), cell.wallV(edge), .001);
                        }
                    }
                }
            }
        }
    }

    private static void comparePoint(int side, double u, double v, double depth) {
        int[][] normals = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 } };
        double[] n = { normals[side][0], normals[side][1], normals[side][2] };
        double[] a = side < 4 ? new double[] { 1, 0, 0 } : new double[] { 0, 0, 1 };
        double[] b = side < 2 ? new double[] { 0, 0, 1 } : new double[] { 0, 1, 0 };
        double[] point = LinkNodeVisuals.point(side, u, v, depth);
        for (int axis = 0; axis < 3; axis++) {
            double expected = .5 + n[axis] * (.5 + depth) + a[axis] * u + b[axis] * v;
            assertBits(expected, LinkNodeVisuals.pointCoordinate(side, axis, u, v, depth));
            assertBits(expected, point[axis]);
        }
    }

    private static void assertBits(double expected, double actual) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }

    private static int[] referencePixels(int size, double seconds, boolean energy, boolean emissive, int voltageRgb,
        int percent) {
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double u = (x + .5) / size - .5, v = (y + .5) / size - .5;
                for (LinkNodeVisuals.Cell cell : LinkNodeVisuals.CELLS) {
                    double dx = Math.abs(u - cell.u), dy = Math.abs(v - cell.v);
                    double distance = Math.max(dy, (Math.sqrt(3) * dx + dy) / 2);
                    if (distance > LinkNodeVisuals.CELL_RADIUS * .866) continue;
                    boolean edge = distance > LinkNodeVisuals.CELL_RADIUS * .866 - 1.1 / size;
                    double light = LinkNodeVisuals.brightness(cell, seconds, size == 32, energy);
                    int alpha = emissive ? (int) (light * (edge ? 245 : 160)) : edge ? 125 : 38;
                    int rgb = LinkNodeVisuals.cellRgb(cell.ring, energy, size == 64 ? voltageRgb : -1, percent);
                    int red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
                    if (emissive) {
                        red += (int) ((255 - red) * light * .65);
                        green += (int) ((255 - green) * light * .65);
                        blue += (int) ((255 - blue) * light * .65);
                    }
                    pixels[y * size + x] = alpha << 24 | red << 16 | green << 8 | blue;
                    break;
                }
            }
        }
        return pixels;
    }
}
