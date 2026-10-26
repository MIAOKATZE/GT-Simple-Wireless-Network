package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import org.junit.Test;

public class DisplayContinuityTest {

    @Test
    public void briefCloseAndReopenPreserveValuesButNeverReceiptAuthority() {
        DisplayContinuity<long[]> cache = new DisplayContinuity<>();
        Object scope = new Object();
        long[] receivedPoints = { 100, 120 };
        cache.open(scope);
        cache.receive(scope, receivedPoints, 1000);
        assertTrue(cache.live(scope, 1100, 3500, false));
        cache.close();
        assertSame(receivedPoints, cache.display(scope));
        assertFalse(cache.live(scope, 1100, 3500, false));
        cache.open(scope);
        assertSame(receivedPoints, cache.display(scope));
        assertEquals(1000, cache.receivedAt());
        assertEquals("stale", cache.status(scope, 1100, 3500, false));
        cache.receive(scope, new long[] { 100, 120, 140 }, 1200);
        assertTrue(cache.live(scope, 1200, 3500, false));
    }

    @Test
    public void pausedTimeNeverCreatesPointsReceiptTimesOrLiveActions() {
        DisplayContinuity<long[]> cache = new DisplayContinuity<>();
        Object scope = new Object();
        long[] original = { 40, 60 };
        cache.receive(scope, original, 1000);
        assertEquals("paused", cache.status(scope, 100000, 3500, true));
        assertSame(original, cache.display(scope));
        assertArrayEquals(new long[] { 40, 60 }, original);
        assertEquals(1000, cache.receivedAt());
        assertFalse(cache.live(scope, 1001, 3500, true));
        assertEquals("stale", cache.status(scope, 100000, 3500, false));
    }

    @Test
    public void sessionSourceOrBindingReplacementCannotBorrowPreviousDisplay() {
        DisplayContinuity<String> cache = new DisplayContinuity<>();
        Object previousCredentials = new Object(), replacementCredentials = new Object();
        cache.receive(previousCredentials, "authorized old value", 1);
        cache.close();
        cache.open(replacementCredentials);
        assertNull(cache.display(replacementCredentials));
        assertEquals("waiting", cache.status(replacementCredentials, 2, 3500, false));
        // A single-slot display cannot resurrect a second account/source by switching back.
        assertNull(cache.display(previousCredentials));
    }

    @Test
    public void explicitDenialPurgesDisplayAndCannotBeUndoneByReopenOrPause() {
        DisplayContinuity<String> cache = new DisplayContinuity<>();
        Object scope = new Object();
        cache.receive(scope, "secret", 123);
        cache.clear();
        cache.open(scope);
        assertNull(cache.display(scope));
        assertEquals(0, cache.receivedAt());
        assertEquals("waiting", cache.status(scope, 124, 3500, true));
        assertFalse(cache.live(scope, 124, 3500, false));
    }

    @Test
    public void missingScopeAfterRevocationCannotSeedAnotherSourcesDisplay() {
        DisplayContinuity<String> cache = new DisplayContinuity<>();
        Object original = new Object(), replacement = new Object();
        cache.receive(original, "old authorized value", 100);
        cache.clear();
        // A resumed producer has lost its captured identity: even a newly authorized value needs a scope.
        cache.receive(null, "reauthorized EU value", 200);
        cache.close();
        cache.open(replacement);
        assertNull(cache.display(replacement));
        assertEquals(0, cache.receivedAt());
        assertEquals("waiting", cache.status(replacement, 201, 3500, false));
    }

    @Test
    public void clockRegressionNeverTurnsHistoryIntoFreshAuthority() {
        DisplayContinuity<String> cache = new DisplayContinuity<>();
        Object scope = new Object();
        cache.receive(scope, "value", 1000);
        assertFalse(cache.live(scope, 999, 3500, false));
        assertEquals("stale", cache.status(scope, 999, 3500, false));
    }

    @Test
    public void lifecycleChangesPurgeEvenWhileClosedWithoutAnotherDisplayRead() {
        Object world = new Object(), player = new Object(), connection = new Object();
        for (int boundary = 0; boundary < 3; boundary++) {
            DisplayContinuity.Session session = new DisplayContinuity.Session();
            DisplayContinuity<String> cache = new DisplayContinuity<>();
            assertTrue(session.update(world, player, connection));
            cache.receive(world, "old", 100);
            cache.close();
            assertFalse(session.update(world, player, connection));
            boolean changed = session.update(
                boundary == 0 ? new Object() : world,
                boundary == 1 ? new Object() : player,
                boundary == 2 ? new Object() : connection);
            if (changed) cache.clear(); // Same always-on invalidation used by HudSessionLifecycle.
            assertEquals(0, cache.receivedAt());
            assertNull(cache.display(world));
        }
    }

    @Test
    public void lateUnloadOrDisconnectCannotClearAnotherSessionsDisplay() {
        DisplayContinuity.Session session = new DisplayContinuity.Session();
        Object world = new Object(), connection = new Object();
        session.update(world, new Object(), connection);
        assertTrue(session.ownsWorld(world));
        assertTrue(session.ownsConnection(connection));
        assertFalse(session.ownsWorld(new Object()));
        assertFalse(session.ownsConnection(new Object()));
        session.clear();
        assertFalse(session.ownsWorld(world));
        assertFalse(session.ownsConnection(connection));
    }

}
