package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import org.junit.Test;

import com.miaokatze.gtswn.client.PortableMonitorClientCache;

public class PortableMonitorFieldsTest {

    private static PacketSyncPortableMonitor.Row eu() {
        PacketSyncPortableMonitor.Row row = new PacketSyncPortableMonitor.Row();
        row.key = "eu";
        row.kind = 0;
        row.amount = "98765";
        row.rate = 42.5;
        row.average = -8.25;
        return row;
    }

    @Test
    public void instantaneousAndAverageAreSeparateFieldsWithIndependentColors() {
        String all = PortableMonitorClientCache
            .pinnedEUText(eu(), true, true, true, false, false, "Instant", "Average");
        assertTrue(all.contains("EU: "));
        assertTrue(all.contains("Instant: \u00a7a+"));
        assertTrue(all.contains("Average: \u00a7c-"));
    }

    @Test
    public void eachDisabledFieldDisappearsWithoutHidingOtherRates() {
        String average = PortableMonitorClientCache
            .pinnedEUText(eu(), false, false, true, false, false, "Instant", "Average");
        assertFalse(average.contains("EU: "));
        assertFalse(average.contains("Instant"));
        assertTrue(average.contains("Average: \u00a7c-"));
        String energy = PortableMonitorClientCache
            .pinnedEUText(eu(), true, false, false, false, false, "Instant", "Average");
        assertTrue(energy.contains("EU: "));
        assertFalse(energy.contains("Instant"));
        assertFalse(energy.contains("Average"));
        assertNull(
            PortableMonitorClientCache.pinnedEUText(eu(), false, false, false, false, false, "Instant", "Average"));
    }

    @Test
    public void compactLabelsPreserveBothSignedRateValues() {
        String compact = PortableMonitorClientCache
            .pinnedEUText(eu(), false, true, true, true, false, "Instant", "Average");
        assertTrue(compact.contains("\u0394t: \u00a7a+"));
        assertTrue(compact.contains("\u0394avg: \u00a7c-"));
        assertFalse(compact.contains("Instant"));
        assertFalse(compact.contains("Average"));
    }
}
