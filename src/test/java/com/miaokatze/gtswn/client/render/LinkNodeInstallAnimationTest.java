package com.miaokatze.gtswn.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinkNodeInstallAnimationTest {

    @Test
    public void revealMovesFromCentreToOuterRingAndCompletesBeforeParticles() {
        assertEquals(0, LinkNodeInstallAnimation.alpha(0, 0), 0);
        assertTrue(LinkNodeInstallAnimation.alpha(0, 12) > 0);
        assertEquals(0, LinkNodeInstallAnimation.alpha(2, 12), 0);
        assertEquals(1, LinkNodeInstallAnimation.alpha(0, 30), 0);
        assertEquals(0, LinkNodeInstallAnimation.alpha(3, 30), 0);
        for (int ring = 0; ring < 4; ring++) {
            assertEquals(1, LinkNodeInstallAnimation.alpha(ring, 48), 0);
        }
    }

    @Test
    public void packetBeforeCoverWaitsAndWrongTypeCannotStartClock() {
        LinkNodeInstallAnimation state = new LinkNodeInstallAnimation();
        Object cover = new Object();
        assertTrue(state.accept("face", 1, 20, true));
        state.resolve("face", cover, false, 22);
        assertEquals(0, state.elapsed("face", cover, 30, .5F), 0);
        state.resolve("face", cover, true, 35);
        assertEquals(12.5, state.elapsed("face", cover, 47, .5F), 0);
        state.prune(83);
        assertEquals(48, state.elapsed("face", cover, 83, 0), 0);
    }

    @Test
    public void packetAfterSyncStartsImmediatelyAndReplacementKeepsElapsed() {
        LinkNodeInstallAnimation state = new LinkNodeInstallAnimation();
        Object old = new Object(), replacement = new Object();
        state.accept("face", 7, 10, true);
        state.resolve("face", old, true, 10);
        state.resolve("face", replacement, true, 13);
        assertEquals(5, state.elapsed("face", replacement, 15, 0), 0);
        assertEquals(0, state.elapsed("face", old, 15, 0), 0);
        assertFalse(state.accept("face", 7, 16, true));
        assertFalse(state.accept("face", 6, 16, true));
        assertEquals(6, state.elapsed("face", replacement, 16, 0), 0);
        state.accept("face", 8, 17, true);
        state.resolve("face", replacement, true, 17);
        assertEquals(0, state.elapsed("face", replacement, 17, 0), 0);
    }

    @Test
    public void pendingTimesOutAndWorldResetDoesNotInferNewAnimations() {
        LinkNodeInstallAnimation state = new LinkNodeInstallAnimation();
        state.accept("face", 1, 10, true);
        state.prune(110);
        assertFalse(state.contains("face"));
        assertFalse(state.accept("face", 1, 111, true));
        state.clear();
        assertEquals(48, state.elapsed("face", new Object(), 200, 0), 0);
        for (int index = 0; index <= LinkNodeInstallAnimation.LIMIT; index++) {
            state.accept("face" + index, index + 1, 200, true);
        }
        assertFalse(state.contains("face0"));
        assertTrue(state.contains("face256"));
    }
}
