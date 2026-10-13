package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DeviceTerminalHeldIndexTest {

    @Before
    public void reset() {
        DeviceTerminalDataStore.clearActiveTerminals();
    }

    @After
    public void cleanup() {
        DeviceTerminalDataStore.clearActiveTerminals();
    }

    @Test
    public void droppingLastHolderStopsSamplingWithoutLogoutButSharedHolderRemains() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), terminal = UUID.randomUUID();
        DeviceTerminalDataStore.onPlayerLoggedIn(first, Collections.singleton(terminal));
        DeviceTerminalDataStore.ensureActiveTerminal(first, terminal);
        DeviceTerminalDataStore.onPlayerLoggedIn(second, Collections.singleton(terminal));
        DeviceTerminalDataStore.synchronizeHeldTerminals(first, Collections.emptySet());
        assertTrue(DeviceTerminalDataStore.isTerminalActive(terminal));
        DeviceTerminalDataStore.synchronizeHeldTerminals(second, Collections.emptySet());
        assertFalse(DeviceTerminalDataStore.isTerminalActive(terminal));
    }

    @Test
    public void historicalTerminalsDoNotAppearInActiveSamplingSnapshot() {
        DeviceTerminalDataStore store = new DeviceTerminalDataStore("history");
        for (int i = 0; i < 2000; i++) store.getOrCreateTerminal(UUID.randomUUID());
        UUID player = UUID.randomUUID(), terminal = UUID.randomUUID();
        store.getOrCreateTerminal(terminal);
        DeviceTerminalDataStore.ensureActiveTerminal(player, terminal);
        assertEquals(
            1,
            store.getActiveTerminals()
                .size());
        assertEquals(
            terminal,
            store.getActiveTerminals()
                .get(0)
                .getKey());
        DeviceTerminalDataStore.onPlayerLoggedOut(player);
        assertTrue(
            store.getActiveTerminals()
                .isEmpty());
    }
}
