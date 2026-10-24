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
    public void exactLegacyTemplatesUpgradeOnceAndResetOnlyTheirSavedViewport() throws Exception {
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
                DesktopLayout.defaultLayout()
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
            assertEquals(1, upgraded.loadView(profile).scale, 0);
            assertEquals(0, upgraded.loadView(profile).panX, 0);
            assertTrue(upgraded.flush());
            DesktopLayout persisted = new DesktopStore(folder).load(profile);
            assertFalse(DesktopStore.untouchedLegacyDefault(persisted));
            assertEquals(
                DesktopLayout.defaultLayout()
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
            assertFalse(DesktopStore.untouchedLegacyDefault(screen));
        }
    }
}
