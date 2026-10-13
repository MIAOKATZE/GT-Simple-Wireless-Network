package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceTerminalAnchorMathTest {

    @Test
    public void markerAndServerShareFourDegreeConeAndRange() {
        assertEquals(25D, DeviceTerminalAnchorMath.distanceSquared(13.5D, 65.6D, -6.5D, 10.5D, 61.6D, -6.5D), 1E-10D);
        double inside = DeviceTerminalAnchorMath.alignment(0.6D, 0D, 10D, 0D, 0D, 0D, 0D, 0D, 1D);
        double outside = DeviceTerminalAnchorMath.alignment(0.8D, 0D, 10D, 0D, 0D, 0D, 0D, 0D, 1D);
        assertTrue(DeviceTerminalAnchorMath.targetable(100.36D, inside, 128));
        assertFalse(DeviceTerminalAnchorMath.targetable(100.64D, outside, 128));
        assertTrue(DeviceTerminalAnchorMath.targetable(128D * 128D, 1D, 128));
        assertFalse(DeviceTerminalAnchorMath.targetable(128D * 128D + 1D, 1D, 128));
    }

    @Test
    public void invalidTargetsAndBehindPlayerCannotStealClick() {
        assertFalse(DeviceTerminalAnchorMath.targetable(100D, -1D, 128));
        assertFalse(DeviceTerminalAnchorMath.targetable(Double.NaN, 1D, 128));
        assertFalse(DeviceTerminalAnchorMath.targetable(0D, 1D, 128));
        assertFalse(DeviceTerminalAnchorMath.betterTarget(Double.NaN, 4D, "bad", 1D, 9D, "ok"));
    }

    @Test
    public void centeringWinsThenDistanceThenStableKeyBreaksTies() {
        assertTrue(DeviceTerminalAnchorMath.betterTarget(1D, 1000D, "far", 0.999D, 1D, "near"));
        assertTrue(DeviceTerminalAnchorMath.betterTarget(1D, 4D, "z", 1D, 9D, "a"));
        assertTrue(DeviceTerminalAnchorMath.betterTarget(1D, 4D, "a", 1D, 4D, "b"));
        assertFalse(DeviceTerminalAnchorMath.betterTarget(1D, 4D, "b", 1D, 4D, "a"));
    }
}
