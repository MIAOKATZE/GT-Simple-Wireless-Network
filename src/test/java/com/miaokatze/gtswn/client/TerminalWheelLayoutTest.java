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
}
