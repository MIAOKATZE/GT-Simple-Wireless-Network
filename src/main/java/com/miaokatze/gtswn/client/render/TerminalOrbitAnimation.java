package com.miaokatze.gtswn.client.render;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.resources.data.AnimationMetadataSection;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;

/** Rebuilds only the terminal screen's particles; casing and lamp keep their source timing. */
final class TerminalOrbitAnimation {

    final BufferedImage image;
    final AnimationMetadataSection metadata;

    private TerminalOrbitAnimation(BufferedImage image) {
        this.image = image;
        metadata = new AnimationMetadataSection(Collections.emptyList(), 16, 16, 1);
    }

    static TerminalOrbitAnimation create(BufferedImage source, AnimationMetadataSection metadata, int color) {
        if (source.getWidth() != 16 || source.getHeight() % 16 != 0) {
            throw new IllegalArgumentException("Terminal animation requires 16 pixel frames");
        }
        int frameCount = source.getHeight() / 16;
        List<Integer> timeline = new ArrayList<>();
        int entries = metadata == null || metadata.getFrameCount() == 0 ? frameCount : metadata.getFrameCount();
        for (int entry = 0; entry < entries; entry++) {
            int frame = metadata == null || metadata.getFrameCount() == 0 ? entry : metadata.getFrameIndex(entry);
            int duration = metadata == null ? 1
                : metadata.getFrameCount() == 0 ? metadata.getFrameTime() : metadata.getFrameTimeSingle(entry);
            if (frame < 0 || frame >= frameCount || duration < 1 || timeline.size() + duration > 4096) {
                throw new IllegalArgumentException("Invalid terminal animation timeline");
            }
            for (int tick = 0; tick < duration; tick++) timeline.add(frame);
        }
        int[][] background = new int[8][10];
        for (int y = 5; y <= 12; y++) {
            for (int x = 3; x <= 12; x++) {
                // Median of non-particle samples restores the actual pink screen, without old orbit ghosts.
                int[] samples = new int[frameCount];
                int count = 0;
                for (int frame = 0; frame < frameCount; frame++) {
                    int pixel = source.getRGB(x, frame * 16 + y);
                    if (!oldParticleMask(source, x, y, frame)) samples[count++] = pixel;
                }
                if (count == 0) throw new IllegalArgumentException("Terminal has no clean screen sample");
                Arrays.sort(samples, 0, count);
                background[y - 5][x - 3] = samples[count / 2];
            }
        }
        int[][][] particles = new int[2][4][5];
        for (int particle = 0; particle < 2; particle++) {
            int left = particle == 0 ? 3 : 8, top = particle == 0 ? 6 : 8;
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 5; x++) {
                    if (oldParticleMask(source, left + x, top + y, 0)) {
                        particles[particle][y][x] = tint(source.getRGB(left + x, top + y), color);
                    }
                }
            }
        }
        BufferedImage result = new BufferedImage(16, timeline.size() * 16, BufferedImage.TYPE_INT_ARGB);
        for (int tick = 0; tick < timeline.size(); tick++) {
            int frame = timeline.get(tick);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int pixel = source.getRGB(x, frame * 16 + y);
                    if (insideScreen(x, y)) {
                        if (oldParticleMask(source, x, y, frame)) {
                            pixel = (pixel & 0xFF000000) | (background[y - 5][x - 3] & 0xFFFFFF);
                        }
                        for (int particle = 0; particle < 2; particle++) {
                            double[] center = position(tick, timeline.size(), particle);
                            int localX = x - (int) Math.round(center[0] - 2);
                            int localY = y - (int) Math.round(center[1] - 1.5);
                            if (localX >= 0 && localX < 5
                                && localY >= 0
                                && localY < 4
                                && particles[particle][localY][localX] != 0) {
                                pixel = (pixel & 0xFF000000) | (particles[particle][localY][localX] & 0xFFFFFF);
                            }
                        }
                    }
                    result.setRGB(x, tick * 16 + y, pixel);
                }
            }
        }
        return new TerminalOrbitAnimation(result);
    }

    static boolean insideScreen(int x, int y) {
        return x >= 3 && x <= 12 && y >= 5 && y <= 12;
    }

    static boolean oldParticleMask(BufferedImage source, int x, int y, int frame) {
        int pixel = source.getRGB(x, frame * 16 + y);
        if (oldParticle(pixel)) return true;
        // The original particles also have pale pink glints next to their saturated core.
        int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
        if (r < 224 || g < 135 || b < 224) return false;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (insideScreen(x + dx, y + dy) && oldParticle(source.getRGB(x + dx, frame * 16 + y + dy)))
                    return true;
            }
        }
        return false;
    }

    private static boolean oldParticle(int pixel) {
        float[] hsv = Color.RGBtoHSB(pixel >> 16 & 255, pixel >> 8 & 255, pixel & 255, null);
        return hsv[1] > 0.5f;
    }

    static double[] position(int tick, int period, int particle) {
        // The normal orbit holds each pose for four ticks; Alt retains a pose every tick.
        tick = Math.floorMod(tick, period);
        if (period == 80) tick = tick / 4 * 4;
        double angle = 2 * Math.PI * tick / period + Math.PI * 1.15 + particle * Math.PI;
        return new double[] { 7.5 + 2.5 * Math.cos(angle), 8.5 + 2 * Math.sin(angle) };
    }

    static double coverage(int x, int y, int tick, int period) {
        String[][] masks = { { ".###.", "#####", ".####", "..##." }, { ".##..", "####.", "#####", ".###." } };
        for (int particle = 0; particle < 2; particle++) {
            double[] center = position(tick, period, particle);
            int localX = x - (int) Math.round(center[0] - 2);
            int localY = y - (int) Math.round(center[1] - 1.5);
            if (localX >= 0 && localX < 5 && localY >= 0 && localY < 4 && masks[particle][localY].charAt(localX) == '#')
                return 1;
        }
        return 0;
    }

    private static int tint(int pixel, int color) {
        if (color == QuantumNetworkColor.rgb(QuantumNetworkColor.DEFAULT)) return pixel;
        float[] original = Color.RGBtoHSB(pixel >> 16 & 255, pixel >> 8 & 255, pixel & 255, null);
        float[] target = Color.RGBtoHSB(color >> 16 & 255, color >> 8 & 255, color & 255, null);
        // Preserve the source edge saturation and brightness, including pale corner glints.
        float saturation = original[1] * target[1];
        float brightness = original[2] * (0.35f + 0.65f * target[2]);
        return (pixel & 0xFF000000) | (Color.HSBtoRGB(target[0], saturation, brightness) & 0xFFFFFF);
    }
}
