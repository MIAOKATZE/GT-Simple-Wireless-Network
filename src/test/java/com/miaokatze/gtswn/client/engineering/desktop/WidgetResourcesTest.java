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
    public void defaultResourceMonitorsPickDistinctStableMountedEntriesAndKeepExactSelections() {
        UUID source = UUID.randomUUID();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Panel a = panel(first), b = panel(second);
        a.rows.add(row(1, 'b'));
        a.rows.add(row(1, 'a'));
        b.rows.add(row(1, 'a')); // The same resource on another network is not another useful default monitor.
        b.rows.add(row(2, 'c'));
        b.rows.add(row(1, 'd'));
        java.util.List<Panel> panels = java.util.Arrays.asList(b, a);
        java.util.List<String> mounts = java.util.Arrays.asList(second.toString(), first.toString());
        DesktopLayout layout = DesktopPreset.overviewLayout();
        assertTrue(WidgetResources.initializeDefaultsCached(layout, source, panels, mounts, p -> true));
        assertEquals(first, layout.find("resources").screen.screen);
        assertEquals(row(1, 'a').key, layout.find("resource1").resource.key);
        assertEquals(row(1, 'b').key, layout.find("resource2").resource.key);
        assertEquals(row(1, 'd').key, layout.find("resource3").resource.key);
        assertEquals(row(2, 'c').key, layout.find("resource4").resource.key);
        for (int index = 1; index <= 4; index++) assertFalse(layout.find("resource" + index).autoResourcePending);
        java.util.Properties initialized = DesktopStore.encode(layout);
        Collections.reverse(a.rows);
        assertFalse(
            WidgetResources.initializeDefaultsCached(layout, source, java.util.Arrays.asList(a, b), mounts, p -> {
                fail("A fully initialized desktop must not rescan inventory receipts");
                return false;
            }));
        assertEquals(initialized, DesktopStore.encode(layout));
        assertTrue(
            layout.replace(
                layout.find("resource1")
                    .boundResource(null)
                    .boundScreen(null)));
        DesktopLayout restored = DesktopStore.decode(DesktopStore.encode(layout));
        assertFalse(WidgetResources.initializeDefaultsCached(restored, source, panels, mounts, p -> true));
        assertNull(restored.find("resource1").resource);
        assertNull(restored.find("resource1").screen);
        assertFalse(restored.find("resource1").autoResourcePending);
    }

    @Test
    public void initializationWaitsForAuthorizedAeReceiptAndNeverOverridesExplicitOrCustomCards() {
        UUID source = UUID.randomUUID(), screen = UUID.randomUUID(), revoked = UUID.randomUUID();
        Panel mounted = panel(screen), unmounted = panel(revoked);
        mounted.rows.add(row(1, 'a'));
        unmounted.rows.add(row(1, 'b'));
        DesktopLayout layout = DesktopPreset.overviewLayout();
        ScreenBinding explicit = new ScreenBinding(source, UUID.randomUUID());
        assertTrue(layout.move("resource1", -8, 8));
        assertTrue(
            layout.replace(
                layout.find("resource1")
                    .boundScreen(explicit)));
        assertTrue(layout.add(new WidgetSpec("custom", WidgetKind.RESOURCES, 0, 10, 4, 2)));
        java.util.List<Panel> panels = java.util.Arrays.asList(unmounted, mounted);
        java.util.List<String> mounts = Collections.singletonList(screen.toString());
        assertFalse(WidgetResources.initializeDefaultsCached(layout, source, panels, mounts, p -> false));
        mounted.aeConnected = false;
        assertFalse(WidgetResources.initializeDefaultsCached(layout, source, panels, mounts, p -> true));
        mounted.aeConnected = true;
        assertTrue(WidgetResources.initializeDefaultsCached(layout, source, panels, mounts, p -> true));
        assertEquals(explicit, layout.find("resource1").screen);
        assertNull(layout.find("resource1").resource);
        assertEquals(row(1, 'a').key, layout.find("resource2").resource.key);
        assertNull(layout.find("resource3").resource);
        assertTrue(layout.find("resource3").autoResourcePending);
        assertNull(layout.find("custom").screen);
        assertNull(layout.find("custom").resource);
        assertEquals(
            1,
            WidgetResources.choicesCached(source, panels, mounts, p -> true)
                .size());
        mounted.rows.add(row(2, 'c'));
        assertTrue(WidgetResources.initializeDefaultsCached(layout, source, panels, mounts, p -> true));
        assertEquals(row(2, 'c').key, layout.find("resource3").resource.key);
        assertNull(layout.find("resource4").resource);
    }

    private static Row row(int kind, char digest) {
        Row row = new Row();
        row.kind = kind;
        row.key = (kind == 1 ? "v2:item:" : "v2:fluid:")
            + String.join("", Collections.nCopies(64, String.valueOf(digest)));
        row.name = "Resource " + digest;
        return row;
    }

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

    @Test
    public void screenCardsRequireExactTerminalAndScreenWithoutFirstScreenFallback() {
        UUID source = UUID.randomUUID(), screen = UUID.randomUUID();
        ScreenBinding binding = new ScreenBinding(source, screen);
        Panel panel = panel(screen);
        java.util.List<Panel> panels = Collections.singletonList(panel);
        java.util.List<String> ids = Collections.singletonList(screen.toString());
        assertEquals("select", WidgetResources.readScreenCached(null, source, panels, ids, p -> true).reason);
        assertEquals(
            "source",
            WidgetResources.readScreenCached(binding, UUID.randomUUID(), panels, ids, p -> true).reason);
        assertSame(panel, WidgetResources.readScreenCached(binding, source, panels, ids, p -> true).panel);
        assertEquals(
            "binding",
            WidgetResources.readScreenCached(binding, source, panels, Collections.emptyList(), p -> true).reason);
        assertEquals(
            "waiting",
            WidgetResources.readScreenCached(binding, source, Collections.emptyList(), ids, p -> true).reason);
        assertNull(WidgetResources.readScreenCached(binding, source, panels, ids, p -> false).panel);
        panel.detail = true;
        assertEquals("unavailable", WidgetResources.readScreenCached(binding, source, panels, ids, p -> false).reason);
        Panel other = panel(UUID.randomUUID());
        assertNull(
            WidgetResources.readScreenCached(binding, source, Collections.singletonList(other), ids, p -> true).panel);
        panel.state = 1;
        assertEquals("unavailable", WidgetResources.readScreenCached(binding, source, panels, ids, p -> false).reason);
    }

    @Test
    public void automaticSourceUsesEveryMountIncludingWaitingScreensAndNeverOverridesExplicitIdentity() {
        UUID source = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        try {
            WidgetResources.update(source, Collections.emptyList(), Collections.singletonList(first.toString()));
            assertEquals(first, WidgetResources.defaultScreen().screen);
            assertEquals("waiting", WidgetResources.readScreen(null).reason);
            assertEquals(
                1,
                WidgetResources.screenChoices()
                    .size());
            WidgetResources
                .update(source, Collections.emptyList(), java.util.Arrays.asList(first.toString(), second.toString()));
            assertNull(WidgetResources.defaultScreen());
            assertEquals("select", WidgetResources.readScreen(null).reason);
            assertEquals("waiting", WidgetResources.readScreen(new ScreenBinding(source, second)).reason);
            assertEquals("binding", WidgetResources.readScreen(new ScreenBinding(source, UUID.randomUUID())).reason);
            WidgetResources.update(null, Collections.emptyList(), Collections.singletonList(first.toString()));
            assertNull(WidgetResources.defaultScreen());
        } finally {
            WidgetResources.update(null, Collections.emptyList());
        }
    }

    private static Panel panel(UUID screen) {
        Panel panel = new Panel();
        panel.binding = new MonitorBindings.Binding(0, 0, 0, 0, screen);
        panel.aeConnected = true;
        return panel;
    }
}
