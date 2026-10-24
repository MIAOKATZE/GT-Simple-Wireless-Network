package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopStoreTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void borderAndExactResourceIdentitySurviveConfigurationAndPersistence() {
        String key = "v2:item:" + String.join("", java.util.Collections.nCopies(64, "a"));
        ResourceBinding binding = new ResourceBinding(UUID.randomUUID(), UUID.randomUUID(), 1, key, "Circuit");
        WidgetSpec spec = new WidgetSpec("bound", WidgetKind.RESOURCES, 0, 0, 8, 4).boundResource(binding)
            .bordered(0x15ABEF)
            .titled("Custom")
            .configured("Custom", java.util.Collections.singletonList("source"), 1)
            .presented(WidgetPresentation.STATUS_LIST, "", null)
            .withBounds(-8, -4, 8, 4);
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(spec));
        Properties data = DesktopStore.encode(layout);
        WidgetSpec restored = DesktopStore.decode(data)
            .find("bound");
        assertEquals(0xFF15ABEF, restored.borderColor);
        assertEquals(binding.source, restored.resource.source);
        assertEquals(binding.screen, restored.resource.screen);
        assertEquals(key, restored.resource.key);
        assertEquals(1, restored.resource.kind);
        assertEquals("Circuit", restored.resource.name);
        assertFalse(data.containsKey("widget.0.amount"));
        assertFalse(data.containsKey("widget.0.rate"));
        data.setProperty("widget.0.borderColor", "invalid");
        assertEquals(
            WidgetSpec.DEFAULT_BORDER_COLOR,
            DesktopStore.decode(data)
                .find("bound").borderColor);
    }

    @Test
    public void pendingCommittedSnapshotDoesNotFollowUnreleasedGeometryAndViewportEdits() throws Exception {
        Path directory = temporary.newFolder()
            .toPath();
        String profile = DesktopStore.profile("world", "player");
        DesktopStore store = new DesktopStore(directory.toFile());
        DesktopLayout layout = DesktopTestLayouts.dragFixture();
        DesktopViewport view = new DesktopViewport();
        store.put(profile, layout);
        store.putView(profile, view);
        DesktopDrag drag = new DesktopDrag();
        drag.begin(layout, "machines", DesktopDrag.Edge.MOVE, 0, 0);
        assertTrue(drag.update(-256, 0));
        view.pan(128, 0);
        assertTrue(store.saveIfDue(2000000000L));
        DesktopStore duringGesture = new DesktopStore(directory.toFile());
        assertEquals(
            0,
            duringGesture.load(profile)
                .find("machines").column);
        assertEquals(0, duringGesture.loadView(profile).panX, 0);
        drag.cancel();
        view.panX = 0;
        store.put(profile, layout);
        store.putView(profile, view);
        assertTrue(store.flush());
        assertEquals(
            0,
            new DesktopStore(directory.toFile()).load(profile)
                .find("machines").column);
        drag.begin(layout, "machines", DesktopDrag.Edge.MOVE, 0, 0);
        assertTrue(drag.update(-256, 0));
        assertTrue(drag.finish());
        store.put(profile, layout);
        assertTrue(store.flush());
        assertEquals(
            -4,
            new DesktopStore(directory.toFile()).load(profile)
                .find("machines").column);
    }

    @Test
    public void roundTripKeepsCustomSizeBindingAndFieldsWithoutBusinessData() throws Exception {
        DesktopLayout layout = new DesktopLayout();
        DeviceBinding binding = new DeviceBinding(UUID.randomUUID(), "stable-key", -1, 30, 40, 50, 7);
        WidgetSpec spec = new WidgetSpec(
            "device",
            WidgetKind.DEVICE,
            -40,
            500,
            9,
            DesktopLayout.defaultLayout()
                .widgets()
                .size(),
            "My machine",
            binding,
            Arrays.asList("status", "averageInput", "coordinates"),
            2);
        assertTrue(layout.add(spec));
        Properties p = DesktopStore.encode(layout);
        WidgetSpec restored = DesktopStore.decode(p)
            .find("device");
        assertEquals(binding, restored.device);
        assertEquals(spec.fields, restored.fields);
        assertEquals(9, restored.columns);
        assertEquals(-40, restored.column);
        assertEquals("My machine", restored.title);
        assertEquals(19, p.size());
        for (String key : p.stringPropertyNames()) {
            assertFalse(key.contains("permission"));
            assertFalse(key.contains("snapshot"));
            assertFalse(key.contains("receipt"));
            assertFalse(key.contains("sample"));
        }
    }

    @Test
    public void explicitEmptyFieldsSurviveTitleGeometryAndStoreWithoutRestoringDefaults() {
        WidgetSpec template = DesktopLayout.defaultLayout()
            .find("machines");
        assertFalse(template.fieldsConfigured);
        assertFalse(template.titled("template title").fieldsConfigured);
        WidgetSpec empty = template.configured("empty", java.util.Collections.<String>emptyList(), 1)
            .titled("renamed")
            .withBounds(-4, -4, 6, 3);
        assertTrue(empty.fieldsConfigured);
        assertTrue(empty.fields.isEmpty());
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(empty));
        WidgetSpec restored = DesktopStore.decode(DesktopStore.encode(layout))
            .find(empty.id);
        assertTrue(restored.fieldsConfigured);
        assertTrue(restored.fields.isEmpty());
        assertEquals("renamed", restored.title);
        assertEquals(-4, restored.column);
    }

    @Test
    public void legacyFieldsInferExplicitSelectionOnlyWhenNonempty() {
        Properties defaults = DesktopStore.encode(DesktopTestLayouts.dragFixture());
        defaults.remove("widget.0.fieldsConfigured");
        assertFalse(
            DesktopStore.decode(defaults)
                .find("machines").fieldsConfigured);
        defaults.setProperty("widget.0.fields", "status");
        WidgetSpec explicit = DesktopStore.decode(defaults)
            .find("machines");
        assertTrue(explicit.fieldsConfigured);
        assertEquals(Arrays.asList("status"), explicit.fields);
    }

    @Test
    public void badOrUnknownWidgetDoesNotDiscardValidSiblings() {
        Properties p = DesktopStore.encode(DesktopLayout.defaultLayout());
        p.setProperty("widget.1.kind", "FUTURE_PLUGIN");
        p.setProperty("widget.2.column", "2147483647");
        p.setProperty("widget.3.rows", "-1");
        DesktopLayout recovered = DesktopStore.decode(p);
        assertEquals(
            DesktopLayout.defaultLayout()
                .widgets()
                .size() - 3,
            recovered.widgets()
                .size());
        assertNotNull(recovered.find("resources"));
        assertNotNull(recovered.find("machines"));
    }

    @Test
    public void profilesAreIsolatedAndViewportMergesWithPendingLayout() throws Exception {
        Path directory = temporary.newFolder()
            .toPath();
        String first = DesktopStore.profile("local:world", "player");
        String second = DesktopStore.profile("server:world", "player");
        assertNotEquals(first, second);
        assertNotEquals(first, DesktopStore.profile("local:world", "another-player"));
        assertNotEquals(DesktopStore.profile("a", "bc"), DesktopStore.profile("ab", "c"));
        DesktopStore store = new DesktopStore(directory.toFile());
        DesktopLayout layout = new DesktopLayout();
        layout.add(WidgetKind.INPUT, -100, 2);
        store.put(first, layout);
        DesktopViewport view = new DesktopViewport();
        view.panX = -1234;
        view.scale = .5;
        store.putView(first, view);
        assertTrue(store.saveIfDue(2000000000L));
        DesktopStore reopened = new DesktopStore(directory.toFile());
        assertEquals(
            -100,
            reopened.load(first)
                .widgets()
                .get(0).column);
        assertEquals(-1234, reopened.loadView(first).panX, 0);
        assertEquals(.5, reopened.loadView(first).scale, 0);
        assertEquals(
            DesktopLayout.defaultLayout()
                .widgets()
                .size(),
            reopened.load(second)
                .widgets()
                .size());
        assertEquals(
            1,
            directory.toFile()
                .list().length);
    }

    @Test
    public void emptyDesktopPersistsAndRepeatedEditsCoalesceWithinThrottle() throws Exception {
        Path directory = temporary.newFolder()
            .toPath();
        String profile = DesktopStore.profile("world", "player");
        DesktopStore store = new DesktopStore(directory.toFile());
        store.put(profile, new DesktopLayout());
        assertTrue(store.saveIfDue(2000000000L));
        DesktopLayout changed = new DesktopLayout();
        changed.add(WidgetKind.INPUT);
        store.put(profile, changed);
        assertFalse(store.saveIfDue(2100000000L));
        assertEquals(
            0,
            new DesktopStore(directory.toFile()).load(profile)
                .widgets()
                .size());
        assertTrue(store.flush());
        assertEquals(
            1,
            new DesktopStore(directory.toFile()).load(profile)
                .widgets()
                .size());
        assertEquals(
            1,
            directory.toFile()
                .list().length);
    }

    @Test
    public void corruptFileFallsBackAndInvalidViewNeverEscapesFiniteBounds() throws Exception {
        Path directory = temporary.newFolder()
            .toPath();
        String profile = DesktopStore.profile("world", "player");
        Files.write(directory.resolve(profile + ".properties"), "schema=\\uBROKEN".getBytes("UTF-8"));
        DesktopStore store = new DesktopStore(directory.toFile());
        assertEquals(
            DesktopLayout.defaultLayout()
                .widgets()
                .size(),
            store.load(profile)
                .widgets()
                .size());
        DesktopViewport view = new DesktopViewport();
        view.panX = Double.NaN;
        view.panY = Double.MAX_VALUE;
        view.scale = 0;
        store.putView(profile, view);
        assertTrue(store.flush());
        DesktopViewport restored = new DesktopStore(directory.toFile()).loadView(profile);
        assertEquals(0, restored.panX, 0);
        assertEquals((double) DesktopLayout.MAX_COORD * DesktopLayout.CELL, restored.panY, 0);
        assertEquals(DesktopViewport.MIN_SCALE, restored.scale, 0);
    }
}
