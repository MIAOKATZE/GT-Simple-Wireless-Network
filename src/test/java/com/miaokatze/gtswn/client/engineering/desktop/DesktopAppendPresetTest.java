package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

public class DesktopAppendPresetTest {

    @Test
    public void repeatedAppendKeepsOriginalCardsAndRelativeGeometryWithIndependentIds() {
        DesktopLayout layout = DesktopPreset.overviewLayout();
        WidgetSpec original = layout.find("wireless");
        Properties originalData = DesktopStore.encode(layout);
        DesktopLayout template = DesktopPreset.overviewLayout();
        List<WidgetSpec> first = layout.appendPreset(template, 0, 0);
        List<WidgetSpec> second = layout.appendPreset(template, 0, 0);
        assertEquals(10, first.size());
        assertEquals(10, second.size());
        assertSame(original, layout.find("wireless"));
        assertEquals(
            30,
            layout.widgets()
                .size());
        Set<String> ids = new HashSet<>();
        for (WidgetSpec widget : layout.widgets()) {
            assertTrue(ids.add(widget.id));
            assertTrue(layout.fits(widget, widget.id));
        }
        for (List<WidgetSpec> group : java.util.Arrays.asList(first, second)) {
            int dx = group.get(0).column - template.widgets()
                .get(0).column;
            int dy = group.get(0).row - template.widgets()
                .get(0).row;
            for (int index = 0; index < group.size(); index++) {
                WidgetSpec placed = group.get(index), source = template.widgets()
                    .get(index);
                assertEquals(source.column + dx, placed.column);
                assertEquals(source.row + dy, placed.row);
                assertEquals(source.columns, placed.columns);
                assertEquals(source.rows, placed.rows);
                assertNull(placed.device);
                assertNull(placed.screen);
                assertNull(placed.resource);
            }
        }
        Properties full = DesktopStore.encode(layout);
        for (String key : originalData.stringPropertyNames())
            if (!key.equals("count")) assertEquals(originalData.getProperty(key), full.getProperty(key));
    }

    @Test
    public void cardinalityFailureDoesNotPartiallyAppend() {
        DesktopLayout layout = new DesktopLayout();
        for (int index = 0; index < 23; index++) assertNotNull(layout.add(WidgetKind.MACHINES, index * 5, 0));
        Properties before = DesktopStore.encode(layout);
        assertTrue(
            layout.appendPreset(DesktopPreset.overviewLayout())
                .isEmpty());
        assertEquals(before, DesktopStore.encode(layout));
    }

    @Test
    public void wholeGroupWithNoLegalTranslationIsRejectedWithoutChangingExistingCards() {
        DesktopLayout template = new DesktopLayout();
        assertTrue(template.add(new WidgetSpec("left", WidgetKind.MACHINES, -DesktopLayout.MAX_COORD, 0, 4, 3)));
        assertTrue(template.add(new WidgetSpec("right", WidgetKind.MACHINES, DesktopLayout.MAX_COORD - 4, 0, 4, 3)));
        assertTrue(template.add(new WidgetSpec("top", WidgetKind.MACHINES, 0, -DesktopLayout.MAX_COORD, 4, 3)));
        assertTrue(template.add(new WidgetSpec("bottom", WidgetKind.MACHINES, 0, DesktopLayout.MAX_COORD - 3, 4, 3)));
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(new WidgetSpec("occupied", WidgetKind.MACHINES, -DesktopLayout.MAX_COORD, 0, 4, 3)));
        Properties before = DesktopStore.encode(layout);
        assertTrue(
            layout.appendPreset(template)
                .isEmpty());
        assertEquals(before, DesktopStore.encode(layout));
    }

    @Test
    public void collisionSearchUsesFreeSpaceBetweenSparseMembersAndHonorsCanvasEdges() {
        DesktopLayout template = new DesktopLayout();
        assertTrue(template.add(new WidgetSpec("left", WidgetKind.MACHINES, 0, 0, 4, 3)));
        assertTrue(template.add(new WidgetSpec("right", WidgetKind.MACHINES, 8, 0, 4, 3)));
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(new WidgetSpec("middle", WidgetKind.MACHINES, 4, 0, 4, 3)));
        List<WidgetSpec> appended = layout.appendPreset(template, 0, 0);
        assertEquals(0, appended.get(0).column);
        assertEquals(0, appended.get(0).row);
        assertEquals(8, appended.get(1).column);
        List<WidgetSpec> boundary = layout.appendPreset(template, Integer.MAX_VALUE, Integer.MIN_VALUE);
        assertEquals(2, boundary.size());
        for (WidgetSpec card : boundary) assertTrue(layout.fits(card, card.id));
    }

    @Test
    public void appendStripsAllTerminalBindingsAndRetainsVisualAndResourcePreferences() {
        DesktopLayout template = new DesktopLayout();
        DeviceBinding device = new DeviceBinding(UUID.randomUUID(), "device", 0, 1, 2, 3, 4);
        assertTrue(
            template.add(
                new WidgetSpec("machine", WidgetKind.DEVICE, 0, 0, 5, 3).boundDevice(device)
                    .titled("My machine")
                    .bordered(0x123456)));
        ResourceBinding resource = new ResourceBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "v2:item:" + String.join("", java.util.Collections.nCopies(64, "a")),
            "Circuit");
        assertTrue(template.add(new WidgetSpec("single", WidgetKind.RESOURCES, 5, 0, 2, 2).boundResource(resource)));
        assertTrue(
            template.add(
                new WidgetSpec("table", WidgetKind.RESOURCES, 7, 0, 6, 3)
                    .boundScreen(new ScreenBinding(resource.source, resource.screen))));
        DesktopLayout layout = new DesktopLayout();
        List<WidgetSpec> added = layout.appendPreset(template, 10, 20);
        assertEquals(3, added.size());
        assertEquals("My machine", added.get(0).title);
        assertEquals(0xFF123456, added.get(0).borderColor);
        assertTrue(added.get(1).singleResource);
        assertFalse(added.get(2).singleResource);
        for (WidgetSpec widget : added) {
            assertNull(widget.device);
            assertNull(widget.screen);
            assertNull(widget.resource);
            if (widget.kind == WidgetKind.RESOURCES) assertTrue(widget.autoResourcePending);
        }
        assertSame(device, template.find("machine").device);
        assertSame(resource, template.find("single").resource);
    }
}
