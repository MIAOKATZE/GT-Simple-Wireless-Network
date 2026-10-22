package com.miaokatze.gtswn.client.engineering.board;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class PortableBoardLifecycleTest {

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
