package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FactoryPowerSelectionTest {

    @Test
    public void sameGridClearsCustomBeforeHidingGrid() {
        FactoryPowerSelection selection = new FactoryPowerSelection();
        selection.grid(FactoryPowerSelection.Grid.MC);
        selection.custom(true);
        selection.beginDrag();
        assertTrue(selection.finish(new FactoryPowerSelection.Region(-1, -1, 0, 0)));
        assertEquals(FactoryPowerSelection.Change.DEFAULT_REGION, selection.grid(FactoryPowerSelection.Grid.MC));
        assertTrue(selection.visible());
        assertFalse(selection.custom());
        assertEquals(FactoryPowerSelection.Change.HIDDEN, selection.grid(FactoryPowerSelection.Grid.MC));
        assertFalse(selection.visible());
    }

    @Test
    public void customRequiresGridAndKnownGtPattern() {
        FactoryPowerSelection selection = new FactoryPowerSelection();
        assertEquals(FactoryPowerSelection.Change.NEED_GRID, selection.custom(true));
        selection.grid(FactoryPowerSelection.Grid.GT);
        assertEquals(FactoryPowerSelection.Change.NEED_PATTERN, selection.custom(false));
        assertFalse(selection.selecting());
        assertEquals(FactoryPowerSelection.Change.SELECTING, selection.custom(true));
    }

    @Test
    public void customButtonCancelsSelectionAndRetainsGrid() {
        FactoryPowerSelection selection = new FactoryPowerSelection();
        selection.grid(FactoryPowerSelection.Grid.MC);
        selection.custom(true);
        selection.beginDrag();
        assertEquals(FactoryPowerSelection.Change.DEFAULT_REGION, selection.custom(true));
        assertTrue(selection.visible());
        assertFalse(selection.selecting());
        assertFalse(selection.dragging());
    }

    @Test
    public void switchingGridClearsSelectionAndAuditOffKeepsGrid() {
        FactoryPowerSelection selection = new FactoryPowerSelection();
        selection.grid(FactoryPowerSelection.Grid.MC);
        selection.custom(true);
        selection.grid(FactoryPowerSelection.Grid.GT);
        assertTrue(selection.gt());
        assertFalse(selection.selecting());
        assertFalse(selection.toggleAudit());
        assertTrue(selection.audit());
        selection.custom(true);
        selection.beginDrag();
        assertTrue(selection.finish(new FactoryPowerSelection.Region(0, 0, 0, 0)));
        assertTrue(selection.toggleAudit());
        assertFalse(selection.audit());
        assertFalse(selection.custom());
        assertTrue(selection.gt());
        assertTrue(selection.visible());
    }

    @Test
    public void previewsOnlyCalculateWorldAlignedRegionUntilRelease() {
        FactoryPowerSelection selection = new FactoryPowerSelection();
        selection.grid(FactoryPowerSelection.Grid.MC);
        selection.custom(true);
        selection.beginDrag();
        FactoryPowerSelection.Region first = FactoryPowerSelection.region(-.1, 0, 16, 31.99, false, false);
        FactoryPowerSelection.Region second = FactoryPowerSelection.region(-.1, 0, 32, 31.99, false, false);
        assertEquals(-1, first.minX);
        assertEquals(1, first.maxX);
        assertEquals(6, first.count());
        assertEquals(8, second.count());
        assertFalse(selection.custom());
        assertTrue(selection.dragging());
        assertTrue(selection.finish(second));
        assertTrue(selection.custom());
        assertFalse(selection.dragging());
    }

    @Test
    public void auditCanRunWithoutGridAndClosingItCancelsPendingSelection() {
        FactoryPowerSelection selection = new FactoryPowerSelection();
        assertFalse(selection.toggleAudit());
        assertTrue(selection.audit());
        assertFalse(selection.visible());
        selection.grid(FactoryPowerSelection.Grid.MC);
        selection.custom(true);
        selection.beginDrag();
        assertTrue(selection.toggleAudit());
        assertFalse(selection.audit());
        assertFalse(selection.selecting());
        assertFalse(selection.dragging());
        assertTrue(selection.visible());
    }

    @Test
    public void oversizedReleaseStaysInSelectionAndGtAxisUsesItsOwnMapping() {
        FactoryPowerSelection selection = new FactoryPowerSelection();
        selection.grid(FactoryPowerSelection.Grid.GT);
        selection.custom(true);
        selection.beginDrag();
        assertFalse(selection.finish(new FactoryPowerSelection.Region(0, 0, 8, 8)));
        assertTrue(selection.selecting());
        assertFalse(selection.custom());
        FactoryPowerSelection.Region region = FactoryPowerSelection.region(-48, -48, -16, -16, true, true);
        assertEquals(-2, region.minX);
        assertEquals(-1, region.maxX);
        assertEquals(4, region.count());
    }
}
