package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinkNodeRevealSessionTest {

    @Test
    public void repeatedScanRequiresSameWorldAndUnexpiredWindow() {
        Object world = new Object();
        LinkNodeRevealSession session = new LinkNodeRevealSession(world, 15000L);
        assertTrue(session.isActive(world, 14999L));
        assertFalse(session.isActive(world, 15000L));
        assertFalse(session.isActive(world, 15001L));
        assertFalse(session.isActive(new Object(), 1000L));
    }

    @Test
    public void firstScanFiltersModeAndRepeatPreservesBothCoversOnSameMachine() {
        assertEquals(1, LinkNodeRevealSession.visibleTypes((byte) 3, (byte) 0, false));
        assertEquals(2, LinkNodeRevealSession.visibleTypes((byte) 3, (byte) 1, false));
        assertEquals(3, LinkNodeRevealSession.visibleTypes((byte) 3, (byte) 0, true));
        assertEquals(3, LinkNodeRevealSession.visibleTypes((byte) 3, (byte) 1, true));
        assertEquals(0, LinkNodeRevealSession.visibleTypes((byte) 2, (byte) 0, false));
        assertEquals(2, LinkNodeRevealSession.visibleTypes((byte) 2, (byte) 0, true));
    }
}
