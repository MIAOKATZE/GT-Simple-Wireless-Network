package com.miaokatze.gtswn.client.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Pure geometry and animation; shared by all covers and independent of OpenGL. */
public final class LinkNodeVisuals {

    public static final double CELL_RADIUS = .078;
    public static final double COLUMN_RADIUS = .36;
    public static final List<Cell> CELLS = cells();
    private static final int[][] NORMALS = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 },
        { 1, 0, 0 } };
    private static final Raster RASTER_16 = new Raster(16), RASTER_32 = new Raster(32), RASTER_64 = new Raster(64);

    private LinkNodeVisuals() {}

    public static int resolution(String quality) {
        if ("old".equals(quality) || "oldplus".equals(quality)) return 16;
        return "low".equals(quality) ? 32 : 64;
    }

    public static boolean legacyMaterial(String material) {
        return "old".equals(material) || "oldplus".equals(material);
    }

    public static int voltageRgb(String material, boolean enabled, int rgb) {
        return enabled && ("high".equals(material) || "medium".equals(material)) ? rgb : -1;
    }

    public static boolean voltageCell(int ring, int voltageRgb) {
        return voltageCell(ring, voltageRgb, 75);
    }

    public static int voltageRings(int percent) {
        return (Math.max(25, Math.min(100, percent)) + 24) / 25;
    }

    public static boolean voltageCell(int ring, int voltageRgb, int percent) {
        return ring < voltageRings(percent) && voltageRgb >= 0;
    }

    /** The default 75% colors the inner three rings; configurable proportions follow the same radial bands. */
    public static int cellRgb(int ring, boolean energy, int voltageRgb) {
        return cellRgb(ring, energy, voltageRgb, 75);
    }

    public static int cellRgb(int ring, boolean energy, int voltageRgb, int percent) {
        return voltageCell(ring, voltageRgb, percent) ? voltageRgb : energy ? 0xFFB523 : 0xDE4AFF;
    }

    public static double particleVoltageRadius(int percent) {
        return COLUMN_RADIUS * voltageRings(percent) / 4D;
    }

    /** Particle centers follow the selected voltage-color proportion; the surrounding column keeps its type color. */
    public static int particleVoltageRgb(double u, double v, int voltageRgb) {
        return particleVoltageRgb(u, v, voltageRgb, 75);
    }

    public static int particleVoltageRgb(double u, double v, int voltageRgb, int percent) {
        double radius = particleVoltageRadius(percent);
        return u * u + v * v <= radius * radius ? voltageRgb : -1;
    }

    /** High and Medium share the same textures, with High adding geometry. */
    public static int textureIndex(String material) {
        return "old".equals(material) ? 2 : "oldplus".equals(material) ? 3 : "low".equals(material) ? 0 : 1;
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
        return new double[] { pointCoordinate(side, 0, u, v, depth), pointCoordinate(side, 1, u, v, depth),
            pointCoordinate(side, 2, u, v, depth) };
    }

    /** Same arithmetic and face basis as point(), without allocating temporary vectors per vertex. */
    public static double pointCoordinate(int side, int axis, double u, double v, double depth) {
        int a = axis == (side < 4 ? 0 : 2) ? 1 : 0;
        int b = axis == (side < 2 ? 2 : 1) ? 1 : 0;
        return .5 + NORMALS[side][axis] * (.5 + depth) + a * u + b * v;
    }

    public static double[] sampleColumn(Random random) {
        double angle = random.nextDouble() * Math.PI * 2;
        double radius = Math.sqrt(random.nextDouble()) * COLUMN_RADIUS;
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
        return pixels(size, seconds, energy, emissive, -1);
    }

    public static int[] pixels(int size, double seconds, boolean energy, boolean emissive, int voltageRgb) {
        return pixels(size, seconds, energy, emissive, voltageRgb, 75);
    }

    public static int[] pixels(int size, double seconds, boolean energy, boolean emissive, int voltageRgb,
        int percent) {
        int[] pixels = new int[size * size];
        writePixels(size, seconds, energy, voltageRgb, percent, emissive ? null : pixels, emissive ? pixels : null);
        return pixels;
    }

    /** Fill existing texture buffers; only pixel ownership is cached, never colors or animation time. */
    public static void writePixels(int size, double seconds, boolean energy, int voltageRgb, int percent, int[] base,
        int[] glow) {
        Raster raster = size == 64 ? RASTER_64 : size == 32 ? RASTER_32 : size == 16 ? RASTER_16 : new Raster(size);
        if (base != null) java.util.Arrays.fill(base, 0);
        if (glow != null) java.util.Arrays.fill(glow, 0);
        for (int i = 0; i < CELLS.size(); i++) {
            Cell cell = CELLS.get(i);
            double light = brightness(cell, seconds, size == 32, energy);
            int rgb = cellRgb(cell.ring, energy, size == 64 ? voltageRgb : -1, percent);
            int red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
            red += (int) ((255 - red) * light * .65);
            green += (int) ((255 - green) * light * .65);
            blue += (int) ((255 - blue) * light * .65);
            int glowRgb = red << 16 | green << 8 | blue;
            for (int edge = 0; edge < 2; edge++) {
                int basePixel = (edge == 1 ? 125 : 38) << 24 | rgb;
                int glowPixel = (int) (light * (edge == 1 ? 245 : 160)) << 24 | glowRgb;
                for (int pixel : raster.pixels[i][edge]) {
                    if (base != null) base[pixel] = basePixel;
                    if (glow != null) glow[pixel] = glowPixel;
                }
            }
        }
    }

    private static final class Raster {

        private final int[][][] pixels = new int[CELLS.size()][2][];

        private Raster(int size) {
            int[] owners = new int[size * size];
            java.util.Arrays.fill(owners, -1);
            int[][] counts = new int[CELLS.size()][2];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double u = (x + .5) / size - .5, v = (y + .5) / size - .5;
                    for (int i = 0; i < CELLS.size(); i++) {
                        Cell cell = CELLS.get(i);
                        double dx = Math.abs(u - cell.u), dy = Math.abs(v - cell.v);
                        double distance = Math.max(dy, (Math.sqrt(3) * dx + dy) / 2);
                        if (distance > CELL_RADIUS * .866) continue;
                        int edge = distance > CELL_RADIUS * .866 - 1.1 / size ? 1 : 0;
                        owners[y * size + x] = i * 2 + edge;
                        counts[i][edge]++;
                        break;
                    }
                }
            }
            for (int i = 0; i < pixels.length; i++) {
                for (int edge = 0; edge < 2; edge++) pixels[i][edge] = new int[counts[i][edge]];
            }
            for (int pixel = owners.length - 1; pixel >= 0; pixel--) {
                int owner = owners[pixel];
                if (owner >= 0) pixels[owner / 2][owner % 2][--counts[owner / 2][owner % 2]] = pixel;
            }
        }
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
        private final double[] surfaceU = new double[7], surfaceV = new double[7], wallU = new double[7],
            wallV = new double[7];

        private Cell(int q, int r, int ring) {
            this.q = q;
            this.r = r;
            this.ring = ring;
            u = CELL_RADIUS * 1.5 * q;
            v = CELL_RADIUS * Math.sqrt(3) * (r + q * .5);
            for (int edge = 0; edge <= 6; edge++) {
                double angle = edge * Math.PI / 3;
                surfaceU[edge] = u + Math.cos(angle) * CELL_RADIUS;
                surfaceV[edge] = v + Math.sin(angle) * CELL_RADIUS;
                wallU[edge] = u + Math.cos(angle) * (CELL_RADIUS * .93);
                wallV[edge] = v + Math.sin(angle) * (CELL_RADIUS * .93);
            }
        }

        public double surfaceU(int edge) {
            return surfaceU[edge];
        }

        public double surfaceV(int edge) {
            return surfaceV[edge];
        }

        public double wallU(int edge) {
            return wallU[edge];
        }

        public double wallV(int edge) {
            return wallV[edge];
        }
    }
}
