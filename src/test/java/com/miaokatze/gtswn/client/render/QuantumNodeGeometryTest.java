package com.miaokatze.gtswn.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QuantumNodeGeometryTest {

    @Test
    public void allSixArmsMatchGlassCableWidthAndKeepOriginalEndpoints() {
        for (int side = 0; side < 6; side++) {
            double[] bounds = QuantumNodeGeometry.armBounds(side);
            int axis = axis(side);
            for (int coordinate = 0; coordinate < 3; coordinate++) {
                if (coordinate == axis) {
                    assertEquals(side % 2 == 0 ? 0 : 11 / 16D, bounds[coordinate], 0);
                    assertEquals(side % 2 == 0 ? 5 / 16D : 1, bounds[coordinate + 3], 0);
                } else {
                    assertEquals(6 / 16D, bounds[coordinate], 0);
                    assertEquals(10 / 16D, bounds[coordinate + 3], 0);
                    assertEquals(4 / 16D, bounds[coordinate + 3] - bounds[coordinate], 0);
                }
            }
        }
    }

    @Test
    public void eachJointRingCoversExactlyTheExposedCoreAndLeavesArmCenterOpen() {
        double coreWidth = 6 / 16D;
        double bevel = QuantumNodeGeometry.BEVEL;
        for (int side = 0; side < 6; side++) {
            double[][][] faces = QuantumNodeGeometry.connectionRing(side);
            int axis = axis(side);
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            double area = 0;
            for (double[][] face : faces) {
                double signedArea = 0;
                for (int i = 0; i < 4; i++) {
                    double[] a = face[i], b = face[(i + 1) % 4];
                    assertEquals(side % 2 == 0 ? 5 / 16D : 11 / 16D, a[axis], 0);
                    signedArea += a[u] * b[v] - b[u] * a[v];
                }
                assertTrue(side % 2 == 0 ? signedArea < 0 : signedArea > 0);
                area += Math.abs(signedArea) / 2;
            }
            double outerArea = side < 2 ? coreWidth * coreWidth - 2 * bevel * bevel
                : (coreWidth - 2 * bevel) * coreWidth;
            assertEquals(outerArea - .25D * .25D, area, 1e-12);
            // Off-grid samples avoid shared edges: every exposed point has one face, the arm opening none.
            for (int i = 0; i < 41; i++) {
                for (int j = 0; j < 41; j++) {
                    double pu = 5 / 16D + (i + .371D) * coreWidth / 41;
                    double pv = 5 / 16D + (j + .617D) * coreWidth / 41;
                    double[] point = new double[3];
                    point[u] = pu;
                    point[v] = pv;
                    boolean inCore;
                    if (side < 2) {
                        double cornerDistance = Math.min(point[0] - 5 / 16D, 11 / 16D - point[0])
                            + Math.min(point[2] - 5 / 16D, 11 / 16D - point[2]);
                        inCore = cornerDistance > bevel;
                    } else {
                        int horizontal = axis == 0 ? 2 : 0;
                        inCore = point[horizontal] > 5 / 16D + bevel && point[horizontal] < 11 / 16D - bevel;
                    }
                    boolean inArm = pu > 6 / 16D && pu < 10 / 16D && pv > 6 / 16D && pv < 10 / 16D;
                    int coverage = 0;
                    for (double[][] face : faces) {
                        if (contains(face, u, v, pu, pv)) coverage++;
                    }
                    assertEquals("side " + side + " sample " + i + "," + j, inCore && !inArm ? 1 : 0, coverage);
                }
            }
        }
    }

    private static int axis(int side) {
        return side < 2 ? 1 : side < 4 ? 2 : 0;
    }

    private static boolean contains(double[][] face, int u, int v, double pu, double pv) {
        boolean positive = false, negative = false;
        for (int i = 0; i < 4; i++) {
            double[] a = face[i], b = face[(i + 1) % 4];
            double cross = (b[u] - a[u]) * (pv - a[v]) - (b[v] - a[v]) * (pu - a[u]);
            positive |= cross > 1e-12;
            negative |= cross < -1e-12;
        }
        return !(positive && negative);
    }
}
