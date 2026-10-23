package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.UUID;

import org.junit.Test;

public class DeviceTerminalVisualizationStateTest {

    @Test
    public void onlyMatchingServerAcknowledgementEnablesOrDisablesTerminal() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        DeviceTerminalVisualizationState.clear();
        assertFalse(DeviceTerminalVisualizationState.active(first));
        DeviceTerminalVisualizationState.apply(first, true);
        assertTrue(DeviceTerminalVisualizationState.active(first));
        assertFalse(DeviceTerminalVisualizationState.active(second));
        DeviceTerminalVisualizationState.apply(second, false);
        assertTrue(DeviceTerminalVisualizationState.active(first));
        DeviceTerminalVisualizationState.apply(first, false);
        assertFalse(DeviceTerminalVisualizationState.active(first));
        DeviceTerminalVisualizationState.apply(second, true);
        DeviceTerminalVisualizationState.clear();
        assertFalse(DeviceTerminalVisualizationState.active(second));
    }

    @Test
    public void factoryDisplayOffMasksOrdinaryPresentationUntilFactoryExit() {
        UUID ordinary = UUID.randomUUID(), factory = UUID.randomUUID();
        org.junit.Assert.assertNull(DeviceTerminalVisualizationState.presentationOwner(true, false, factory, ordinary));
        org.junit.Assert.assertNull(DeviceTerminalVisualizationState.presentationOwner(true, true, null, ordinary));
        assertEquals(factory, DeviceTerminalVisualizationState.presentationOwner(true, true, factory, ordinary));
        assertEquals(ordinary, DeviceTerminalVisualizationState.presentationOwner(false, false, factory, ordinary));
    }

    @Test
    public void restoreAcknowledgementsKeepSameTerminalClickClockAndCachedSlot() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        DeviceTerminalVisualizationState.clear();
        DeviceTerminalVisualizationState.apply(first, true);
        Field retry = DeviceTerminalVisualizationState.class.getDeclaredField("retryAtTick");
        Field slot = DeviceTerminalVisualizationState.class.getDeclaredField("inventorySlot");
        retry.setAccessible(true);
        slot.setAccessible(true);
        retry.setLong(null, 560L);
        slot.setInt(null, 17);
        DeviceTerminalVisualizationState.apply(first, true);
        assertEquals(560L, retry.getLong(null));
        assertEquals(17, slot.getInt(null));
        DeviceTerminalVisualizationState.apply(second, true);
        assertEquals(Long.MIN_VALUE, retry.getLong(null));
        assertEquals(-1, slot.getInt(null));
        DeviceTerminalVisualizationState.apply(first, false);
        assertTrue(DeviceTerminalVisualizationState.active(second));
        DeviceTerminalVisualizationState.clear();
    }
}
