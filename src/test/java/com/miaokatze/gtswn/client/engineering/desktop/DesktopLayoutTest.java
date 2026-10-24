package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.UUID;

import org.junit.Test;

public class DesktopLayoutTest {

    @Test
    public void compactUnboundResourcePreferencesRemainReadableWhenAutomaticSourceIsIntroduced() {
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(new WidgetSpec("customStock", WidgetKind.RESOURCES, -8, -4, 4, 2).titled("Stock")));
        WidgetSpec restored = DesktopStore.decode(DesktopStore.encode(layout))
            .find("customStock");
        assertNotNull(restored);
        assertEquals(4, restored.columns);
        assertEquals(2, restored.rows);
        assertEquals(-8, restored.column);
        assertEquals("Stock", restored.title);
        assertNull(restored.screen);
        assertNull(restored.resource);
    }

    @Test
    public void exactResourcePlacementUsesCompactHoleWithoutAnIntermediateTable() {
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(new WidgetSpec("left", WidgetKind.MACHINES, 0, 0, 4, 64)));
        assertTrue(layout.add(new WidgetSpec("right", WidgetKind.MACHINES, 8, 0, 8, 64)));
        assertTrue(layout.add(new WidgetSpec("below", WidgetKind.MACHINES, 4, 3, 4, 61)));
        assertTrue(layout.add(new WidgetSpec("limit", WidgetKind.MACHINES, 0, DesktopLayout.MAX_COORD - 3, 4, 3)));
        assertFalse(
            "A complete table does not fit the only remaining hole",
            layout.add(
                new WidgetSpec("table", WidgetKind.RESOURCES, 4, 0, 6, 3)
                    .boundScreen(new ScreenBinding(UUID.randomUUID(), UUID.randomUUID()))));
        ResourceBinding binding = new ResourceBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "v2:item:" + String.join("", java.util.Collections.nCopies(64, "a")),
            "Circuit");
        WidgetSpec resource = layout.addResource(binding);
        assertNotNull(resource);
        assertSame(binding, resource.resource);
        assertEquals(4, resource.column);
        assertEquals(0, resource.row);
        assertEquals(4, resource.columns);
        assertEquals(2, resource.rows);
        assertEquals(
            5,
            layout.widgets()
                .size());
    }

    @Test
    public void resourceCardsKeepSavedGeometryButAllowCompactResizeAndRestoreTableMinimum() {
        ResourceBinding binding = new ResourceBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            "v2:fluid:" + String.join("", java.util.Collections.nCopies(64, "b")),
            "Steam");
        WidgetSpec old = new WidgetSpec("resource", WidgetKind.RESOURCES, 0, 0, 8, 4).boundResource(binding);
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(old));
        WidgetSpec restored = DesktopStore.decode(DesktopStore.encode(layout))
            .find("resource");
        assertEquals(8, restored.columns);
        assertEquals(4, restored.rows);
        assertTrue(layout.resize("resource", 0, 0, 4, 3));
        assertFalse(layout.resize("resource", 0, 0, 3, 3));
        WidgetSpec table = layout.find("resource")
            .boundResource(null);
        assertEquals(6, table.columns);
        assertEquals(3, table.rows);
        assertEquals(6, table.minColumns());
        assertEquals(3, table.minRows());
    }

    @Test
    public void defaultArrangementIsBalancedWithinInitialAreaAndHasEveryRealSource() {
        DesktopLayout layout = DesktopLayout.defaultLayout();
        assertEquals(
            DesktopLayout.defaultLayout()
                .widgets()
                .size(),
            layout.widgets()
                .size());
        for (WidgetSpec widget : layout.widgets()) {
            assertTrue(layout.fits(widget, widget.id));
            assertTrue(widget.x() >= 0 && widget.y() >= 0);
            assertTrue(widget.x() + widget.width() <= DesktopLayout.DEFAULT_SIZE);
            assertTrue(widget.y() + widget.height() <= DesktopLayout.DEFAULT_SIZE);
            assertNull(widget.device);
        }
        assertEquals(
            1024,
            layout.bounds()
                .width(),
            0);
        assertEquals(
            512,
            layout.bounds()
                .height(),
            0);
        DesktopViewport view = new DesktopViewport();
        view.fit(layout, 640, 308);
        assertTrue("Default fit uses most of a widescreen viewport", view.scale > .5);
        for (String id : new String[] { "machines", "wireless" }) {
            WidgetSpec instrument = layout.find(id);
            assertTrue(instrument.width() * view.scale >= 130);
            assertTrue(instrument.height() * view.scale >= 64);
        }
    }

    @Test
    public void moveBeyondInitialAreaAndAcrossNegativeCoordinatesHasNoCanvasClamp() {
        DesktopLayout layout = DesktopLayout.defaultLayout();
        assertTrue(layout.move("machines", -100, 100));
        assertEquals(
            -6400,
            layout.find("machines")
                .x());
        assertEquals(
            6400,
            layout.find("machines")
                .y());
        assertTrue(
            layout.move("machines", -DesktopLayout.MAX_COORD, DesktopLayout.MAX_COORD - layout.find("machines").rows));
        assertFalse(layout.move("machines", Integer.MAX_VALUE, 0));
        assertFalse(layout.move("machines", 0, Integer.MIN_VALUE));
    }

    @Test
    public void collisionsAndTooSmallResizeAreRejectedWithoutMutatingOriginal() {
        DesktopLayout layout = DesktopTestLayouts.dragFixture();
        WidgetSpec original = layout.find("machines");
        assertFalse(layout.move("machines", 4, 0));
        assertFalse(layout.resize("machines", 0, 0, 2, 1));
        assertFalse(layout.resize("machines", 0, 0, Integer.MAX_VALUE, 2));
        assertSame(original, layout.find("machines"));
        assertTrue(layout.resize("machines", -4, -4, 8, 4));
    }

    @Test
    public void displayFieldsDriveMinimumSizeWithoutShrinkingCustomGeometry() {
        WidgetSpec spec = new WidgetSpec(
            "custom",
            WidgetKind.DEVICE,
            0,
            0,
            9,
            9,
            "custom",
            binding(),
            Arrays.asList("status", "input", "output", "coordinates", "not-a-field", "status"),
            1);
        assertEquals(4, spec.fields.size());
        assertTrue("A few compact fields fit the ordinary device card", spec.minRows() <= 3);
        WidgetSpec configured = spec.configured("title\n§red", Arrays.asList("status", "input"), 2);
        assertEquals("titlered", configured.title);
        assertEquals(9, configured.columns);
        assertEquals(9, configured.rows);
    }

    @Test
    public void onlyBackedFieldsAreSelectableAndAliasesNeverDuplicateDeviceFields() {
        assertFalse(
            WidgetSpec.availableFields(WidgetKind.DEVICE)
                .contains("stored"));
        assertFalse(
            WidgetSpec.availableFields(WidgetKind.DEVICE)
                .contains("capacity"));
        assertFalse(
            WidgetSpec.availableFields(WidgetKind.DEVICE)
                .contains("progress"));
        assertFalse(
            WidgetSpec.availableFields(WidgetKind.DEVICE)
                .contains("input"));
        assertTrue(
            WidgetSpec.availableFields(WidgetKind.WIRELESS)
                .contains("stored"));
        assertFalse(
            WidgetSpec.availableFields(WidgetKind.MACHINES)
                .contains("coordinates"));
        WidgetSpec device = new WidgetSpec(
            "device",
            WidgetKind.DEVICE,
            0,
            0,
            4,
            3,
            "",
            binding(),
            Arrays.asList("stored", "progress", "input", "instantInput", "output", "capacity"),
            1);
        assertEquals(Arrays.asList("instantInput", "instantOutput"), device.fields);
        WidgetSpec overview = new WidgetSpec(
            "overview",
            WidgetKind.MACHINES,
            0,
            0,
            4,
            3,
            "",
            null,
            Arrays.asList("coordinates", "name", "status", "source"),
            1);
        assertEquals(Arrays.asList("status", "source"), overview.fields);
    }

    @Test
    public void manyFieldsNeedMoreSpaceAndColumnLayoutTradesWidthForHeight() {
        WidgetSpec few = new WidgetSpec(
            "few",
            WidgetKind.DEVICE,
            0,
            0,
            4,
            3,
            "",
            binding(),
            Arrays.asList("status", "instantInput", "instantOutput"),
            1);
        WidgetSpec many = few.configured("", WidgetSpec.availableFields(WidgetKind.DEVICE), 1);
        assertEquals(3, few.minRows());
        assertTrue(many.minRows() > few.minRows());
        WidgetSpec columns = many.configured("", many.fields, 4);
        assertTrue(columns.minRows() < many.minRows());
        assertTrue(columns.minColumns() > many.minColumns());
        assertTrue(columns.rows >= many.rows);
    }

    @Test
    public void sparseLayoutLimitsInstancesRatherThanGeneratingCanvasCells() {
        DesktopLayout layout = new DesktopLayout();
        for (int i = 0; i < DesktopLayout.MAX_WIDGETS; i++)
            assertNotNull(layout.add(WidgetKind.MACHINES, i * 10 - 100, -100));
        assertNull(layout.add(WidgetKind.MACHINES));
        assertEquals(
            DesktopLayout.MAX_WIDGETS,
            layout.widgets()
                .size());
        assertFalse(layout.add(new WidgetSpec("device", WidgetKind.DEVICE, 0, 0, 3, 3)));
    }

    @Test
    public void deviceReferenceRequiresStableSourceAndDoesNotHoldLiveData() {
        DesktopLayout layout = new DesktopLayout();
        DeviceBinding binding = binding();
        WidgetSpec device = layout.addDevice(binding);
        assertNotNull(device);
        assertEquals(binding, device.device);
        assertEquals(WidgetKind.DEVICE, device.kind);
        assertNotSame(layout, layout.copy());
        assertSame(
            device,
            layout.copy()
                .find(device.id));
    }

    private static DeviceBinding binding() {
        return new DeviceBinding(UUID.randomUUID(), "machine-key", -1, 8, 64, 9, 42);
    }
}
