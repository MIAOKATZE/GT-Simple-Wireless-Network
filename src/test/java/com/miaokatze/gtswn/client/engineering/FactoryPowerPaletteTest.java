package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Cell;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Power;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Review;

public class FactoryPowerPaletteTest {

    @Test
    public void draggingRebasesTheScaleOnTheCurrentRectangleOnly() {
        Cell low = cell(0, 0, 10), middle = cell(1, 0, 20), high = cell(2, 0, 30), outside = cell(1, 1, 10000);
        EngineeringSnapshot snapshot = snapshot("ok", null, low, middle, high, outside);
        FactoryPowerPalette full = palette(snapshot, new FactoryPowerSelection.Region(0, 0, 2, 0));
        FactoryPowerPalette drag = palette(snapshot, new FactoryPowerSelection.Region(1, 0, 2, 0));
        assertEquals(10, full.min, 0);
        assertEquals(30, full.max, 0);
        assertEquals(20, drag.min, 0);
        assertEquals(30, drag.max, 0);
        assertEquals(.5, full.position(20), 0);
        assertEquals(0, drag.position(20), 0);
        assertNotEquals(full.cellColor(middle), drag.cellColor(middle));
        assertFalse(drag.known(outside));
        assertFalse(drag.known(low));
        // A newly subscribed snapshot of the same selection uses exactly the preview's scale.
        FactoryPowerPalette subscribed = palette(
            snapshot("ok", null, middle, high),
            new FactoryPowerSelection.Region(1, 0, 2, 0));
        assertEquals(drag.cellColor(middle), subscribed.cellColor(middle));
        assertEquals(drag.cellColor(high), subscribed.cellColor(high));
    }

    @Test
    public void missingUnknownAndUnmonitoredCellsDoNotWidenTheRangeOrInventZero() {
        Cell known = cell(0, 0, 20);
        Cell unknown = new Cell(1, 0, 1, 1, new Power(100000, 0, 0, 0));
        Cell uncovered = new Cell(2, 0, 0, 0, Power.ZERO);
        Cell missing = new Cell(3, 0, 1, 0, null);
        FactoryPowerPalette scale = palette(
            snapshot("ok", null, known, unknown, uncovered, missing),
            new FactoryPowerSelection.Region(0, 0, 3, 0));
        assertEquals(20, scale.min, 0);
        assertEquals(20, scale.max, 0);
        for (Cell invalid : Arrays.asList(unknown, uncovered, missing)) {
            assertFalse(scale.known(invalid));
            assertEquals(FactoryPowerPalette.UNKNOWN, scale.cellColor(invalid));
        }
        assertFalse(
            palette(snapshot("ok", null, uncovered, missing), new FactoryPowerSelection.Region(0, 0, 3, 0)).hasValues);
    }

    @Test
    public void staleFailedAndIncompleteCoverageCannotSupplyBounds() {
        Cell cell = cell(0, 0, 50);
        FactoryPowerSelection.Region region = new FactoryPowerSelection.Region(0, 0, 0, 0);
        assertFalse(FactoryPowerPalette.forRegion(snapshot("ok", null, cell), false, region).hasValues);
        assertFalse(palette(snapshot("denied", null, cell), region).hasValues);
        assertFalse(palette(snapshot("ok", review(true, 9), cell), region).hasValues);
        assertFalse(palette(snapshot("ok", review(false, 8), cell), region).hasValues);
        assertTrue(palette(snapshot("ok", review(false, 9), cell), region).hasValues);
        assertFalse(FactoryPowerPalette.forRegion(null, true, region).hasValues);
        assertFalse(FactoryPowerPalette.forRegion(snapshot("ok", null, cell), true, null).hasValues);
    }

