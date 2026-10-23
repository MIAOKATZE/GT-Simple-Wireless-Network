package com.miaokatze.gtswn.client.engineering.terminal;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.client.DeviceTerminalVisualizationState;

public class FactoryVisualizationReceiptTest {

    @Test
    public void currentSourceRequiresANewFreshReceiptAndMatchingUuid() {
        UUID selected = UUID.randomUUID(), other = UUID.randomUUID();
        assertFalse(EngineeringTerminalHudSnapshot.acceptsVisualization(selected, selected, true, 7, 7, true));
        assertFalse(EngineeringTerminalHudSnapshot.acceptsVisualization(selected, other, true, 7, 8, true));
        assertFalse(EngineeringTerminalHudSnapshot.acceptsVisualization(selected, selected, false, 7, 8, true));
        assertFalse(EngineeringTerminalHudSnapshot.acceptsVisualization(selected, selected, true, 7, 8, false));
        assertFalse(EngineeringTerminalHudSnapshot.acceptsVisualization(selected, null, false, 7, 8, true));
        assertTrue(EngineeringTerminalHudSnapshot.acceptsVisualization(selected, selected, true, 7, 8, true));
    }

    @Test
    public void sameVersionFullReplyAndHeartbeatAdvanceTheReceiptWithoutGrantingTravel() {
        UUID id = UUID.randomUUID(), other = UUID.randomUUID();
        DeviceTerminalClientCache.clear();
        DeviceTerminalVisualizationState.clear();
        DeviceTerminalVisualizationState.apply(other, true);
        DeviceTerminalClientCache.receivePage(id, 5, 0, 1, Collections.emptyList());
        long baseline = DeviceTerminalClientCache.receiptRevision(id);
        DeviceTerminalClientCache.receivePage(id, 5, 0, 1, Collections.emptyList());
        assertTrue(DeviceTerminalClientCache.receiptRevision(id) > baseline);
        baseline = DeviceTerminalClientCache.receiptRevision(id);
        DeviceTerminalClientCache.receivePage(id, 5, -1, 1, Collections.emptyList());
        assertTrue(DeviceTerminalClientCache.receiptRevision(id) > baseline);
        assertFalse(DeviceTerminalVisualizationState.active(id));
        assertTrue(DeviceTerminalVisualizationState.active(other));
        DeviceTerminalClientCache.clear();
        DeviceTerminalVisualizationState.clear();
    }

    @Test
    public void factoryDisplayToggleDoesNotChangeTheAcknowledgedOrdinarySession() {
        UUID id = UUID.randomUUID();
        DeviceTerminalVisualizationState.clear();
        DeviceTerminalVisualizationState.apply(id, true);
        boolean before = EngineeringTerminalActions.deviceVisualizationEnabled();
        try {
            EngineeringTerminalActions.toggleDeviceVisualization();
            assertEquals(!before, EngineeringTerminalActions.deviceVisualizationEnabled());
            assertTrue(DeviceTerminalVisualizationState.active(id));
            DeviceTerminalVisualizationState.apply(id, false);
            assertFalse(DeviceTerminalVisualizationState.active(id));
        } finally {
            if (EngineeringTerminalActions.deviceVisualizationEnabled() != before)
                EngineeringTerminalActions.toggleDeviceVisualization();
            DeviceTerminalVisualizationState.clear();
        }
    }
}
