package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Row;
import com.miaokatze.gtswn.network.PortablePanelRenderData;

public class WidgetResourcesTest {

    private static final String ITEM = "v2:item:" + String.join("", Collections.nCopies(64, "a"));

    @Test
    public void sourceScreenTypeAndFullIdentityAreAllRequired() {
        UUID source = UUID.randomUUID(), screen = UUID.randomUUID();
        ResourceBinding binding = new ResourceBinding(source, screen, 1, ITEM, "Same display name");
        Panel panel = panel(screen);
        Row row = new Row();
        row.kind = 1;
        row.key = ITEM.substring(0, ITEM.length() - 1) + "b";
        row.name = "Same display name";
        panel.rows.add(row);
        assertNull(WidgetResources.readCached(binding, source, Collections.singletonList(panel), p -> true).row);
        row.key = ITEM;
        assertSame(row, WidgetResources.readCached(binding, source, Collections.singletonList(panel), p -> true).row);
        assertNull(
            WidgetResources.readCached(binding, UUID.randomUUID(), Collections.singletonList(panel), p -> true).row);
        row.kind = 2;
        assertNull(WidgetResources.readCached(binding, source, Collections.singletonList(panel), p -> true).row);
        row.kind = 1;
        panel.binding = new MonitorBindings.Binding(0, 0, 0, 0, UUID.randomUUID());
        assertNull(WidgetResources.readCached(binding, source, Collections.singletonList(panel), p -> true).row);
    }

    @Test
    public void revokedStaleUnloadedAndDisconnectedSourcesNeverExposeCachedRows() {
        UUID source = UUID.randomUUID(), screen = UUID.randomUUID();
        ResourceBinding binding = new ResourceBinding(source, screen, 1, ITEM, "Resource");
        Panel panel = panel(screen);
        Row row = new Row();
        row.kind = 1;
        row.key = ITEM;
        row.amount = "0";
        panel.rows.add(row);
        assertSame(row, WidgetResources.readCached(binding, source, Collections.singletonList(panel), p -> true).row);
        assertNull(WidgetResources.readCached(binding, source, Collections.singletonList(panel), p -> false).row);
        panel.aeConnected = false;
        assertEquals(
            "offline",
            WidgetResources.readCached(binding, source, Collections.singletonList(panel), p -> true).reason);
        assertNull(WidgetResources.readCached(binding, source, Collections.emptyList(), p -> true).row);
    }

    @Test
    public void coldZeroAndMissingRateStayUnknown() {
        Panel panel = panel(UUID.randomUUID());
        Row row = new Row();
        row.key = ITEM;
        row.monitored = true;
        panel.chartKey = ITEM;
        panel.render = new PortablePanelRenderData();
        panel.render.ae.add(new AEMonitorSample(0, 0, 12, 0));
        assertFalse(WidgetResources.rateKnown(panel, row));
        panel.render.ae.add(new AEMonitorSample(5000, 100, 12, 0));
        assertTrue(WidgetResources.rateKnown(panel, row));
        row.rate = Double.NaN;
        assertFalse(WidgetResources.rateKnown(panel, row));
        row.rate = 10;
        row.monitored = false;
        assertFalse(WidgetResources.rateKnown(panel, row));
    }

    @Test(expected = IllegalArgumentException.class)
    public void legacyDisplayNamesCannotBecomeExactResourceBindings() {
        new ResourceBinding(UUID.randomUUID(), UUID.randomUUID(), 1, "item:minecraft:stone:0", "Stone");
    }

    private static Panel panel(UUID screen) {
        Panel panel = new Panel();
        panel.binding = new MonitorBindings.Binding(0, 0, 0, 0, screen);
        panel.aeConnected = true;
        return panel;
    }
}
