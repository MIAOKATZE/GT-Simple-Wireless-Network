package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalGestureRulesTest {

    @Test
    public void deviceScanUsesAltWithoutShiftAndAcceptsBlockOrEntityAim() {
        assertTrue(TerminalGestureRules.canCharge(true, true, false, true));
        assertFalse(TerminalGestureRules.canCharge(true, true, true, true));
        assertFalse(TerminalGestureRules.canCharge(true, false, true, true));
        assertTrue(TerminalGestureRules.canCharge(true, true, false, false));
        assertFalse(TerminalGestureRules.canCharge(true, true, true, false));
    }

    @Test
    public void otherTerminalsKeepAltShiftRevealGesture() {
        assertTrue(TerminalGestureRules.canCharge(false, true, true, true));
        assertFalse(TerminalGestureRules.canCharge(false, true, false, true));
        assertFalse(TerminalGestureRules.canCharge(false, true, true, false));
    }

    @Test
    public void visualizationToggleCannotStartScanning() {
        assertTrue(TerminalGestureRules.canToggleVisualization(true, true, true));
        assertFalse(TerminalGestureRules.canCharge(true, true, true, true));
        assertFalse(TerminalGestureRules.canToggleVisualization(true, true, false));
        assertFalse(TerminalGestureRules.canToggleVisualization(false, true, true));
    }

    @Test
    public void markerTravelRequiresActiveModeAndPlainClick() {
        assertTrue(TerminalGestureRules.canTravelToMarker(true, true, false, false, true));
        assertFalse(TerminalGestureRules.canTravelToMarker(true, false, false, false, true));
        assertFalse(TerminalGestureRules.canTravelToMarker(true, true, true, false, true));
        assertFalse(TerminalGestureRules.canTravelToMarker(true, true, false, true, true));
        assertFalse(TerminalGestureRules.canTravelToMarker(true, true, false, false, false));
    }

    @Test
    public void revealCombinationCannotIncorporateEvenWhenPointingAtBlock() {
        assertFalse(TerminalGestureRules.canIncorporate(true, true, true));
        assertFalse(TerminalGestureRules.canIncorporate(true, true, false));
    }

    @Test
    public void ordinaryAltBlockKeepsIncorporationButAirAndUnmodifiedClickDoNot() {
        assertTrue(TerminalGestureRules.canIncorporate(true, false, true));
        assertFalse(TerminalGestureRules.canIncorporate(true, false, false));
        assertFalse(TerminalGestureRules.canIncorporate(false, false, true));
        assertFalse(TerminalGestureRules.canIncorporate(false, true, true));
    }
}
