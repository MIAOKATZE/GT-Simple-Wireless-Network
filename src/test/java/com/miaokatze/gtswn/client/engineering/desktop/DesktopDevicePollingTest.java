package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

public class DesktopDevicePollingTest {

    @Test
    public void openSelectorGetsBudgetAheadOfFourVisibleSourcesAndLostPossessionDropsImmediately() {
        UUID factory = UUID.randomUUID(), choice = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID(),
            c = UUID.randomUUID(), d = UUID.randomUUID();
        java.util.Set<UUID> held = new java.util.HashSet<>(Arrays.asList(factory, choice, a, b, c, d));
        assertEquals(
            Arrays.asList(factory, choice, a, b),
            new java.util.ArrayList<>(
                DesktopDevicePolling.sources(factory, choice, Arrays.asList(a, a, b, c, d), held::contains)));
        held.remove(choice);
        assertEquals(
            Arrays.asList(factory, a, b, c),
            new java.util.ArrayList<>(
                DesktopDevicePolling.sources(factory, choice, Arrays.asList(a, b, c, d), held::contains)));
        held.clear();
        assertTrue(
            DesktopDevicePolling.sources(factory, choice, Arrays.asList(a, b, c, d), held::contains)
                .isEmpty());
    }

    @Test
    public void nativeViewerPausesSharedDispatchAndClosingItResumesTheNextFairSource() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger considered = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Predicate<UUID> due = id -> {
            considered.incrementAndGet();
            return true;
        };
        for (int tick = 0; tick < 40; tick++) {
            assertNull(DesktopDevicePolling.next(Arrays.asList(a, b), a, true, due));
        }
        assertEquals(0, considered.get());
        assertEquals(b, DesktopDevicePolling.next(Arrays.asList(a, b), a, false, due));
        assertEquals(1, considered.get());
        // Closing/changing the real screen uses the current flag; no remembered suspension can leak.
        assertEquals(a, DesktopDevicePolling.next(Arrays.asList(a, b), b, false, due));
    }

    @Test
    public void fourSourcesRotateFairlyAndDuplicateCardsDoNotCreateDemands() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        java.util.Set<UUID> sources = new java.util.LinkedHashSet<>(Arrays.asList(a, a, b, c, d, b));
        assertEquals(4, sources.size());
        UUID cursor = null;
        for (UUID expected : Arrays.asList(a, b, c, d, a, b, c, d)) {
            cursor = DesktopDevicePolling.next(sources, cursor, id -> true);
            assertEquals(expected, cursor);
        }
        assertEquals(c, DesktopDevicePolling.next(sources, d, id -> id.equals(c)));
        assertNull(DesktopDevicePolling.next(sources, a, id -> false));
        assertNull(DesktopDevicePolling.next(Collections.emptyList(), null, id -> true));
    }

    @Test
    public void replacedTypeOrCoordinatesNeverMatchTheSavedDevice() {
        DeviceBinding binding = new DeviceBinding(UUID.randomUUID(), "device", 2, 3, 4, 5, 6);
        assertTrue(DesktopDeviceSources.matches(binding, entry(2, 3, 4, 5, 6)));
        assertFalse(DesktopDeviceSources.matches(binding, entry(2, 3, 4, 5, 7)));
        assertFalse(DesktopDeviceSources.matches(binding, entry(0, 3, 4, 5, 6)));
        assertFalse(DesktopDeviceSources.matches(binding, entry(2, 9, 4, 5, 6)));
    }

    private static com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry entry(int dim, int x, int y, int z,
        int meta) {
        return new com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry(
            "device",
            "Device",
            (byte) 1,
            (byte) 0,
            10,
            20,
            10,
            20,
            dim,
            x,
            y,
            z,
            "",
            "",
            meta,
            (byte) 2,
            (byte) 0);
    }
}
