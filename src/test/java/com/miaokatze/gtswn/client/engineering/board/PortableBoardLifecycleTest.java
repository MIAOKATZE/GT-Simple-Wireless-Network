package com.miaokatze.gtswn.client.engineering.board;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class PortableBoardLifecycleTest {

    @Test
    public void userCloseNotifiesHostOnceWhileLifecycleCloseDoesNotReenterIt() throws Exception {
        java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
        PortableSuperDashboard.onUserClose(closed::incrementAndGet);
        java.lang.reflect.Method request = PortableSuperDashboard.class.getDeclaredMethod("requestClose");
        request.setAccessible(true);
        request.invoke(null);
        assertEquals(1, closed.get());
        assertFalse(PortableSuperDashboard.isOpen());
        PortableSuperDashboard.close();
        request.invoke(null);
        assertEquals(1, closed.get());
    }

    @Test
    public void closingBoardWithReadOnlySnapshotTextIsSafeAndRepeatable() throws Exception {
        Field basic = PortableSuperDashboard.class.getDeclaredField("basic");
        basic.setAccessible(true);
        basic.set(null, Collections.singletonList("read-only terminal snapshot"));
        PortableSuperDashboard.close();
        assertTrue(((List<?>) basic.get(null)).isEmpty());
        assertFalse(PortableSuperDashboard.isOpen());
        PortableSuperDashboard.close();
        assertTrue(((List<?>) basic.get(null)).isEmpty());
    }
}
