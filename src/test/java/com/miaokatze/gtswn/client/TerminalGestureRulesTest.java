package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalGestureRulesTest {

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
