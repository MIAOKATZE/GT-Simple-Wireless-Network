package com.miaokatze.gtswn.client.render;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

import com.miaokatze.gtswn.client.render.LinkNodeVisuals.Cell;

public class LinkNodeVoltageColorsTest {

    @Test
    public void exactlyNineteenInnerCellsChangeWhileEighteenOuterCellsKeepNodeIdentity() {
        for (boolean energy : new boolean[] { false, true }) {
            int inner = 0, outer = 0;
            for (Cell cell : LinkNodeVisuals.CELLS) {
                if (cell.ring < 3) {
                    inner++;
                    assertEquals(0x55FF55, LinkNodeVisuals.cellRgb(cell.ring, energy, 0x55FF55));
                } else {
                    outer++;
                    assertEquals(energy ? 0xFFB523 : 0xDE4AFF, LinkNodeVisuals.cellRgb(cell.ring, energy, 0x55FF55));
                }
            }
            assertEquals(19, inner);
            assertEquals(18, outer);
        }
    }

    @Test
    public void baseAndGlowUseTheSelectedTierOnEveryInnerRingWithoutChangingAlphaOrPulse() {
        for (boolean energy : new boolean[] { false, true }) {
            for (boolean glow : new boolean[] { false, true }) {
                for (double seconds : new double[] { .45, 1.6, 2.8, 3.95, 6.2 }) {
                    int[] old = LinkNodeVisuals.pixels(64, seconds, energy, glow);
                    int[] green = LinkNodeVisuals.pixels(64, seconds, energy, glow, 0x55FF55);
                    int[] blue = LinkNodeVisuals.pixels(64, seconds, energy, glow, 0x5555FF);
                    for (int pixel = 0; pixel < old.length; pixel++) {
                        assertEquals(old[pixel] >>> 24, green[pixel] >>> 24);
                        assertEquals(old[pixel] >>> 24, blue[pixel] >>> 24);
                    }
                    for (Cell cell : LinkNodeVisuals.CELLS) {
                        int pixel = (int) ((cell.v + .5) * 64) * 64 + (int) ((cell.u + .5) * 64);
                        if (cell.ring >= 3) {
                            assertEquals(old[pixel], green[pixel]);
                            assertEquals(old[pixel], blue[pixel]);
                        } else {
                            // Glow retains the existing light-dependent move towards white.
                            int base = glow
                                ? 85 + (int) (170 * LinkNodeVisuals.brightness(cell, seconds, false, energy) * .65)
                                : 85;
                            assertEquals(base << 16 | 255 << 8 | base, green[pixel] & 0xFFFFFF);
                            assertEquals(base << 16 | base << 8 | 255, blue[pixel] & 0xFFFFFF);
                            assertTrue(green[pixel] != blue[pixel]);
                        }
                    }
                    // Generating another node's palette must not mutate this node's pixels.
                    assertArrayEquals(green, LinkNodeVisuals.pixels(64, seconds, energy, glow, 0x55FF55));
                }
            }
        }
    }

    @Test
    public void particlesUseVoltageOnlyWithinSeventyFivePercentOfTheColumnRadius() {
        double voltageRadius = LinkNodeVisuals.particleVoltageRadius(75);
        assertEquals(.27, voltageRadius, 1e-12);
        for (int step = 0; step < 24; step++) {
            double angle = step * Math.PI / 12;
            double u = Math.cos(angle), v = Math.sin(angle);
            assertEquals(
                0x00AA00,
                LinkNodeVisuals.particleVoltageRgb(u * voltageRadius * .999, v * voltageRadius * .999, 0x00AA00));
            assertEquals(
                -1,
                LinkNodeVisuals.particleVoltageRgb(u * voltageRadius * 1.001, v * voltageRadius * 1.001, 0x00AA00));
        }
        assertEquals(0x00AA00, LinkNodeVisuals.particleVoltageRgb(voltageRadius, 0, 0x00AA00));
        assertEquals(-1, LinkNodeVisuals.particleVoltageRgb(.2, .2, 0x00AA00));
        assertEquals(-1, LinkNodeVisuals.particleVoltageRgb(0, 0, -1));
        assertEquals(-1, LinkNodeVisuals.particleVoltageRgb(voltageRadius, 0, -1));
        Random random = new Random(69);
        int colored = 0;
        for (int i = 0; i < 10000; i++) {
            double[] xy = LinkNodeVisuals.sampleColumn(random);
            if (LinkNodeVisuals.particleVoltageRgb(xy[0], xy[1], 0x00AA00) >= 0) colored++;
        }
        // 75% of the radius colors 56.25% of a uniformly sampled circular cross-section.
        assertTrue(colored > 5400 && colored < 5850);
        for (String material : new String[] { "high", "medium", "low", "old", "oldplus" }) {
            assertEquals(
                -1,
                LinkNodeVisuals.particleVoltageRgb(0, 0, LinkNodeVisuals.voltageRgb(material, false, 0x00AA00)));
            if (!"high".equals(material) && !"medium".equals(material)) {
                assertEquals(
                    -1,
                    LinkNodeVisuals.particleVoltageRgb(0, 0, LinkNodeVisuals.voltageRgb(material, true, 0x00AA00)));
            }
        }
    }

