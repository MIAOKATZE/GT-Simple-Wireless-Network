package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DesktopThemeTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void copyResetAndAppendPreserveLocalThemeWhileTemplateApplicationAdoptsItsTheme() {
        DesktopLayout desktop = new DesktopLayout().withTheme(DashboardTheme.MINIMAL);
        DesktopLayout template = new DesktopLayout().withTheme(DashboardTheme.RETRO);
        assertTrue(template.add(new WidgetSpec("source", WidgetKind.MACHINES, 0, 0, 4, 3)));
        assertEquals(
            DashboardTheme.MINIMAL,
            desktop.copy()
                .theme());
        assertEquals(
            1,
            desktop.appendPreset(template)
                .size());
        assertEquals(DashboardTheme.MINIMAL, desktop.theme());
        desktop.reset();
        assertEquals(DashboardTheme.MINIMAL, desktop.theme());
        DesktopLayout applied = DesktopAutoBind.initialize(template);
        assertEquals(DashboardTheme.RETRO, applied.theme());
        applied.setTheme(DashboardTheme.INDUSTRIAL);
        assertEquals(DashboardTheme.RETRO, template.theme());
    }

    @Test
    public void oldFilesDefaultToStandardAndEveryThemePersistsWithoutChangingSchema() throws Exception {
        Properties legacy = DesktopStore.encode(new DesktopLayout());
        assertFalse(legacy.containsKey("theme"));
        assertEquals(
            DashboardTheme.STANDARD,
            DesktopStore.decode(legacy)
                .theme());
        File folder = temporary.newFolder();
        String profile = DesktopStore.profile("themes", "player");
        for (DashboardTheme theme : DashboardTheme.values()) {
            DesktopLayout desktop = new DesktopLayout().withTheme(theme);
            DesktopStore store = new DesktopStore(folder);
            store.put(profile, desktop);
            assertTrue(store.flush());
            assertEquals(
                theme,
                new DesktopStore(folder).load(profile)
                    .theme());
            assertEquals(
                "1",
                DesktopStore.encode(desktop)
                    .getProperty("schema"));
        }
        legacy.setProperty("theme", "FUTURE_THEME");
        assertEquals(
            DashboardTheme.STANDARD,
            DesktopStore.decode(legacy)
                .theme());
    }

    @Test
    public void exactRawLegacyTemplatesMigrateAndKeepTheirSavedThemeAndViewport() throws Exception {
        for (DashboardTheme theme : DashboardTheme.values()) {
            File folder = temporary.newFolder();
            String profile = DesktopStore.profile("legacy-theme", "player");
            DesktopLayout previous = DesktopTestLayouts.v219Overview()
                .withTheme(theme);
            Properties saved = DesktopStore.encode(previous);
            // Include explicit STANDARD as some older external preference editors may do.
            saved.setProperty("theme", theme.name());
            saved.setProperty("view.scale", ".42");
            Path path = folder.toPath()
                .resolve(profile + ".properties");
            try (OutputStream output = Files.newOutputStream(path)) {
                saved.store(output, "legacy desktop");
            }
            DesktopStore restored = new DesktopStore(folder);
            DesktopLayout migrated = restored.load(profile);
            assertEquals(theme, migrated.theme());
            assertEquals(2, migrated.find("resource1").columns);
            assertNotNull(migrated.find("resource5"));
            assertEquals(.42, restored.loadView(profile).scale, 0);
            assertTrue(restored.flush());
            assertEquals(DesktopStore.encode(migrated), DesktopStore.encode(new DesktopStore(folder).load(profile)));
        }
    }

    @Test
    public void unknownRawThemePreventsMigrationAndPreservesOriginalFile() throws Exception {
        File folder = temporary.newFolder();
        String profile = DesktopStore.profile("future-theme", "player");
        Properties saved = DesktopStore.encode(DesktopTestLayouts.v219Overview());
        saved.setProperty("theme", "FUTURE_THEME");
        Path path = folder.toPath()
            .resolve(profile + ".properties");
        try (OutputStream output = Files.newOutputStream(path)) {
            saved.store(output, "future desktop");
        }
        byte[] original = Files.readAllBytes(path);
        DesktopStore store = new DesktopStore(folder);
        DesktopLayout restored = store.load(profile);
        assertEquals(DashboardTheme.STANDARD, restored.theme());
        assertEquals(4, restored.find("resource1").columns);
        assertNull(restored.find("resource5"));
        assertTrue(store.flush());
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test
    public void glowPersistsAndDefaultOmissionPreservesLegacyTemplateDetection() {
        DesktopLayout previous = DesktopTestLayouts.v219Overview();
        Properties defaults = DesktopStore.encode(previous);
        assertFalse(defaults.containsKey("widget.0.effects.glow"));
        assertTrue(DesktopStore.untouchedLegacyDefault(previous));
        WidgetSpec first = previous.widgets()
            .get(0);
        assertTrue(previous.replace(first.styled(first.effects.withGlow(1.7))));
        assertFalse(DesktopStore.untouchedLegacyDefault(previous));
        assertEquals(
            1.7,
            DesktopStore.decode(DesktopStore.encode(previous))
                .find(first.id).effects.glow,
            0);
        defaults.setProperty("widget.0.effects.glow", "NaN");
        assertEquals(
            WidgetEffects.DEFAULT.glow,
            DesktopStore.decode(defaults)
                .widgets()
                .get(0).effects.glow,
            0);
        defaults.setProperty("widget.0.effects.glow", "3");
        assertEquals(
            WidgetEffects.DEFAULT.glow,
            DesktopStore.decode(defaults)
                .widgets()
                .get(0).effects.glow,
            0);
    }
}
