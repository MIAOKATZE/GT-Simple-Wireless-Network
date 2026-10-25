package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopDefaultMigrationTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void savedV224InformationFootersArePreservedForEveryOperationalPreset() throws Exception {
        for (DesktopPreset preset : DesktopPreset.values()) {
            if (preset == DesktopPreset.INFORMATION) continue;
            DesktopLayout previous = preset.createLayout();
            for (WidgetSpec widget : new java.util.ArrayList<>(previous.widgets()))
                if (widget.kind.isInformation()) assertTrue(previous.remove(widget.id));
            if (preset == DesktopPreset.OVERVIEW) {
                for (WidgetSpec widget : new java.util.ArrayList<>(previous.widgets())) assertTrue(
                    previous.replace(widget.withBounds(widget.column, widget.row - 2, widget.columns, widget.rows)));
            }
            int row = previous.bounds().bottom / DesktopLayout.CELL;
            boolean player = preset == DesktopPreset.OVERVIEW || preset == DesktopPreset.PRODUCTION
                || preset == DesktopPreset.DIAGNOSTICS;
            String[] ids = { "clock", "gameClock", "session", "team", player ? "player" : "world" };
            WidgetKind[] kinds = { WidgetKind.CLOCK, WidgetKind.CLOCK, WidgetKind.SESSION, WidgetKind.TEAM,
                player ? WidgetKind.PLAYER : WidgetKind.WORLD };
            for (int index = 0; index < ids.length; index++) {
                WidgetSpec widget = new WidgetSpec(ids[index], kinds[index], index * 2, row, 2, 2);
                if (index < 2) widget = widget.configured(
                    "",
                    (index == 0 ? DesktopInformationTemplate.LOCAL_CLOCK : DesktopInformationTemplate.GAME_CLOCK)
                        .fields(),
                    1);
                assertTrue(previous.add(widget));
            }
            assertFalse(DesktopStore.untouchedLegacyDefault(previous));
            assertLayoutPreserved(previous);
        }
    }

    @Test
    public void v218DefaultGainsCompactMonitorsAndRetainsRemovedBoundCardsAndSavedZoom() throws Exception {
        DesktopLayout previous = DesktopTestLayouts.v218Overview();
        bindScreensAndResource(previous);
        DeviceBinding machine = new DeviceBinding(UUID.randomUUID(), "machine", 0, 1, 2, 3, 4);
        assertTrue(
            previous.replace(
                previous.find("device")
                    .boundDevice(machine)));
        assertTrue(DesktopStore.untouchedLegacyDefault(previous));
        java.io.File folder = temporary.newFolder();
        String profile = DesktopStore.profile("v218", "player");
        DesktopStore old = new DesktopStore(folder);
        old.put(profile, previous);
        DesktopViewport view = new DesktopViewport();
        view.scale = .37;
        view.panX = 83;
        old.putView(profile, view);
        assertTrue(old.flush());
        DesktopStore upgraded = new DesktopStore(folder);
        DesktopLayout migrated = upgraded.load(profile);
        assertEquals(
            12,
            migrated.widgets()
                .size());
        assertEquals(machine, migrated.find("device").device);
        assertEquals(previous.find("resources").resource.key, migrated.find("resources").resource.key);
        assertFalse(migrated.find("resources").autoResourcePending);
        assertTrue(migrated.find("resource1").autoResourcePending);
        assertEquals(.37, upgraded.loadView(profile).scale, 0);
        assertEquals(83, upgraded.loadView(profile).panX, 0);
        assertTrue(upgraded.flush());
        assertEquals(DesktopStore.encode(migrated), DesktopStore.encode(new DesktopStore(folder).load(profile)));
        DesktopLayout custom = previous.copy();
        assertTrue(
            custom.replace(
                custom.find("netRate")
                    .withRate(false, 180)));
        assertFalse(DesktopStore.untouchedLegacyDefault(custom));
        assertLayoutPreserved(custom);
    }

    @Test
    public void v217DefaultWithOnlyOperationalBindingsMigratesAndRetainsEveryReference() throws Exception {
        DesktopLayout previous = boundV217Overview();
        assertTrue(DesktopStore.untouchedLegacyDefault(previous));
        assertMigratedReferences(previous);
    }

    @Test
    public void olderDefaultWithOnlyScreenBindingsMigratesButMachineBindingsKeepTheirLayout() throws Exception {
        for (boolean tall : new boolean[] { false, true }) {
            DesktopLayout previous = DesktopTestLayouts.legacyDefault(tall);
            bindScreensAndResource(previous);
            assertTrue(DesktopStore.untouchedLegacyDefault(previous));
            assertMigratedReferences(previous);
            assertTrue(
                previous.replace(
                    previous.find("input")
                        .boundDevice(new DeviceBinding(UUID.randomUUID(), "machine", 0, 1, 2, 3, 4))));
            assertFalse(DesktopStore.untouchedLegacyDefault(previous));
            assertLayoutPreserved(previous);
        }
    }

    private void assertMigratedReferences(DesktopLayout previous) throws Exception {
        java.io.File folder = temporary.newFolder();
        String profile = DesktopStore.profile("bound-default", "player");
        DesktopStore old = new DesktopStore(folder);
        old.put(profile, previous);
        assertTrue(old.flush());
        DesktopStore upgraded = new DesktopStore(folder);
        DesktopLayout migrated = upgraded.load(profile);
        int retained = 0;
        DesktopLayout fresh = DesktopStore.v221Overview();
        for (WidgetSpec original : previous.widgets()) if (fresh.find(original.id) == null
            && (original.device != null || original.resource != null || original.screen != null)) retained++;
        assertEquals(
            fresh.widgets()
                .size() + retained,
            migrated.widgets()
                .size());
        assertEquals(WidgetPresentation.GAUGE, migrated.find("wireless").presentation);
        for (WidgetSpec widget : migrated.widgets()) {
            WidgetSpec original = previous.find(widget.id);
            if (original == null) continue;
            assertEquals(original.screen, widget.screen);
            assertEquals(original.device, widget.device);
        }
        for (WidgetSpec original : previous.widgets())
            if (original.device != null || original.resource != null || original.screen != null)
                assertNotNull("Retained binding for " + original.id, migrated.find(original.id));
        ResourceBinding originalResource = previous.find("resources").resource;
        ResourceBinding migratedResource = migrated.find("resources").resource;
        assertEquals(originalResource.source, migratedResource.source);
        assertEquals(originalResource.screen, migratedResource.screen);
        assertEquals(originalResource.kind, migratedResource.kind);
        assertEquals(originalResource.key, migratedResource.key);
        assertEquals(originalResource.name, migratedResource.name);
        assertTrue(upgraded.flush());
        assertEquals(DesktopStore.encode(migrated), DesktopStore.encode(new DesktopStore(folder).load(profile)));
    }

    @Test
    public void exactV219DefaultKeepsAllFourResourceBindingsAndAddsOnlyOneNewPendingSlot() throws Exception {
        DesktopLayout previous = DesktopTestLayouts.v219Overview();
        UUID source = UUID.randomUUID(), screen = UUID.randomUUID();
        for (int index = 1; index <= 4; index++) {
            ResourceBinding binding = new ResourceBinding(
                source,
                screen,
                1,
                "v2:item:" + String.join("", Collections.nCopies(64, Integer.toString(index))),
                "Resource " + index);
            assertTrue(
                previous.replace(
                    previous.find("resource" + index)
                        .boundResource(binding)));
        }
        assertTrue(DesktopStore.untouchedLegacyDefault(previous));
        java.io.File folder = temporary.newFolder();
        String profile = DesktopStore.profile("v219-bound", "player");
        DesktopStore old = new DesktopStore(folder);
        old.put(profile, previous);
        DesktopViewport view = new DesktopViewport();
        view.scale = .43;
        view.panX = -123;
        old.putView(profile, view);
        assertTrue(old.flush());
        DesktopStore restored = new DesktopStore(folder);
        DesktopLayout upgraded = restored.load(profile);
        assertEquals(
            10,
            upgraded.widgets()
                .size());
        for (int index = 1; index <= 4; index++) {
            WidgetSpec widget = upgraded.find("resource" + index);
            ResourceBinding expected = previous.find(widget.id).resource;
            assertEquals(expected.source, widget.resource.source);
            assertEquals(expected.screen, widget.resource.screen);
            assertEquals(expected.key, widget.resource.key);
            assertEquals(2, widget.columns);
            assertEquals(2, widget.rows);
            assertFalse(widget.autoResourcePending);
        }
        assertTrue(upgraded.find("resource5").autoResourcePending);
        assertEquals(.43, restored.loadView(profile).scale, 0);
        assertEquals(-123, restored.loadView(profile).panX, 0);
        assertTrue(restored.flush());
        assertEquals(DesktopStore.encode(upgraded), DesktopStore.encode(new DesktopStore(folder).load(profile)));
    }

    @Test
    public void v219GeometryOrStyleCustomizationPreventsMigration() throws Exception {
        DesktopLayout previous = DesktopTestLayouts.v219Overview();
        assertTrue(
            previous.replace(
                previous.find("resource3")
                    .titled("Reserve")));
        assertFalse(DesktopStore.untouchedLegacyDefault(previous));
        assertLayoutPreserved(previous);
        previous = DesktopTestLayouts.v219Overview();
        assertTrue(previous.move("resource4", -4, 12));
        assertFalse(DesktopStore.untouchedLegacyDefault(previous));
        assertLayoutPreserved(previous);
    }

    @Test
    public void boundV217DefaultWithCustomizedAppearanceNeverMigrates() throws Exception {
        DesktopLayout previous = boundV217Overview();
        WidgetSpec original = previous.find("wireless");
        WidgetSpec[] changes = { original.titled("My grid"), original.withBounds(-4, -4, 4, 2),
            original.bordered(0x123456), original.configured("", Collections.emptyList(), 1),
            original.presented(WidgetPresentation.BAR, "stored", null),
            original.presented(WidgetPresentation.KPI, "netRate", null),
            original.presented(WidgetPresentation.KPI, "stored", 100.0) };
        for (WidgetSpec changed : changes) {
            DesktopLayout custom = previous.copy();
            assertTrue(custom.replace(changed));
            assertFalse(DesktopStore.untouchedLegacyDefault(custom));
            assertLayoutPreserved(custom);
        }
    }

    private void assertLayoutPreserved(DesktopLayout layout) throws Exception {
        java.io.File folder = temporary.newFolder();
        String profile = DesktopStore.profile("bound-custom", "player");
        DesktopStore old = new DesktopStore(folder);
        old.put(profile, layout);
        assertTrue(old.flush());
        assertEquals(DesktopStore.encode(layout), DesktopStore.encode(new DesktopStore(folder).load(profile)));
    }

    @Test
    public void malformedOperationalBindingOnV217DefaultCannotBeDiscardedByMigration() throws Exception {
        for (String key : new String[] { "widget.1.screen.id", "widget.5.resource.key", "widget.7.source" }) {
            java.io.File folder = temporary.newFolder();
            String profile = DesktopStore.profile("v217-bound-invalid", "player");
            Properties saved = DesktopStore.encode(boundV217Overview());
            saved.setProperty(key, "invalid");
            java.nio.file.Path path = folder.toPath()
                .resolve(profile + ".properties");
            try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(path)) {
                saved.store(output, "invalid operational binding");
            }
            byte[] original = java.nio.file.Files.readAllBytes(path);
            DesktopStore restored = new DesktopStore(folder);
            assertEquals(
                8,
                restored.load(profile)
                    .widgets()
                    .size());
            assertTrue(restored.flush());
            assertArrayEquals(original, java.nio.file.Files.readAllBytes(path));
        }
    }

    private static DesktopLayout boundV217Overview() {
        DesktopLayout previous = DesktopTestLayouts.v217Overview();
        bindScreensAndResource(previous);
        assertTrue(
            previous.replace(
                previous.find("netRate")
                    .boundScreen(new ScreenBinding(UUID.randomUUID(), UUID.randomUUID()))));
        assertTrue(
            previous.replace(
                previous.find("device")
                    .boundDevice(new DeviceBinding(UUID.randomUUID(), "machine", 0, 1, 2, 3, 4))));
        return previous;
    }

    private static void bindScreensAndResource(DesktopLayout previous) {
        for (String id : new String[] { "wireless", "history", "resources" }) {
            assertTrue(
                previous.replace(
                    previous.find(id)
                        .boundScreen(new ScreenBinding(UUID.randomUUID(), UUID.randomUUID()))));
        }
        ResourceBinding resource = new ResourceBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "v2:item:" + String.join("", Collections.nCopies(64, "a")),
            "Circuit");
        assertTrue(
            previous.replace(
                previous.find("resources")
                    .boundResource(resource)));
    }

    @Test
    public void exactV217DefaultMigratesOnceAndPreservesCustomizedVersion() throws Exception {
        java.io.File folder = temporary.newFolder();
        String profile = DesktopStore.profile("v217", "player");
        DesktopStore old = new DesktopStore(folder);
        DesktopLayout previous = DesktopTestLayouts.v217Overview();
        assertTrue(DesktopStore.untouchedLegacyDefault(previous));
        old.put(profile, previous);
        DesktopViewport view = new DesktopViewport();
        view.panX = 500;
        old.putView(profile, view);
        assertTrue(old.flush());
        DesktopStore upgraded = new DesktopStore(folder);
        assertEquals(DesktopStore.encode(DesktopStore.v221Overview()), DesktopStore.encode(upgraded.load(profile)));
        assertEquals(500, upgraded.loadView(profile).panX, 0);
        assertTrue(upgraded.flush());
        assertFalse(DesktopStore.untouchedLegacyDefault(new DesktopStore(folder).load(profile)));

        WidgetSpec original = previous.find("machines");
        WidgetSpec[] changes = { original.titled("mine"), original.bordered(0x123456),
            original.configured("", Collections.emptyList(), 1),
            original.presented(WidgetPresentation.KPI, "running", null),
            original.presented(WidgetPresentation.KPI, "count", 16.0), original.withBounds(-4, -4, 4, 2) };
        for (WidgetSpec changed : changes) {
            DesktopLayout custom = previous.copy();
            assertTrue(custom.replace(changed));
            assertFalse(DesktopStore.untouchedLegacyDefault(custom));
            old.put(profile, custom);
            old.putView(profile, view);
            assertTrue(old.flush());
            DesktopStore restored = new DesktopStore(folder);
            assertEquals(DesktopStore.encode(custom), DesktopStore.encode(restored.load(profile)));
            assertEquals(500, restored.loadView(profile).panX, 0);
        }
    }

    @Test
    public void v217MalformedOrFuturePreferencesPreserveOriginalFileAndViewport() throws Exception {
        for (String suffix : new String[] { "screen.id", "presentation", "metricField", "futurePreference" }) {
            java.io.File folder = temporary.newFolder();
            String profile = DesktopStore.profile("v217-raw", "player");
            Properties saved = DesktopStore.encode(DesktopTestLayouts.v217Overview());
            saved.setProperty("widget.0." + suffix, "invalid");
            saved.setProperty("view.x", "500");
            java.nio.file.Path path = folder.toPath()
                .resolve(profile + ".properties");
            try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(path)) {
                saved.store(output, "custom");
            }
            byte[] original = java.nio.file.Files.readAllBytes(path);
            DesktopStore restored = new DesktopStore(folder);
            assertEquals(
                8,
                restored.load(profile)
                    .widgets()
                    .size());
            assertEquals(500, restored.loadView(profile).panX, 0);
            assertTrue(restored.flush());
            assertArrayEquals(original, java.nio.file.Files.readAllBytes(path));
        }
    }

    @Test
    public void malformedOptionalBindingsAndDisplayPreferencesNeverTriggerMigration() throws Exception {
        for (String suffix : new String[] { "source", "resource.source", "screen.id", "userTarget", "presentation",
            "borderColor", "metricField" }) assertRawPreferencePreserved(suffix, "invalid");
    }

    @Test
    public void decoderNormalizationCannotEraseEvidenceOfCustomizationBeforeMigration() throws Exception {
        assertRawPreferencePreserved("title", "\u0001");
        assertRawPreferencePreserved("fields", "futureField");
        assertRawPreferencePreserved("fieldsConfigured", "invalid");
        assertRawPreferencePreserved("displayColumns", "0");
        assertRawPreferencePreserved("futurePreference", "custom");
    }

    private void assertRawPreferencePreserved(String suffix, String value) throws Exception {
        java.io.File folder = temporary.newFolder();
        String profile = DesktopStore.profile("legacy", "player");
        Properties saved = DesktopStore.encode(DesktopTestLayouts.legacyDefault(false));
        saved.setProperty("widget.0." + suffix, value);
        saved.setProperty("view.x", "500");
        java.nio.file.Path path = folder.toPath()
            .resolve(profile + ".properties");
        try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(path)) {
            saved.store(output, "legacy custom preferences");
        }
        byte[] original = java.nio.file.Files.readAllBytes(path);
        assertTrue(DesktopStore.untouchedLegacyDefault(DesktopStore.decode(saved)));
        DesktopStore restored = new DesktopStore(folder);
        assertEquals(
            8,
            restored.load(profile)
                .widgets()
                .size());
        assertNotNull(
            restored.load(profile)
                .find("input"));
        assertEquals(500, restored.loadView(profile).panX, 0);
        assertTrue(restored.flush());
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(path));
    }

    @Test
    public void exactLegacyTemplatesUpgradeOnceAndPreserveTheirSavedViewport() throws Exception {
        for (boolean tall : new boolean[] { false, true }) {
            java.io.File folder = temporary.newFolder();
            String profile = DesktopStore.profile("world" + tall, "player");
            DesktopStore old = new DesktopStore(folder);
            DesktopLayout legacy = DesktopTestLayouts.legacyDefault(tall);
            assertTrue(DesktopStore.untouchedLegacyDefault(legacy));
            old.put(profile, legacy);
            DesktopViewport view = new DesktopViewport();
            view.scale = .2;
            view.panX = 500;
            old.putView(profile, view);
            assertTrue(old.flush());
            DesktopStore upgraded = new DesktopStore(folder);
            assertEquals(
                DesktopStore.v221Overview()
                    .widgets()
                    .size(),
                upgraded.load(profile)
                    .widgets()
                    .size());
            assertNotNull(
                upgraded.load(profile)
                    .find("netRate"));
            assertNull(
                upgraded.load(profile)
                    .find("input"));
            assertEquals(.2, upgraded.loadView(profile).scale, 0);
            assertEquals(500, upgraded.loadView(profile).panX, 0);
            assertTrue(upgraded.flush());
            DesktopLayout persisted = new DesktopStore(folder).load(profile);
            assertFalse(DesktopStore.untouchedLegacyDefault(persisted));
            assertEquals(
                DesktopStore.v221Overview()
                    .widgets()
                    .size(),
                persisted.widgets()
                    .size());
        }
    }

    @Test
    public void anyCustomLayoutOrPreferencePreventsTemplateMigration() {
        for (boolean tall : new boolean[] { false, true }) {
            DesktopLayout legacy = DesktopTestLayouts.legacyDefault(tall);
            WidgetSpec original = legacy.find("machines");
            WidgetSpec[] changes = { original.titled("mine"), original.bordered(0x123456),
                original.configured("", Collections.emptyList(), 1),
                original.presented(WidgetPresentation.KPI, "count", null),
                original.presented(WidgetPresentation.DEFAULT, "running", 10.0),
                original.withBounds(-4, -4, original.columns, original.rows) };
            for (WidgetSpec changed : changes) {
                DesktopLayout custom = legacy.copy();
                assertTrue(custom.replace(changed));
                assertFalse(DesktopStore.untouchedLegacyDefault(custom));
            }
            DesktopLayout bound = legacy.copy();
            assertTrue(
                bound.replace(
                    bound.find("input")
                        .boundDevice(new DeviceBinding(UUID.randomUUID(), "machine", 0, 1, 2, 3, 4))));
            assertFalse(DesktopStore.untouchedLegacyDefault(bound));
            DesktopLayout screen = legacy.copy();
            assertTrue(
                screen.replace(
                    screen.find("wireless")
                        .boundScreen(new ScreenBinding(UUID.randomUUID(), UUID.randomUUID()))));
            assertTrue(DesktopStore.untouchedLegacyDefault(screen));
        }
    }
}