    @Test
    public void allFourProportionsLinkMaterialBandsAndParticleRadiusWithoutChangingAlpha() {
        int[] percentages = { 25, 50, 75, 100 };
        int[] coloredCells = { 1, 7, 19, 37 };
        for (int option = 0; option < percentages.length; option++) {
            int percent = percentages[option];
            assertEquals(option + 1, LinkNodeVisuals.voltageRings(percent));
            double radius = .36 * percent / 100D;
            assertEquals(radius, LinkNodeVisuals.particleVoltageRadius(percent), 1e-12);
            int colored = 0;
            for (Cell cell : LinkNodeVisuals.CELLS) {
                if (LinkNodeVisuals.cellRgb(cell.ring, true, 0x00AA00, percent) == 0x00AA00) colored++;
            }
            assertEquals(coloredCells[option], colored);
            for (int step = 0; step < 24; step++) {
                double angle = step * Math.PI / 12;
                assertEquals(
                    0x00AA00,
                    LinkNodeVisuals.particleVoltageRgb(
                        Math.cos(angle) * radius * .999,
                        Math.sin(angle) * radius * .999,
                        0x00AA00,
                        percent));
                assertEquals(
                    -1,
                    LinkNodeVisuals.particleVoltageRgb(
                        Math.cos(angle) * radius * 1.001,
                        Math.sin(angle) * radius * 1.001,
                        0x00AA00,
                        percent));
            }
            for (boolean energy : new boolean[] { false, true }) {
                for (boolean glow : new boolean[] { false, true }) {
                    int[] original = LinkNodeVisuals.pixels(64, 2.8, energy, glow);
                    int[] tinted = LinkNodeVisuals.pixels(64, 2.8, energy, glow, 0x00AA00, percent);
                    for (int pixel = 0; pixel < original.length; pixel++) {
                        assertEquals(original[pixel] >>> 24, tinted[pixel] >>> 24);
                    }
                    for (Cell cell : LinkNodeVisuals.CELLS) {
                        int pixel = (int) ((cell.v + .5) * 64) * 64 + (int) ((cell.u + .5) * 64);
                        if (cell.ring >= option + 1) assertEquals(original[pixel], tinted[pixel]);
                        else assertTrue(original[pixel] != tinted[pixel]);
                    }
                    LinkNodeVisuals.pixels(64, 2.8, energy, glow, 0x5555FF, 100 - option * 25);
                    assertArrayEquals(tinted, LinkNodeVisuals.pixels(64, 2.8, energy, glow, 0x00AA00, percent));
                    assertArrayEquals(original, LinkNodeVisuals.pixels(64, 2.8, energy, glow, -1, percent));
                    assertArrayEquals(
                        LinkNodeVisuals.pixels(32, 2.8, energy, glow),
                        LinkNodeVisuals.pixels(32, 2.8, energy, glow, 0x00AA00, percent));
                }
            }
        }
        assertEquals(1, LinkNodeVisuals.voltageRings(Integer.MIN_VALUE));
        assertEquals(2, LinkNodeVisuals.voltageRings(26));
        assertEquals(3, LinkNodeVisuals.voltageRings(51));
        assertEquals(4, LinkNodeVisuals.voltageRings(Integer.MAX_VALUE));
    }

    @Test
    public void onlyHighAndMediumEnableVoltageAndTheSwitchRestoresEveryOriginalPixel() {
        for (String material : new String[] { "high", "medium", "low", "old", "oldplus" }) {
            assertEquals(-1, LinkNodeVisuals.voltageRgb(material, false, 0x55FF55));
            boolean enhanced = "high".equals(material) || "medium".equals(material);
            assertEquals(enhanced ? 0x55FF55 : -1, LinkNodeVisuals.voltageRgb(material, true, 0x55FF55));
            assertEquals(-1, LinkNodeVisuals.voltageRgb(material, true, -1));
            for (boolean energy : new boolean[] { false, true }) {
                for (boolean glow : new boolean[] { false, true }) {
                    int size = LinkNodeVisuals.resolution(material);
                    assertArrayEquals(
                        LinkNodeVisuals.pixels(size, 2.3, energy, glow),
                        LinkNodeVisuals
                            .pixels(size, 2.3, energy, glow, LinkNodeVisuals.voltageRgb(material, false, 0x55FF55)));
                    if (!enhanced) assertArrayEquals(
                        LinkNodeVisuals.pixels(size, 2.3, energy, glow),
                        LinkNodeVisuals.pixels(size, 2.3, energy, glow, 0x55FF55));
                }
            }
        }
    }
}
