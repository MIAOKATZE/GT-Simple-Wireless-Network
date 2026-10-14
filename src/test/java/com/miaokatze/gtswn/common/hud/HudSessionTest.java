package com.miaokatze.gtswn.common.hud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HudSessionTest {

    @Test
    public void queuedReplyCannotCrossWorldPlayerOrConnectionIdentityEvenWithIdenticalTickCounts() {
        for (int changed = 0; changed < 3; changed++) {
            HudSession session = new HudSession();
            Object world = new Object(), player = new Object(), connection = new Object();
            session.change(world, player, connection);
            HudSession.Snapshot queued = session.capture(world, player, connection);
            long token = session.request();
            Object nextWorld = changed == 0 ? new Object() : world;
            Object nextPlayer = changed == 1 ? new Object() : player;
            Object nextConnection = changed == 2 ? new Object() : connection;
            assertTrue(session.change(nextWorld, nextPlayer, nextConnection));
            assertFalse(session.consume(queued, nextWorld, nextPlayer, nextConnection, token));
            assertNull(session.capture(world, player, connection));
        }
    }

    @Test
    public void replyArrivingAfterWorldSwitchStillNeedsTheNewRequestToken() {
        HudSession session = new HudSession();
        Object world = new Object(), player = new Object(), connection = new Object();
        session.change(world, player, connection);
        long oldToken = session.request();
        Object nextWorld = new Object();
        session.change(nextWorld, player, connection);
        HudSession.Snapshot newSnapshot = session.capture(nextWorld, player, connection);
        long newToken = session.request();
        assertFalse(session.consume(newSnapshot, nextWorld, player, connection, oldToken));
        assertTrue(session.consume(newSnapshot, nextWorld, player, connection, newToken));
        assertFalse(session.consume(newSnapshot, nextWorld, player, connection, newToken));
    }

    @Test
    public void wrongConnectionAndOwnerInvalidationCannotConsumeCurrentRequest() {
        HudSession session = new HudSession();
        Object world = new Object(), player = new Object(), connection = new Object();
        session.change(world, player, connection);
        HudSession.Snapshot snapshot = session.capture(world, player, connection);
        assertNotNull(snapshot);
        long firstOwnerToken = session.request();
        assertFalse(session.consume(snapshot, world, player, new Object(), firstOwnerToken));
        session.invalidateRequest();
        assertFalse(session.consume(snapshot, world, player, connection, firstOwnerToken));
        long nextOwnerToken = session.request();
        assertFalse(session.consume(snapshot, world, player, connection, firstOwnerToken));
        assertTrue(session.consume(snapshot, world, player, connection, nextOwnerToken));
    }

    @Test
    public void disconnectedOrAbsentWorldNeverIssuesAUsableResponseSnapshot() {
        HudSession session = new HudSession();
        Object world = new Object(), player = new Object(), connection = new Object();
        session.change(world, player, connection);
        HudSession.Snapshot old = session.capture(world, player, connection);
        long token = session.request();
        session.change(null, null, null);
        assertNull(session.capture(null, null, null));
        assertFalse(session.consume(old, world, player, connection, token));
        session.change(world, player, connection);
        assertFalse(session.consume(old, world, player, connection, token));
    }

    @Test
    public void fastPollingAllowsEarlierInFlightRepliesButPreventsOutOfOrderBalanceRollback() {
        HudSession session = new HudSession();
        Object world = new Object(), player = new Object(), connection = new Object();
        session.change(world, player, connection);
        HudSession.Snapshot snapshot = session.capture(world, player, connection);
        long first = session.request();
        long second = session.request();
        long third = session.request();
        assertTrue(session.consume(snapshot, world, player, connection, first));
        assertTrue(session.consume(snapshot, world, player, connection, third));
        assertFalse(session.consume(snapshot, world, player, connection, second));
        assertFalse(session.consume(snapshot, world, player, connection, third));
        long fourth = session.request();
        session.invalidateRequest();
        assertFalse(session.consume(snapshot, world, player, connection, fourth));
        assertTrue(session.consume(snapshot, world, player, connection, session.request()));
    }
}
