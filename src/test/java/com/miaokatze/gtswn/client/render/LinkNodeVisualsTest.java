package com.miaokatze.gtswn.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class LinkNodeVisualsTest {

    @Test
    public void particleEntranceAndExitEachUseEightSmoothTicks() {
        for (int life = 24; life <= 40; life++) {
            assertEquals(0, LinkNodeVisuals.particleAlpha(0, life), 0);
            assertEquals(0, LinkNodeVisuals.particleAlpha(life, life), 0);
            assertEquals(0, LinkNodeVisuals.particleAlpha(-1, life), 0);
            assertEquals(0, LinkNodeVisuals.particleAlpha(life + 1, life), 0);
            assertEquals(.5, LinkNodeVisuals.particleAlpha(4, life), 1e-12);
            assertEquals(.5, LinkNodeVisuals.particleAlpha(life - 4, life), 1e-12);
            assertEquals(1, LinkNodeVisuals.particleAlpha(8, life), 0);
            assertEquals(1, LinkNodeVisuals.particleAlpha(life - 8, life), 0);
            for (double age = .25; age <= 8; age += .25) {
                assertTrue(LinkNodeVisuals.particleAlpha(age, life) > LinkNodeVisuals.particleAlpha(age - .25, life));
                assertEquals(
                    LinkNodeVisuals.particleAlpha(age, life),
                    LinkNodeVisuals.particleAlpha(life - age, life),
                    1e-12);
            }
            // Smoothstep has a flat tangent at both invisible endpoints and the full-opacity joins.
            assertTrue(LinkNodeVisuals.particleAlpha(.001, life) / .001 < .001);
            assertTrue((1 - LinkNodeVisuals.particleAlpha(7.999, life)) / .001 < .001);
        }
    }

    @Test
    public void energyArrivalGetsVisiblyBrighterInsideFadeWindowBeforeVanishingAtSurface() {
        for (int life = 24; life <= 40; life++) {
            double body = particleGlow(life / 2D, life, true);
            double enteringFade = particleGlow(life - 8, life, true);
            double nearSurface = particleGlow(life - 6, life, true);
            assertTrue(enteringFade > body);
            assertTrue(nearSurface > enteringFade * 1.25);
            assertTrue(particleGlow(life - 4, life, true) > body * 2);
            double peak = 0, peakAge = 0;
            for (double age = 0; age <= life; age += .125) {
                double glow = particleGlow(age, life, true);
                if (glow > peak) {
                    peak = glow;
                    peakAge = age;
                }
            }
            assertTrue(life - peakAge > 4 && life - peakAge < 8);
            double distance = .5 * (1 - peakAge / life);
            assertTrue(distance < .15);
            for (double age = life - 4; age < life; age += .125) {
                assertTrue(particleGlow(age + .125, life, true) < particleGlow(age, life, true));
            }
            assertEquals(0, particleGlow(life, life, true), 0);
        }
    }

    @Test
    public void dynamoDepartureIsBrightThenNormalThenFadesAtDistantEndpoint() {
        for (int life = 24; life <= 40; life++) {
            assertEquals(0, particleGlow(0, life, false), 0);
            assertTrue(particleGlow(6, life, false) > particleGlow(12, life, false) * 1.5);
            assertEquals(1, particleGlow(12, life, false), 0);
            assertEquals(1, particleGlow(life - 8, life, false), 0);
            assertEquals(.5, particleGlow(life - 4, life, false), 1e-12);
            assertEquals(0, particleGlow(life, life, false), 0);
            assertEquals(0, LinkNodeVisuals.particleColorMix(12, life, false), 0);
        }
    }

    @Test
    public void particleColorsAndRenderedGlowRemainBoundedThroughoutAllLifetimes() {
        for (int life = 24; life <= 40; life++) {
            double previousEnergyMix = 0, previousDynamoMix = .35;
            for (double age = 0; age <= life; age += .125) {
                double energyMix = LinkNodeVisuals.particleColorMix(age, life, true);
                double dynamoMix = LinkNodeVisuals.particleColorMix(age, life, false);
                assertTrue(energyMix >= previousEnergyMix && energyMix <= .35);
                assertTrue(dynamoMix <= previousDynamoMix && dynamoMix >= 0);
                previousEnergyMix = energyMix;
                previousDynamoMix = dynamoMix;
                for (boolean energy : new boolean[] { false, true }) {
                    double alpha = particleGlow(age, life, energy) * .22 * .8;
                    assertTrue(alpha >= 0 && alpha <= 1);
                    assertEquals(alpha, LinkNodeVisuals.particleOpacity(age, life, energy, true), 1e-12);
                    assertTrue(LinkNodeVisuals.particleOpacity(age, life, energy, false) <= .82 * .8);
                }
            }
        }
    }

    private static double particleGlow(double age, double life, boolean energy) {
        return LinkNodeVisuals.particleAlpha(age, life) * LinkNodeVisuals.particleGlowStrength(age, life, energy);
    }

    @Test
    public void highAndMediumShareProfileWhileLowAddsThirdRing() {
        assertEquals(64, LinkNodeVisuals.resolution("high"));
        assertEquals(64, LinkNodeVisuals.resolution("medium"));
        assertEquals(32, LinkNodeVisuals.resolution("low"));
        assertEquals(37, LinkNodeVisuals.CELLS.size());
        assertEquals(
            18,
            LinkNodeVisuals.CELLS.stream()
                .filter(cell -> cell.ring == 3)
                .count());
    }

    @Test
    public void pulseIsSynchronousWithinRingsAndLowRestsWithoutRandomFlashes() {
        for (boolean low : new boolean[] { false, true }) {
            double active = low ? 7 : 5;
            for (double seconds = 0; seconds < active; seconds += .13) {
                double[] bands = { -1, -1, -1, -1 };
                for (LinkNodeVisuals.Cell cell : LinkNodeVisuals.CELLS) {
                    double brightness = LinkNodeVisuals.brightness(cell, seconds, low, true);
                    if (bands[cell.ring] < 0) bands[cell.ring] = brightness;
                    assertEquals(bands[cell.ring], brightness, 0);
                }
            }
        }
        assertFalse(LinkNodeVisuals.pulseActive(6, false));
        assertFalse(LinkNodeVisuals.pulseActive(10, true));
        assertTrue(LinkNodeVisuals.pulseActive(12, true));
        for (double t = 7; t < 12; t += .11) {
            for (LinkNodeVisuals.Cell cell : LinkNodeVisuals.CELLS) {
                assertEquals(0, LinkNodeVisuals.brightness(cell, t, true, false), 0);
            }
        }
    }

    @Test
    public void energyPulseMovesInwardAndDynamoPulseMovesOutwardWithAQuietGap() {
        for (boolean low : new boolean[] { false, true }) {
            for (boolean energy : new boolean[] { false, true }) {
                double[] peaks = new double[4];
                for (int ring = 0; ring < 4; ring++) {
                    final int band = ring;
                    LinkNodeVisuals.Cell cell = LinkNodeVisuals.CELLS.stream()
                        .filter(candidate -> candidate.ring == band)
                        .findFirst()
                        .get();
                    double best = -1;
                    for (int frame = 0; frame < (low ? 700 : 500); frame++) {
                        double t = frame / 100D;
                        double value = LinkNodeVisuals.brightness(cell, t, low, energy);
                        if (value > best) {
                            best = value;
                            peaks[ring] = t;
                        }
                    }
                    assertTrue(best > .99);
                }
                for (int ring = 0; ring < 3; ring++) {
                    assertTrue(energy ? peaks[ring] > peaks[ring + 1] : peaks[ring] < peaks[ring + 1]);
                }
                for (LinkNodeVisuals.Cell cell : LinkNodeVisuals.CELLS) {
                    // All traveling bands have finished but the pulse gate is still active:
                    // any random contribution leaking through would spoil this dark window.
                    assertTrue(LinkNodeVisuals.pulseActive(low ? 6.9 : 4.8, low));
                    assertEquals(0, LinkNodeVisuals.brightness(cell, low ? 6.9 : 4.8, low, energy), 0);
                }
            }
        }
        boolean idleFlash = false, idleVariety = false;
        for (double t = 5; t < 10; t += .05) {
            double first = LinkNodeVisuals.brightness(LinkNodeVisuals.CELLS.get(0), t, false, true);
            for (LinkNodeVisuals.Cell cell : LinkNodeVisuals.CELLS) {
                double brightness = LinkNodeVisuals.brightness(cell, t, false, true);
                idleFlash |= brightness > .5;
                idleVariety |= Math.abs(first - brightness) > .1;
            }
        }
        assertTrue(idleFlash && idleVariety);
    }

    @Test
    public void highAndMediumIdleLastsFiveSecondsAndResamplesBetweenCycles() {
        assertFalse(LinkNodeVisuals.pulseActive(5, false));
        assertFalse(LinkNodeVisuals.pulseActive(8, false));
        assertFalse(LinkNodeVisuals.pulseActive(9.999, false));
        assertTrue(LinkNodeVisuals.pulseActive(10, false));
        int changed = 0;
        boolean extendedIntervalLit = false;
        for (double t = 5.1; t < 10; t += .13) {
            for (LinkNodeVisuals.Cell cell : LinkNodeVisuals.CELLS) {
                double first = LinkNodeVisuals.brightness(cell, t, false, true);
                double next = LinkNodeVisuals.brightness(cell, t + 10, false, true);
                if (Math.abs(first - next) > .25) changed++;
                if (t >= 8 && first > .8) extendedIntervalLit = true;
            }
        }
        assertTrue(changed > 100);
        assertTrue(extendedIntervalLit);
    }

    @Test
    public void idleFlashesDoNotFormSpatiallyCorrelatedColumns() {
        // Linear q/r sine phases correlate every second cell along hex-grid columns.
        // Measure brightness covariance across many cycles in all three grid directions.
        for (int[] offset : new int[][] { { 2, 0 }, { 0, 2 }, { 2, -2 } }) {
            double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            int samples = 0;
            for (LinkNodeVisuals.Cell first : LinkNodeVisuals.CELLS) {
                for (LinkNodeVisuals.Cell second : LinkNodeVisuals.CELLS) {
                    if (second.q != first.q + offset[0] || second.r != first.r + offset[1]) continue;
                    for (int cycle = 0; cycle < 80; cycle++) {
                        for (double idle = .1; idle < 4.9; idle += .2) {
                            double t = cycle * 10 + 5 + idle;
                            double x = LinkNodeVisuals.brightness(first, t, false, true);
                            double y = LinkNodeVisuals.brightness(second, t, false, true);
                            sx += x;
                            sy += y;
                            sxx += x * x;
                            syy += y * y;
                            sxy += x * y;
                            samples++;
                        }
                    }
                }
            }
            double correlation = (sxy - sx * sy / samples)
                / Math.sqrt((sxx - sx * sx / samples) * (syy - sy * sy / samples));
            assertTrue("Correlated honeycomb column: " + correlation, Math.abs(correlation) < .2);
        }
    }

    @Test
    public void mediumParticleEndpointsTouchThePlaneWhileHighTracksLocalRelief() {
        for (boolean energy : new boolean[] { false, true }) {
            double end = energy ? 1 : 0;
            assertEquals(.01, LinkNodeVisuals.particleDepth("medium", 0, 0, .125, .65, energy, end), 0);
            assertEquals(.135, LinkNodeVisuals.particleDepth("high", 0, 0, .125, .65, energy, end), 1e-12);
            assertEquals(.05375, LinkNodeVisuals.particleDepth("high", 0, 0, .125, -.65, energy, end), 1e-12);
        }
        assertTrue(
            LinkNodeVisuals.particleDepth("medium", .1, -.1, .125, .65, true, .1)
                > LinkNodeVisuals.particleDepth("medium", .1, -.1, .125, .65, true, .9));
        assertTrue(
            LinkNodeVisuals.particleDepth("medium", .1, -.1, .125, .65, false, .1)
                < LinkNodeVisuals.particleDepth("medium", .1, -.1, .125, .65, false, .9));
    }

    @Test
    public void particlesTravelHalfABlockFromTheirLocalSurfaceOnEveryFace() {
        for (String quality : new String[] { "medium", "high" }) {
            for (boolean energy : new boolean[] { false, true }) {
                double surface = LinkNodeVisuals.particleDepth(quality, .1, -.1, .125, .65, energy, energy ? 1 : 0);
                double far = LinkNodeVisuals.particleDepth(quality, .1, -.1, .125, .65, energy, energy ? 0 : 1);
                assertEquals(.5, far - surface, 1e-12);
                for (int side = 0; side < 6; side++) {
                    double[] nearPoint = LinkNodeVisuals.point(side, .1, -.1, surface);
                    double[] farPoint = LinkNodeVisuals.point(side, .1, -.1, far);
                    double[] normal = LinkNodeVisuals.normal(side);
                    for (int axis = 0; axis < 3; axis++) {
                        assertEquals(normal[axis] * .5, farPoint[axis] - nearPoint[axis], 1e-12);
                    }
                }
            }
        }
    }

    @Test
    public void allFaceGeometryStaysOutsideHostAndWithinAllowedThickness() {
        for (int side = 0; side < 6; side++) {
            double[] normal = LinkNodeVisuals.normal(side);
            for (double relief : new double[] { -1, -.65, 0, .65, 1 }) {
                for (LinkNodeVisuals.Cell cell : LinkNodeVisuals.CELLS) {
                    double depth = LinkNodeVisuals.surfaceDepth(cell, .125, relief);
                    assertTrue(depth >= 0 && depth <= .125);
                    double[] point = LinkNodeVisuals.point(side, cell.u, cell.v, depth);
                    double projected = 0;
                    for (int axis = 0; axis < 3; axis++) projected += (point[axis] - .5) * normal[axis];
                    assertEquals(.5 + depth, projected, 1e-12);
                }
            }
        }
    }

    @Test
    public void cylinderSamplesStayRandomAndVelocityHasNoLateralComponent() {
        Random random = new Random(901);
        double sumX = 0, sumY = 0;
        for (int i = 0; i < 1000; i++) {
            double[] xy = LinkNodeVisuals.sampleColumn(random);
            assertTrue(xy[0] * xy[0] + xy[1] * xy[1] <= .36 * .36);
            sumX += xy[0];
            sumY += xy[1];
        }
        assertTrue(Math.abs(sumX / 1000) < .02 && Math.abs(sumY / 1000) < .02);
        for (int side = 0; side < 6; side++) {
            for (boolean energy : new boolean[] { false, true }) {
                double[] v = LinkNodeVisuals.velocity(side, energy, .04);
                double[] n = LinkNodeVisuals.normal(side);
                for (int axis = 0; axis < 3; axis++) assertEquals(n[axis] * (energy ? -.04 : .04), v[axis], 0);
            }
        }
    }

    @Test
    public void texturesContainTransparentAndBrightPixelsAtBothResolutions() {
        for (int size : new int[] { 32, 64 }) {
            int[] base = LinkNodeVisuals.pixels(size, .45, false, false);
            int[] glow = LinkNodeVisuals.pixels(size, .45, false, true);
            int transparent = 0, lit = 0;
            for (int pixel : base) if ((pixel >>> 24) == 0) transparent++;
            for (int pixel : glow) if ((pixel >>> 24) > 150) lit++;
            assertTrue(transparent > 0 && lit > 0);
        }
    }
}
