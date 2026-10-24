package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

import org.junit.Test;

public class DesktopBindingTest {

    @Test
    public void componentsCanBePlacedSavedBoundChangedAndClearedWithoutTelemetry() {
        DeviceBinding first = new DeviceBinding(UUID.randomUUID(), "first", 0, 1, 2, 3, 4);
        DeviceBinding second = new DeviceBinding(UUID.randomUUID(), "second", 2, 5, 6, 7, 8);
        DesktopLayout layout = new DesktopLayout();
        for (WidgetKind kind : new WidgetKind[] { WidgetKind.DEVICE, WidgetKind.INPUT, WidgetKind.OUTPUT }) {
            WidgetSpec blank = layout.add(kind);
            assertNotNull(blank);
            assertNull(blank.device);
            WidgetSpec restored = DesktopStore.decode(DesktopStore.encode(layout))
                .find(blank.id);
            assertNotNull(restored);
            assertNull(restored.device);
            WidgetSpec bound = restored.boundDevice(first)
                .titled("chosen")
                .bordered(0x2277AA)
                .configured("chosen", Collections.singletonList("source"), 1);
            assertTrue(layout.replace(bound));
            assertEquals(
                first,
                DesktopStore.decode(DesktopStore.encode(layout))
                    .find(blank.id).device);
            assertTrue(layout.replace(bound.boundDevice(second)));
            assertEquals(second, layout.find(blank.id).device);
            assertTrue(
                layout.replace(
                    layout.find(blank.id)
                        .boundDevice(null)));
            assertEquals("chosen", layout.find(blank.id).title);
            assertNull(layout.find(blank.id).device);
        }
        for (String key : DesktopStore.encode(layout)
            .stringPropertyNames()) {
            assertFalse(key.contains("snapshot"));
            assertFalse(key.contains("receipt"));
            assertFalse(key.contains("permission"));
            assertFalse(key.contains("amount"));
        }
    }

    @Test
    public void screenIdentitySurvivesEveryCopyAndExactResourceMigratesItsScreenReference() {
        ScreenBinding source = new ScreenBinding(UUID.randomUUID(), UUID.randomUUID());
        ScreenBinding another = new ScreenBinding(source.source, UUID.randomUUID());
        for (WidgetKind kind : new WidgetKind[] { WidgetKind.WIRELESS, WidgetKind.HISTORY, WidgetKind.RESOURCES }) {
            WidgetSpec original = new WidgetSpec("screen", kind, 0, 0, 8, 5).boundScreen(source)
                .titled("title")
                .bordered(0x223344)
                .configured("title", Collections.emptyList(), 1)
                .presented(WidgetPresentation.DEFAULT, "", null)
                .withBounds(-8, -4, 8, 5);
            DesktopLayout layout = new DesktopLayout();
            assertTrue(layout.add(original));
            WidgetSpec restored = DesktopStore.decode(DesktopStore.encode(layout))
                .find(original.id);
            assertEquals(source, restored.screen);
            assertEquals(another, restored.boundScreen(another).screen);
            assertNull(restored.boundScreen(null).screen);
        }
        ResourceBinding resource = new ResourceBinding(
            source.source,
            source.screen,
            1,
            "v2:item:" + String.join("", Collections.nCopies(64, "a")),
            "Item");
        WidgetSpec single = new WidgetSpec("resource", WidgetKind.RESOURCES, 0, 0, 4, 2).boundResource(resource);
        assertEquals(source, single.screen);
        assertEquals(source, single.boundResource(null).screen);
        assertNull(single.boundScreen(another).resource);
        assertNull(single.boundScreen(null).resource);
        assertEquals(4, single.boundScreen(null).columns);
        assertEquals(2, single.boundScreen(null).rows);
        assertEquals(6, single.boundScreen(another).columns);
        assertEquals(3, single.boundScreen(another).rows);
        DesktopLayout compact = new DesktopLayout();
        assertTrue(compact.add(single));
        assertTrue(compact.replace(single.boundScreen(null)));
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(single));
        Properties old = DesktopStore.encode(layout);
        old.remove("widget.0.screen.source");
        old.remove("widget.0.screen.id");
        assertEquals(
            source,
            DesktopStore.decode(old)
                .find("resource").screen);
    }

    @Test
    public void malformedOptionalDeviceReferenceBecomesAnEmptyCardWithoutDroppingGeometry() {
        DesktopLayout layout = new DesktopLayout();
        WidgetSpec card = layout.add(WidgetKind.DEVICE);
        Properties saved = DesktopStore.encode(layout);
        saved.setProperty("widget.0.source", "invalid");
        saved.setProperty("widget.0.key", "machine");
        WidgetSpec restored = DesktopStore.decode(saved)
            .find(card.id);
        assertNotNull(restored);
        assertNull(restored.device);
        assertEquals(card.columns, restored.columns);
        assertEquals(card.rows, restored.rows);
    }
}
