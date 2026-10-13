package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceTerminalLabelLayoutTest {

    private static double[] identity() {
        double[] matrix = new double[16];
        matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1D;
        return matrix;
    }

    @Test
    public void actualBackgroundCornersIncludeNegativeBillboardScale() {
        double[] rectangle = new double[4];
        assertTrue(
            DeviceTerminalLabelLayout
                .project(identity(), new int[] { 0, 0, 800, 600 }, 0, 0, 0, 1, 0, 0, 1, 0, .01D, 10, 27, rectangle));
        assertEquals(360D, rectangle[0], 1E-8D);
        assertEquals(219D, rectangle[1], 1E-8D);
        assertEquals(440D, rectangle[2], 1E-8D);
        assertEquals(279D, rectangle[3], 1E-8D);
    }

    @Test
    public void matrixCompositionUsesProjectionAfterCameraTransform() {
        float[] view = new float[16], projection = new float[16];
        view[0] = view[5] = view[10] = view[15] = 1F;
        view[12] = 2F;
        projection[0] = 3F;
        projection[5] = projection[10] = projection[15] = 1F;
        double[] result = new double[16];
        DeviceTerminalLabelLayout.combine(view, projection, result);
        assertEquals(3D, result[0], 0D);
        assertEquals(6D, result[12], 0D);
        assertEquals(1D, result[15], 0D);
    }

    @Test
    public void labelsBehindCameraOrOutsideViewportDoNotOccupyLayout() {
        double[] projection = identity();
        projection[11] = -1D;
        projection[15] = 0D;
        int[] viewport = { 0, 0, 800, 600 };
        double[] rectangle = new double[4];
        assertFalse(
            DeviceTerminalLabelLayout.project(projection, viewport, 0, 0, 1, 1, 0, 0, 1, 0, .01D, 10, 27, rectangle));
        assertFalse(
            DeviceTerminalLabelLayout
                .project(projection, viewport, 100, 0, -5, 1, 0, 0, 1, 0, .01D, 10, 27, rectangle));
        assertFalse(
            DeviceTerminalLabelLayout
                .project(projection, viewport, Double.NaN, 0, -5, 1, 0, 0, 1, 0, .01D, 10, 27, rectangle));
        assertTrue(
            DeviceTerminalLabelLayout.project(projection, viewport, 0, 0, -5, 1, 0, 0, 1, 0, .01D, 10, 27, rectangle));
    }

    @Test
    public void targetRectangleKeepsPriorityAndThreePixelGap() {
        double[] accepted = { 10, 20, 110, 80 };
        assertFalse(DeviceTerminalLabelLayout.fits(new double[] { 30, 25, 70, 65 }, accepted, 1, 3D));
        assertFalse(DeviceTerminalLabelLayout.fits(new double[] { 112, 20, 150, 80 }, accepted, 1, 3D));
        assertTrue(DeviceTerminalLabelLayout.fits(new double[] { 113, 20, 150, 80 }, accepted, 1, 3D));
        assertTrue(DeviceTerminalLabelLayout.fits(new double[] { 10, 83, 110, 100 }, accepted, 1, 3D));
    }

    @Test
    public void denseCandidatesCannotOverwriteAcceptedLabels() {
        double[] accepted = new double[64 * 4];
        double[] candidate = new double[4];
        int count = 0;
        for (int i = 0; i < 64; i++) {
            candidate[0] = (i % 8) * 8;
            candidate[1] = (i / 8) * 8;
            candidate[2] = candidate[0] + 20;
            candidate[3] = candidate[1] + 20;
            if (DeviceTerminalLabelLayout.fits(candidate, accepted, count, 3D)) {
                System.arraycopy(candidate, 0, accepted, count * 4, 4);
                count++;
            }
        }
        assertEquals(9, count);
        assertEquals(0D, accepted[0], 0D);
        assertEquals(0D, accepted[1], 0D);
    }
}
