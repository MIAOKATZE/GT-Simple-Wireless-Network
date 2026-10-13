package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.miaokatze.gtswn.client.PortableMonitorClientCache.PinnedLine;

public class PortableHudLayoutTest {

    @Test
    public void plotDoesNotStretchListValuesAndMixedRowsKeepTheirOwnHeight() {
        PinnedLine plot = new PinnedLine("", new double[61], new double[61], 0);
        PinnedLine value = new PinnedLine("Iron: 256", new double[0]);
        assertEquals(48, PortableHudLayout.height(plot, 0));
        assertEquals(12, PortableHudLayout.height(value, 0));
        assertEquals(240, PortableHudLayout.width(plot, 0, 240));
        assertEquals(53, PortableHudLayout.width(value, 53, 240));
        List<List<PinnedLine>> pages = PortableHudLayout.pages(Arrays.asList(plot, value, value), 16, 72, 0);
        assertEquals(1, pages.size());
        assertEquals(
            3,
            pages.get(0)
                .size());
        assertEquals(
            2,
            PortableHudLayout.pages(Arrays.asList(plot, value, value), 16, 60, 0)
                .size());
    }
}
