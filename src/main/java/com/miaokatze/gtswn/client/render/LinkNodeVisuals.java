package com.miaokatze.gtswn.client.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Pure geometry and animation; shared by all covers and independent of OpenGL. */
public final class LinkNodeVisuals {

    public static final double CELL_RADIUS = .078;
    public static final List<Cell> CELLS = cells();
    private static final int[][] NORMALS = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 },
        { 1, 0, 0 } };

    private LinkNodeVisuals() {}

    public static int resolution(String quality) {
        return "low".equals(quality) ? 32 : 64;
    }

    public static boolean pulseActive(double seconds, boolean low) {
        return phase(seconds, low ? 12 : 10) < (low ? 7 : 5);
    }

    private static double phase(double seconds, double period) {
        return seconds - Math.floor(seconds / period) * period;
    }

    public static double brightness(Cell cell, double seconds, boolean low, boolean energy) {
        double p = phase(seconds, low ? 12 : 10);
        double active = low ? 7 : 5;
        if (p < active) {
            double band = (energy ? 3 - cell.ring : cell.ring) / 3D;
            double arrival = .45 + band * (active - 1.5);
            return Math.max(0, 1 - Math.abs(p - arrival) / .65);
        }
        if (low) return 0;
        // Independent per-cell flash schedules avoid the spatial bands produced by linear phases.
        int seed = ((cell.q + 3) * 7 + cell.r + 3) ^ (int) Math.floor(seconds / 10) * 0x9E3779B9;
        double idle = p - active;
        double light = 0;
        for (int flash = 0; flash < 2; flash++) {
            int event = seed ^ (flash + 1) * 0x85EBCA6B;
            if (randomUnit(event) >= .6) continue;
            double duration = .75 + randomUnit(event ^ 0xC2B2AE35) * .4;
            double start = randomUnit(event ^ 0x27D4EB2F) * (5 - duration);
            double progress = (idle - start) / duration;
            if (progress > 0 && progress < 1) {
                double envelope = Math.sin(progress * Math.PI);
                light = Math.max(light, envelope * envelope);
            }
        }
        return light;
    }

    private static double randomUnit(int seed) {
        seed ^= seed >>> 16;
        seed *= 0x85EBCA6B;
        seed ^= seed >>> 13;
        seed *= 0xC2B2AE35;
        seed ^= seed >>> 16;
        return (seed >>> 8) / 16777216D;
    }

    public static double surfaceDepth(Cell cell, double depth, double relief) {
        double band = cell.ring / 3D;
        return depth * (1 - Math.abs(relief) * (relief >= 0 ? band : 1 - band));
    }

    /** A column terminates at its local model surface; the flat quality has no shell offset. */
    public static double particleDepth(String quality, double u, double v, double depth, double relief, boolean energy,
        double progress) {
        double surface = 0;
        if ("high".equals(quality)) {
            Cell nearest = CELLS.get(0);
            double distance = Double.MAX_VALUE;
            for (Cell cell : CELLS) {
                double du = u - cell.u, dv = v - cell.v;
                double candidate = du * du + dv * dv;
                if (candidate < distance) {
                    distance = candidate;
                    nearest = cell;
                }
            }
            surface = Math.max(.001, surfaceDepth(nearest, depth, relief));
        }
        return .01 + surface + (energy ? 1 - progress : progress) * .5;
    }

    public static double[] normal(int side) {
        return new double[] { NORMALS[side][0], NORMALS[side][1], NORMALS[side][2] };
    }

    /** Face-local coordinates; depth is always outside the original full block. */
    public static double[] point(int side, double u, double v, double depth) {
        double[] n = normal(side);
        double[] a = side < 2 ? new double[] { 1, 0, 0 }
            : side < 4 ? new double[] { 1, 0, 0 } : new double[] { 0, 0, 1 };
        double[] b = side < 2 ? new double[] { 0, 0, 1 } : new double[] { 0, 1, 0 };
        return new double[] { .5 + n[0] * (.5 + depth) + a[0] * u + b[0] * v,
            .5 + n[1] * (.5 + depth) + a[1] * u + b[1] * v, .5 + n[2] * (.5 + depth) + a[2] * u + b[2] * v };
    }

    public static double[] sampleColumn(Random random) {
        double angle = random.nextDouble() * Math.PI * 2;
        double radius = Math.sqrt(random.nextDouble()) * .36;
        return new double[] { Math.cos(angle) * radius, Math.sin(angle) * radius };
    }

    public static double[] velocity(int side, boolean energy, double speed) {
        double[] n = normal(side);
        double sign = energy ? -speed : speed;
        return new double[] { n[0] * sign, n[1] * sign, n[2] * sign };
    }

    /** Both endpoints are invisible, with eight-tick smooth entrance and exit windows. */
    public static double particleAlpha(double age, double life) {
        return smoothStep(age / 8) * smoothStep((life - age) / 8);
    }

    /** Actual render opacity, retaining the original body/glow multipliers and overall transparency. */
    public static double particleOpacity(double age, double life, boolean glow) {
        return particleAlpha(age, life) * (glow ? .22 : .82) * .8;
    }

    private static double smoothStep(double value) {
        double t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }

    public static int[] pixels(int size, double seconds, boolean energy, boolean emissive) {
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double u = (x + .5) / size - .5, v = (y + .5) / size - .5;
                for (Cell cell : CELLS) {
                    double dx = Math.abs(u - cell.u), dy = Math.abs(v - cell.v);
                    double distance = Math.max(dy, (Math.sqrt(3) * dx + dy) / 2);
                    if (distance > CELL_RADIUS * .866) continue;
                    boolean edge = distance > CELL_RADIUS * .866 - 1.1 / size;
                    double light = brightness(cell, seconds, size == 32, energy);
                    int alpha = emissive ? (int) (light * (edge ? 245 : 160)) : edge ? 125 : 38;
                    int red = energy ? 255 : 222, green = energy ? 181 : 74, blue = energy ? 35 : 255;
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

    private static List<Cell> cells() {
        List<Cell> cells = new ArrayList<>();
        for (int q = -3; q <= 3; q++) {
            for (int r = -3; r <= 3; r++) {
                int ring = Math.max(Math.abs(q), Math.max(Math.abs(r), Math.abs(q + r)));
                if (ring <= 3) cells.add(new Cell(q, r, ring));
            }
        }
        return java.util.Collections.unmodifiableList(cells);
    }

    public static final class Cell {

        public final int q, r, ring;
        public final double u, v;

        private Cell(int q, int r, int ring) {
            this.q = q;
            this.r = r;
            this.ring = ring;
            u = CELL_RADIUS * 1.5 * q;
            v = CELL_RADIUS * Math.sqrt(3) * (r + q * .5);
        }
    }
}
