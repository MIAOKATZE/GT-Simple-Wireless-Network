// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.miaokatze.gtswn.client.render.LinkNodeVisuals.Cell;
import com.miaokatze.gtswn.client.render.LinkNodeVisuals.Geometry;

/** Reference the old quads and compare their actual 013/123 triangles, including winding and attributes. */
public class LinkNodePackedGeometryTest {

    @Test
    public void cachedCoordinatesKeepTheOriginalDoubleArithmeticAndSeventhCorner() {
        for (double depth : new double[] { -.1, 0, .001, .08, .5 }) {
            for (double relief : new double[] { -1, -.37, 0, .61, 1 }) {
                Geometry geometry = new Geometry(depth, relief);
                for (int side = 0; side < 6; side++) {
                    for (int i = 0; i < LinkNodeVisuals.CELLS.size(); i++) {
                        Cell cell = LinkNodeVisuals.CELLS.get(i);
                        double top = Math.max(.001, LinkNodeVisuals.surfaceDepth(cell, depth, relief));
                        assertEquals(top, geometry.depth(i), 0);
                        double[] surface = geometry.surface(side, i), wall = geometry.wall(side, i);
                        for (int axis = 0; axis < 3; axis++) {
                            sameBits(LinkNodeVisuals.pointCoordinate(side, axis, cell.u, cell.v, top), surface[axis]);
                            for (int edge = 0; edge <= 6; edge++) {
                                sameBits(
                                    LinkNodeVisuals
                                        .pointCoordinate(side, axis, cell.surfaceU(edge), cell.surfaceV(edge), top),
                                    surface[(edge + 1) * 3 + axis]);
                                sameBits(
                                    LinkNodeVisuals
                                        .pointCoordinate(side, axis, cell.wallU(edge), cell.wallV(edge), .001),
                                    wall[edge * 6 + axis]);
                                sameBits(
                                    LinkNodeVisuals
                                        .pointCoordinate(side, axis, cell.wallU(edge), cell.wallV(edge), top),
                                    wall[edge * 6 + 3 + axis]);
                            }
                        }
                        for (int vertex = 0; vertex < 8; vertex++) {
                            sameBits(vertex == 0 ? cell.u + .5 : cell.surfaceU(vertex - 1) + .5, cell.textureU(vertex));
                            sameBits(vertex == 0 ? cell.v + .5 : cell.surfaceV(vertex - 1) + .5, cell.textureV(vertex));
                        }
                    }
                }
            }
        }
    }

    @Test
    public void threeCapQuadsKeepAllSixTrianglesInOrderWithUvColorAndWorldFloatPositions() {
        for (double depth : new double[] { -.1, .08, .5 }) {
            for (double relief : new double[] { -1, 0, 1 }) {
                Geometry geometry = new Geometry(depth, relief);
                for (int side = 0; side < 6; side++) {
                    for (int i = 0; i < LinkNodeVisuals.CELLS.size(); i++) {
                        Cell cell = LinkNodeVisuals.CELLS.get(i);
                        for (double host : new double[] { 0, -29999984, 29999984 }) {
                            List<Vertex> original = new ArrayList<>(), compact = new ArrayList<>();
                            Vertex[] vertices = new Vertex[8];
                            for (int vertex = 0; vertex < 8; vertex++) {
                                vertices[vertex] = vertex(
                                    geometry.surface(side, i),
                                    vertex * 3,
                                    host,
                                    cell.textureU(vertex),
                                    cell.textureV(vertex),
                                    color(i));
                            }
                            for (int edge = 0; edge < 6; edge++) {
                                add(original, vertices, 0, edge + 1, edge + 2, 0);
                            }
                            for (int edge = 0; edge < 6; edge += 2) {
                                add(compact, vertices, edge + 1, edge + 2, edge + 3, 0);
                            }
                            assertTriangles(quads(original), quads(compact));
                            assertEquals(6, quads(compact).size());
                        }
                    }
                }
            }
        }
    }

