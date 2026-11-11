// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalWheelLayoutTest {

    private static final int[][] VIEWPORTS = { { 507, 442 }, { 640, 450 }, { 640, 360 }, { 320, 240 } };

    @Test
    public void wheelStaysBetweenTopControlsAndHotbarAtEveryViewport() {
        for (int[] viewport : VIEWPORTS) for (boolean engineering : new boolean[] { false, true }) {
            TerminalWheelLayout layout = new TerminalWheelLayout(viewport[0], viewport[1], engineering);
            assertEquals(4, layout.helpTop);
            assertEquals(Math.min(372, viewport[0] - 12), layout.helpWidth);
            assertEquals((viewport[0] - layout.helpWidth) / 2, layout.helpLeft);
            assertTrue(layout.centerX - layout.radius >= 6);
            assertTrue(layout.centerX + layout.radius <= viewport[0] - 6);
            assertTrue(layout.centerY - layout.radius >= (engineering ? 64 : 42));
            assertTrue(layout.centerY + layout.radius <= viewport[1] - 34);
        }
        TerminalWheelLayout regular = new TerminalWheelLayout(507, 442, false);
        assertEquals(176, regular.radius);
        assertEquals(218, regular.centerY);
        assertEquals(28, regular.mainIconSize);
    }

    @Test
    public void allThirtyTwoDrawingCentersMatchTheirOnlyInputRegionAfterResize() {
        for (int[] viewport : VIEWPORTS) {
            TerminalWheelLayout layout = new TerminalWheelLayout(viewport[0], viewport[1], false);
            for (int index = 0; index < 8; index++) for (int zone = 0; zone < 4; zone++) {
                double angle = TerminalWheelGeometry.centerAngle(index, zone);
                double radius = layout.centerRadius(zone);
                double x = layout.centerX + Math.cos(angle) * radius;
                double y = layout.centerY + Math.sin(angle) * radius;
                int hits = 0;
                for (int other = 0; other < 8; other++)
                    for (int region = 0; region < 4; region++) if (layout.contains(other, region, x, y)) {
                        assertEquals(index, other);
                        assertEquals(zone, region);
                        hits++;
                    }
                assertEquals(1, hits);
            }
        }
    }

    @Test
    public void resizedWheelUsesFourUnitDividersAcrossAllBandsAndMatchingHitEdges() {
        double epsilon = 1e-6;
        for (int[] viewport : VIEWPORTS) {
            TerminalWheelLayout layout = new TerminalWheelLayout(viewport[0], viewport[1], false);
            for (int index = 0; index < 8; index++) for (int zone = 0; zone < 4; zone++) {
                for (double fraction : new double[] { 0.01, 0.5, 0.99 }) {
                    double radius = layout.inner(zone) + fraction * (layout.outer(zone) - layout.inner(zone));
                    double left = TerminalWheelGeometry.end(index, TerminalWheelGeometry.MAIN, radius);
                    double right = TerminalWheelGeometry.start((index + 1) % 8, TerminalWheelGeometry.MAIN, radius);
                    assertEquals(
                        4,
                        Math.hypot(
                            radius * (Math.cos(left) - Math.cos(right)),
                            radius * (Math.sin(left) - Math.sin(right))),
                        1e-9);
                    double start = TerminalWheelGeometry.start(index, zone, radius);
                    double end = TerminalWheelGeometry.end(index, zone, radius);
                    assertHit(layout, index, zone, radius, start - epsilon, false);
                    assertHit(layout, index, zone, radius, start + epsilon, true);
                    assertHit(layout, index, zone, radius, end - epsilon, true);
                    assertHit(layout, index, zone, radius, end + epsilon, false);
                }
                assertEquals(
                    TerminalWheelGeometry.start(index, TerminalWheelGeometry.MAIN, layout.inner(0)),
                    TerminalWheelGeometry.start(index, TerminalWheelGeometry.INNER, layout.outer(3)),
                    0);
                assertEquals(
                    TerminalWheelGeometry.end(index, TerminalWheelGeometry.MAIN, layout.outer(0)),
                    TerminalWheelGeometry.end(index, TerminalWheelGeometry.OUTER_SECOND, layout.inner(2)),
                    0);
                assertEquals(
                    TerminalWheelGeometry.end(index, TerminalWheelGeometry.OUTER_FIRST, radiusForOuter(layout)),
                    TerminalWheelGeometry.start(index, TerminalWheelGeometry.OUTER_SECOND, radiusForOuter(layout)),
                    0);
            }
        }
    }

    @Test
    public void nativeSizeIconAndWrappedDefaultNamesFitEntirelyInsideMainSector() {
        for (int[] viewport : new int[][] { { 507, 442 }, { 640, 450 }, { 640, 360 } }) {
            TerminalWheelLayout layout = new TerminalWheelLayout(viewport[0], viewport[1], false);
            for (int index = 0; index < 8; index++) {
                TerminalWheelLayout.Bounds bounds = null;
                // Find the complete 13-character default name at native font size, as the screen does.
                for (int width = 112; width >= 18 && bounds == null; width -= 2) {
                    int charactersPerLine = Math.max(1, width / 9);
                    int lineCount = (13 + charactersPerLine - 1) / charactersPerLine;
                    bounds = layout.terminalContent(index, width, lineCount * 9);
                }
                assertNotNull(bounds);
                assertTrue(layout.terminalFits(index, bounds));
            }
        }
    }

    @Test
    public void minimumViewportStillHasSafeRealTerminalIcons() {
        TerminalWheelLayout layout = new TerminalWheelLayout(320, 240, false);
        for (int index = 0; index < 8; index++)
            assertNotNull(layout.content(index, layout.mainIconSize, layout.mainIconSize));
    }

    private static double radiusForOuter(TerminalWheelLayout layout) {
        return layout.centerRadius(TerminalWheelGeometry.OUTER_FIRST);
    }

    private static void assertHit(TerminalWheelLayout layout, int index, int zone, double radius, double angle,
        boolean expected) {
        assertEquals(
            expected,
            layout.contains(
                index,
                zone,
                layout.centerX + Math.cos(angle) * radius,
                layout.centerY + Math.sin(angle) * radius));
    }
}
