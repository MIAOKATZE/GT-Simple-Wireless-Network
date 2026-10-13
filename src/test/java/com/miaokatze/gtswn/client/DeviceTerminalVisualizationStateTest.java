package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