    @Test
    public void wallStripPreservesDiagonalWindingColorAndOrderWithoutBridgesAcrossCellsAndBatches() {
        for (double relief : new double[] { -1, -.5, 0, .5, 1 }) {
            Geometry geometry = new Geometry(.08, relief);
            List<Vertex> original = new ArrayList<>(), compact = new ArrayList<>();
            // Nineteen sources exercise the exact eighteen-source flush and its new parity prefix.
            for (int source = 0; source < 19; source++) {
                if (source == 18) {
                    assertTriangles(quads(original), strip(compact));
                    assertEquals(18 * 37 * 16 - 1, compact.size());
                    original.clear();
                    compact.clear();
                }
                for (int i = 0; i < LinkNodeVisuals.CELLS.size(); i++) {
                    double[] points = geometry.wall(source % 6, i);
                    Vertex[] vertices = new Vertex[14];
                    double host = (source % 2 == 0 ? 29999900 : -29999900) + source;
                    for (int vertex = 0; vertex < 14; vertex++) {
                        vertices[vertex] = vertex(points, vertex * 3, host, 0, 0, color(i + source));
                    }
                    for (int edge = 0; edge < 6; edge++) {
                        add(original, vertices, edge * 2, edge * 2 + 2, edge * 2 + 3, edge * 2 + 1);
                    }
                    if (!compact.isEmpty()) compact.add(
                        compact.get(compact.size() - 1)
                            .withColor(color(i + source)));
                    compact.add(vertices[0]);
                    compact.addAll(Arrays.asList(vertices));
                }
            }
            assertTriangles(quads(original), strip(compact));
            assertEquals(37 * 16 - 1, compact.size());
        }
    }

    private static void sameBits(double expected, double actual) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }

    private static Vertex vertex(double[] points, int offset, double host, double u, double v, int color) {
        // Preserve the actual operation order: local double + block integer + Tessellator offset, then float.
        double translation = -host + .375;
        return new Vertex(
            (float) (host + points[offset] + translation),
            (float) (host + points[offset + 1] + translation),
            (float) (host + points[offset + 2] + translation),
            (float) u,
            (float) v,
            color);
    }

    private static int color(int index) {
        float alpha = (float) (.63F * (.25 + (index % 7) / 7D * .6) * ((index % 4) / 3D));
        return 255 | 178 << 8 | 35 << 16 | (int) (alpha * 255) << 24;
    }

    private static void add(List<Vertex> target, Vertex[] vertices, int a, int b, int c, int d) {
        target.add(vertices[a]);
        target.add(vertices[b]);
        target.add(vertices[c]);
        target.add(vertices[d]);
    }

    private static List<Vertex[]> quads(List<Vertex> vertices) {
        List<Vertex[]> triangles = new ArrayList<>();
        for (int i = 0; i < vertices.size(); i += 4) {
            triangle(triangles, vertices.get(i), vertices.get(i + 1), vertices.get(i + 3));
            triangle(triangles, vertices.get(i + 1), vertices.get(i + 2), vertices.get(i + 3));
        }
        return triangles;
    }

    private static List<Vertex[]> strip(List<Vertex> vertices) {
        List<Vertex[]> triangles = new ArrayList<>();
        for (int i = 0; i + 2 < vertices.size(); i++) {
            triangle(triangles, vertices.get(i + (i % 2)), vertices.get(i + 1 - (i % 2)), vertices.get(i + 2));
        }
        return triangles;
    }

    private static void triangle(List<Vertex[]> triangles, Vertex a, Vertex b, Vertex c) {
        if (!a.samePosition(b) && !b.samePosition(c) && !c.samePosition(a)) triangles.add(new Vertex[] { a, b, c });
    }

    private static void assertTriangles(List<Vertex[]> expected, List<Vertex[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            Vertex[] a = expected.get(i), b = actual.get(i);
            boolean cyclic = false;
            for (int shift = 0; shift < 3; shift++) {
                cyclic |= a[0].same(b[shift]) && a[1].same(b[(shift + 1) % 3]) && a[2].same(b[(shift + 2) % 3]);
            }
            assertTrue("Triangle " + i + " must preserve its cyclic orientation and every vertex attribute", cyclic);
        }
    }

    private static final class Vertex {

        private final float x, y, z, u, v;
        private final int color;

        private Vertex(float x, float y, float z, float u, float v, int color) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = u;
            this.v = v;
            this.color = color;
        }

        private Vertex withColor(int newColor) {
            return new Vertex(x, y, z, u, v, newColor);
        }

        private boolean samePosition(Vertex other) {
            return Float.floatToIntBits(x) == Float.floatToIntBits(other.x)
                && Float.floatToIntBits(y) == Float.floatToIntBits(other.y)
                && Float.floatToIntBits(z) == Float.floatToIntBits(other.z);
        }

        private boolean same(Vertex other) {
            return samePosition(other) && Float.floatToIntBits(u) == Float.floatToIntBits(other.u)
                && Float.floatToIntBits(v) == Float.floatToIntBits(other.v)
                && color == other.color;
        }
    }
}
