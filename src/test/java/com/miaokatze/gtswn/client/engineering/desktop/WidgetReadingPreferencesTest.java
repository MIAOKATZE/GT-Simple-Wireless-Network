package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

public class WidgetReadingPreferencesTest {

    @Test
    public void rateDefaultsAndImmutableEditsPreserveSelectedModeAndWindow() {
        WidgetSpec grid = new WidgetSpec("grid", WidgetKind.WIRELESS, 0, 0, 4, 3);
        assertTrue(grid.averageRate);
        assertEquals(30, grid.averageWindowSeconds);
        WidgetSpec edited = grid.withRate(false, 75)
            .titled("Grid")
            .bordered(0x123456)
            .configured("Grid", Collections.singletonList("status"), 1)
            .presented(WidgetPresentation.GAUGE, "stored", null)
            .boundDevice(null)
            .boundScreen(null)
            .withBounds(1, 2, 4, 4);
        assertFalse(edited.averageRate);
        assertEquals(75, edited.averageWindowSeconds);
        assertEquals(1, edited.withRate(true, 0).averageWindowSeconds);
        assertEquals(3600, edited.withRate(true, Integer.MAX_VALUE).averageWindowSeconds);
    }

    @Test
    public void pendingAutomaticResourceSurvivesAppearanceEditsAndExplicitBindingConsumesIt() {
        WidgetSpec pending = new WidgetSpec("resource", WidgetKind.RESOURCES, 0, 0, 2, 1).withSingleResource(true)
            .withAutoResource(true)
            .withRate(false, 75)
            .titled("Item")
            .bordered(0x123456)
            .configured("Item", Collections.emptyList(), 1)
            .withBounds(1, 1, 2, 1);
        assertTrue(pending.autoResourcePending);
        assertTrue(pending.singleResource);
        assertFalse(pending.averageRate);
        assertEquals(75, pending.averageWindowSeconds);
        assertFalse(pending.boundResource(null).autoResourcePending);
        assertFalse(pending.boundScreen(null).autoResourcePending);
        assertTrue(pending.boundResource(null).singleResource);
        assertTrue(pending.boundScreen(new ScreenBinding(UUID.randomUUID(), UUID.randomUUID())).singleResource);
        ResourceBinding resource = new ResourceBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "v2:item:" + String.join("", Collections.nCopies(64, "a")),
            "Item");
        WidgetSpec bound = pending.boundResource(resource);
        assertFalse(bound.autoResourcePending);
        assertTrue(bound.singleResource);
        assertEquals(2, bound.minColumns());
        assertEquals(1, bound.minRows());
    }
}
