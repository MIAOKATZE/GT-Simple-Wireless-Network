package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;

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

    private static int at(double angle) {
        return TerminalWheelGeometry.sector(Math.cos(angle) * 100, Math.sin(angle) * 100, 49, 136);
    }
}
