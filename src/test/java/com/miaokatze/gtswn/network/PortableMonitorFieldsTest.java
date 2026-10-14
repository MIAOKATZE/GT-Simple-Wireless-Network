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

    @Test
    public void stockRatesShareOneLineInInstantAverageOrderWithTheirOwnSigns() {
        PacketSyncPortableMonitor.Row row = eu();
        row.kind = 1;
        row.name = "Iron";
        String both = PortableMonitorClientCache.pinnedStockText(row, 0, true, true, true, "Instant", "Average (5m)");
        assertTrue(both.startsWith("\u00a7bIron: \u00a7f"));
        assertTrue(both.contains("Instant: \u00a7a+42.50 /min"));
        assertTrue(both.contains("Average (5m): \u00a7c-8.25 /min"));
        assertTrue(both.indexOf("Instant") < both.indexOf("Average"));
        assertFalse(both.contains("\n"));
        String average = PortableMonitorClientCache
            .pinnedStockText(row, 0, false, false, true, "Instant", "Average (5m)");
        assertFalse(average.contains("Instant"));
        assertFalse(average.contains(row.amount));
        assertTrue(average.contains("Average (5m): \u00a7c-8.25 /min"));
    }

    @Test
    public void fluidStockRatesKeepMillibucketUnitsAndCanBeHiddenTogether() {
        PacketSyncPortableMonitor.Row row = eu();
        row.kind = 2;
        row.name = "Water";
        String both = PortableMonitorClientCache.pinnedStockText(row, 0, true, true, true, "Instant", "Average (5m)");
        assertTrue(both.contains(" mB | "));
        assertTrue(both.contains("Instant: \u00a7a+42.50 mB/min"));
        assertTrue(both.contains("Average (5m): \u00a7c-8.25 mB/min"));
        String amountOnly = PortableMonitorClientCache
            .pinnedStockText(row, 0, true, false, false, "Instant", "Average (5m)");
        assertTrue(amountOnly.endsWith(" mB"));
        assertFalse(amountOnly.contains("Instant"));
        assertFalse(amountOnly.contains("Average"));
    }
}