    @Test
    public void sameSignAndCrossZeroRangesAllUseTheFullContinuousPalette() {
        for (double[] values : new double[][] { { 10, 20, 30 }, { -30, -20, -10 }, { -20, 0, 20 } }) {
            Cell low = cell(0, 0, values[0]), middle = cell(1, 0, values[1]), high = cell(2, 0, values[2]);
            FactoryPowerPalette scale = palette(
                snapshot("ok", null, low, middle, high),
                new FactoryPowerSelection.Region(0, 0, 2, 0));
            assertEquals(0, scale.position(values[0]), 0);
            assertEquals(.5, scale.position(values[1]), 0);
            assertEquals(1, scale.position(values[2]), 0);
            assertNotEquals(scale.cellColor(low), scale.cellColor(middle));
            assertNotEquals(scale.cellColor(middle), scale.cellColor(high));
            assertNotEquals(scale.cellColor(low), scale.cellColor(high));
        }
        FactoryPowerPalette asymmetric = palette(
            snapshot("ok", null, cell(0, 0, -10), cell(1, 0, 30)),
            new FactoryPowerSelection.Region(0, 0, 1, 0));
        assertEquals(.25, asymmetric.position(0), 0);
        assertNotEquals(asymmetric.colorAt(.25), asymmetric.colorAt(.5));
    }

    @Test
    public void equalAndZeroSamplesStayKnownAndHaveAUniformLegend() {
        for (double value : new double[] { -42, 0, 42 }) {
            Cell first = cell(0, 0, value), second = cell(1, 0, value);
            FactoryPowerPalette scale = palette(
                snapshot("ok", null, first, second),
                new FactoryPowerSelection.Region(0, 0, 1, 0));
            assertTrue(scale.hasValues);
            assertTrue(scale.known(first));
            assertEquals(.5, scale.position(value), 0);
            assertEquals(scale.cellColor(first), scale.cellColor(second));
            assertEquals(scale.colorAt(0), scale.colorAt(1));
            assertNotEquals(FactoryPowerPalette.UNKNOWN, scale.cellColor(first));
        }
    }

    @Test
    public void finiteExtremesAndSubnormalRangesKeepOrderedFinitePositions() {
        for (double extent : new double[] { Double.MAX_VALUE, Double.MIN_VALUE }) {
            FactoryPowerPalette scale = palette(
                snapshot("ok", null, cell(0, 0, -extent), cell(1, 0, extent)),
                new FactoryPowerSelection.Region(0, 0, 1, 0));
            assertEquals(0, scale.position(-extent), 0);
            assertEquals(.5, scale.position(0), 0);
            assertEquals(1, scale.position(extent), 0);
            assertNotEquals(scale.colorAt(0), scale.colorAt(1));
        }
        Cell overflow = new Cell(0, 0, 1, 0, new Power(-Double.MAX_VALUE, Double.MAX_VALUE, 0, 0));
        assertEquals(Double.MAX_VALUE, FactoryPowerPalette.net(overflow), 0);
        Cell negativeOverflow = new Cell(1, 0, 1, 0, new Power(Double.MAX_VALUE, -Double.MAX_VALUE, 0, 0));
        assertEquals(-Double.MAX_VALUE, FactoryPowerPalette.net(negativeOverflow), 0);
        double min = Math.nextDown(Double.MAX_VALUE);
        FactoryPowerPalette close = palette(
            snapshot("ok", null, cell(0, 0, min), cell(1, 0, Double.MAX_VALUE)),
            new FactoryPowerSelection.Region(0, 0, 1, 0));
        assertEquals(0, close.position(min), 0);
        assertEquals(1, close.position(Double.MAX_VALUE), 0);
    }

    private static FactoryPowerPalette palette(EngineeringSnapshot snapshot, FactoryPowerSelection.Region region) {
        return FactoryPowerPalette.forRegion(snapshot, true, region);
    }

    private static Cell cell(int x, int z, double net) {
        return new Cell(x, z, 1, 0, new Power(Math.max(0, -net), Math.max(0, net), 0, 0));
    }

    private static Review review(boolean discovering, int loaded) {
        return new Review(0, 0, 0, 0, Power.ZERO, Power.ZERO, loaded, 9, loaded, 0, false, discovering);
    }

    private static EngineeringSnapshot snapshot(String status, Review review, Cell... cells) {
        return new EngineeringSnapshot(
            1,
            2,
            100,
            4,
            status,
            Collections.emptyList(),
            Arrays.asList(cells),
            Power.ZERO,
            Power.ZERO,
            0,
            0,
            0,
            false,
            false,
            false,
            null,
            false,
            true,
            review);
    }
}
