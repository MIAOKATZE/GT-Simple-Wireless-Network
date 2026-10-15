package com.miaokatze.gtswn.client.render;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.miaokatze.gtswn.client.render.LinkNodeVisuals.Cell;

public class LinkNodeVoltageColorsTest {

    @Test
    public void exactlyNineteenInnerCellsChangeWhileEighteenOuterCellsKeepNodeIdentity() {
        for (boolean energy : new boolean[] { false, true }) {
            int inner = 0, outer = 0;
            for (Cell cell : LinkNodeVisuals.CELLS) {
                if (cell.ring <= 2) {
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
                        if (cell.ring == 3) {
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
