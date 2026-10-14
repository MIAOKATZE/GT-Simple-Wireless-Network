package com.miaokatze.gtswn.client;

/** Allocation-free screen rectangles for the world renderer's existing billboard transform. */
public final class DeviceTerminalLabelLayout {

    private DeviceTerminalLabelLayout() {}

    /** OpenGL column-major projection * modelview, calculated once for all labels in a frame. */
    public static void combine(float[] modelview, float[] projection, double[] result) {
        for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
            double value = 0D;
            for (int k = 0; k < 4; k++) value += projection[k * 4 + row] * modelview[column * 4 + k];
            result[column * 4 + row] = value;
        }
    }

    /** Projects the exact four background corners, returning the visible viewport intersection. */
    public static boolean project(double[] matrix, int[] viewport, double x, double y, double z, double rx, double rz,
        double ux, double uy, double uz, double scale, int halfWidth, int bottom, double[] rectangle) {
        rectangle[0] = rectangle[1] = Double.POSITIVE_INFINITY;
        rectangle[2] = rectangle[3] = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            double sx = -scale * (corner < 2 ? -halfWidth : halfWidth);
            double sy = -scale * ((corner & 1) == 0 ? 7 : bottom);
            double px = x + rx * sx + ux * sy, py = y + uy * sy, pz = z + rz * sx + uz * sy;
            double w = matrix[3] * px + matrix[7] * py + matrix[11] * pz + matrix[15];
            if (!Double.isFinite(w) || w <= 1E-6D) return false;
            double screenX = viewport[0]
                + (1D + (matrix[0] * px + matrix[4] * py + matrix[8] * pz + matrix[12]) / w) * viewport[2] * .5D;
            double screenY = viewport[1]
                + (1D + (matrix[1] * px + matrix[5] * py + matrix[9] * pz + matrix[13]) / w) * viewport[3] * .5D;
            if (!Double.isFinite(screenX) || !Double.isFinite(screenY)) return false;
            rectangle[0] = Math.min(rectangle[0], screenX);
            rectangle[1] = Math.min(rectangle[1], screenY);
            rectangle[2] = Math.max(rectangle[2], screenX);
            rectangle[3] = Math.max(rectangle[3], screenY);
        }
        rectangle[0] = Math.max(rectangle[0], viewport[0]);
        rectangle[1] = Math.max(rectangle[1], viewport[1]);
        rectangle[2] = Math.min(rectangle[2], viewport[0] + viewport[2]);
        rectangle[3] = Math.min(rectangle[3], viewport[1] + viewport[3]);
        return rectangle[0] < rectangle[2] && rectangle[1] < rectangle[3];
    }

    /** Rectangles are packed left,bottom,right,top; accepted entries retain target/nearest priority. */
    public static boolean fits(double[] candidate, double[] accepted, int count, double margin) {
        for (int i = 0; i < count * 4; i += 4) {
            if (candidate[0] < accepted[i + 2] + margin && candidate[2] + margin > accepted[i]
                && candidate[1] < accepted[i + 3] + margin
                && candidate[3] + margin > accepted[i + 1]) return false;
        }
        return true;
    }
}
