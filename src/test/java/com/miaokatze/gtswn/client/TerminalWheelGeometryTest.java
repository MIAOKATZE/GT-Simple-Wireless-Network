package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalWheelGeometryTest {

    @Test
    public void allEightIconCentersHitTheirClockwiseSector() {
        for (int index = 0; index < 8; index++) {
            double angle = TerminalWheelGeometry.angle(index);
            assertEquals(index, TerminalWheelGeometry.sector(Math.cos(angle) * 99, Math.sin(angle) * 99, 49, 136));
        }
    }

    @Test
    public void centerAndOutsideNeverActivateAnyTerminal() {
        assertEquals(-1, TerminalWheelGeometry.sector(0, 0, 49, 136));
        assertEquals(-1, TerminalWheelGeometry.sector(0, -48, 49, 136));
        assertEquals(-1, TerminalWheelGeometry.sector(0, -137, 49, 136));
    }

    @Test
    public void visibleGapsAreNotClickableAndAdjacentWedgesStayDistinct() {
        for (int index = 0; index < 8; index++) {
            double border = TerminalWheelGeometry.angle(index) + TerminalWheelGeometry.STEP / 2;
            assertEquals(-1, at(border));
            assertEquals(index, at(border - TerminalWheelGeometry.GAP * 2));
            assertEquals((index + 1) % 8, at(border + TerminalWheelGeometry.GAP * 2));
        }
    }

    @Test
    public void keyboardCyclesThroughEveryPresetAndWraps() {
        int index = 0;
        for (int expected = 1; expected <= 8; expected++) {
            index = TerminalWheelGeometry.next(index);
            assertEquals(expected % 8, index);
        }
    }

    @Test
    public void allThirtyTwoButtonCentersHaveExactlyOneHit() {
        for (int index = 0; index < 8; index++) for (int zone = 0; zone < 4; zone++) {
            double angle = TerminalWheelGeometry.centerAngle(index, zone);
            double radius = TerminalWheelGeometry.centerRadius(zone);
            double x = Math.cos(angle) * radius, y = Math.sin(angle) * radius;
            int hits = 0;
            for (int candidate = 0; candidate < 8; candidate++) for (int region = 0; region < 4; region++)
                if (TerminalWheelGeometry.contains(candidate, region, x, y)) {
                    assertEquals(index, candidate);
                    assertEquals(zone, region);
                    hits++;
                }
            assertEquals(1, hits);
        }
    }

    @Test
    public void auxiliaryBandsAreEightPercentWiderWithinOriginalOutline() {
        double thickness = 32 * 1.08 * 136 / 331;
        assertEquals(thickness, TerminalWheelGeometry.outer(3) - TerminalWheelGeometry.inner(3), 0.000001);
        assertEquals(thickness, TerminalWheelGeometry.outer(1) - TerminalWheelGeometry.inner(1), 0.000001);
        assertEquals(136, TerminalWheelGeometry.outer(1), 0);
    }

    @Test
    public void radialGapsAndOuterButtonDividerNeverActivate() {
        for (int index = 0; index < 8; index++) {
            double angle = TerminalWheelGeometry.angle(index);
            double[] radii = { 0, TerminalWheelGeometry.inner(3) - 0.1,
                (TerminalWheelGeometry.outer(3) + TerminalWheelGeometry.inner(0)) / 2,
                (TerminalWheelGeometry.outer(0) + TerminalWheelGeometry.inner(1)) / 2, 136.1 };
            for (double radius : radii) for (int zone = 0; zone < 4; zone++) assertFalse(
                TerminalWheelGeometry.contains(index, zone, Math.cos(angle) * radius, Math.sin(angle) * radius));
            double radius = TerminalWheelGeometry.centerRadius(1);
            assertFalse(TerminalWheelGeometry.contains(index, 1, Math.cos(angle) * radius, Math.sin(angle) * radius));
            assertFalse(TerminalWheelGeometry.contains(index, 2, Math.cos(angle) * radius, Math.sin(angle) * radius));
            assertTrue(TerminalWheelGeometry.contains(index, 0, Math.cos(angle) * 94, Math.sin(angle) * 94));
        }
    }

    private static int at(double angle) {
        return TerminalWheelGeometry.sector(Math.cos(angle) * 100, Math.sin(angle) * 100, 49, 136);
    }
}
