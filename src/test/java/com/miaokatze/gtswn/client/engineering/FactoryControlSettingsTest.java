package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.miaokatze.gtswn.client.engineering.FactoryControlSettings.Setting;

public class FactoryControlSettingsTest {

    private Field minecraftHome;
    private Object originalHome;

    @Before
    public void initializeForgeConfigDirectory() throws Exception {
        minecraftHome = cpw.mods.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        originalHome = minecraftHome.get(null);
        minecraftHome.set(null, temporary.getRoot());
    }

    @After
    public void restoreForgeConfigDirectory() throws Exception {
        if (minecraftHome != null) minecraftHome.set(null, originalHome);
    }

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void invalidValuesFallBackAndFiniteMultipliersStayWithinBounds() {
        for (Setting setting : Setting.values()) {
            assertEquals(1, setting.sanitize(Double.NaN), 0);
            assertEquals(1, setting.sanitize(Double.POSITIVE_INFINITY), 0);
            assertEquals(1, setting.sanitize(0), 0);
            assertEquals(1, setting.sanitize(-2), 0);
            assertEquals(.25, setting.sanitize(.01), 0);
            assertEquals(4, setting.sanitize(50), 0);
        }
    }

    @Test
    public void adjustmentStopsAtTheBoundaryAndUpdatesThePolicyRevision() {
        try {
            FactoryControlSettings.set(Setting.KEYBOARD_PAN, .25);
            long revision = FactoryControlSettings.revision();
            FactoryControlSettings.adjust(Setting.KEYBOARD_PAN, -1);
            assertEquals(.25, FactoryControlSettings.value(Setting.KEYBOARD_PAN), 0);
            assertEquals(revision, FactoryControlSettings.revision());
            FactoryControlSettings.adjust(Setting.KEYBOARD_PAN, 1);
            assertEquals(.5, FactoryControlSettings.value(Setting.KEYBOARD_PAN), 0);
            assertTrue(FactoryControlSettings.revision() > revision);
            FactoryControlSettings.adjust(Setting.KEYBOARD_PAN, Integer.MAX_VALUE);
            assertEquals(4, FactoryControlSettings.value(Setting.KEYBOARD_PAN), 0);
        } finally {
            FactoryControlSettings.resetDefaults();
        }
    }

    @Test
    public void explicitSettingsPersistWithoutCreatingAFileForDefaults() {
        File file = new File(temporary.getRoot(), "camera.cfg");
        FactoryControlSettings.Store first = new FactoryControlSettings.Store(file);
        assertFalse(file.exists());
        first.set(Setting.KEYBOARD_PAN, 1.5);
        first.set(Setting.MOUSE_PAN, .5);
        first.set(Setting.ROTATION, 2);
        first.set(Setting.WHEEL_ZOOM, 1.25);
        assertTrue(file.isFile());
        FactoryControlSettings.Store reloaded = new FactoryControlSettings.Store(file);
        assertEquals(1.5, reloaded.value(Setting.KEYBOARD_PAN), 0);
        assertEquals(.5, reloaded.value(Setting.MOUSE_PAN), 0);
        assertEquals(2, reloaded.value(Setting.ROTATION), 0);
        assertEquals(1.25, reloaded.value(Setting.WHEEL_ZOOM), 0);
        reloaded.preset(1);
        FactoryControlSettings.Store reset = new FactoryControlSettings.Store(file);
        for (Setting setting : Setting.values()) assertEquals(1, reset.value(setting), 0);
    }
}
