package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraftforge.common.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.config.Config;

import cpw.mods.fml.relauncher.FMLInjectionData;

public class TestHarnessOptInConfigurationTest {

    @Rule
    public final TemporaryFolder directory = new TemporaryFolder();
    @Rule
    public final TestHarnessOptInRule harness = new TestHarnessOptInRule(false);
    private final Map<Field, Object> original = new LinkedHashMap<>();

    @Before
    public void preserveConfiguration() throws Exception {
        for (Field field : Config.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true);
                original.put(field, field.get(null));
            }
        }
        Field minecraftHome = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        original.put(minecraftHome, minecraftHome.get(null));
        minecraftHome.set(null, directory.getRoot());
    }

    @After
    public void restoreConfiguration() throws Exception {
        for (Map.Entry<Field, Object> entry : original.entrySet()) entry.getKey()
            .set(null, entry.getValue());
    }

    @Test
    public void newConfigurationDefaultsToAnExplicitRestartOnlyOptIn() throws Exception {
        File file = directory.newFile("fresh.cfg");
        Config.synchronizeConfiguration(file);
        assertFalse(Config.testDimensionEnabled);
        Configuration saved = new Configuration(file);
        assertFalse(
            saved.get("testworld", "enableAgentTestHarness", true)
                .getBoolean(true));
    }

    @Test
    public void legacyAutomaticallyEnabledConfigurationDoesNotGrantNewOptIn() throws Exception {
        File file = directory.newFile("legacy.cfg");
        Configuration legacy = new Configuration(file);
        legacy.get("testworld", "enabled", true)
            .set(true);
        legacy.save();
        Config.synchronizeConfiguration(file);
        assertFalse(Config.testDimensionEnabled);
        Configuration saved = new Configuration(file);
        assertTrue(
            saved.get("testworld", "enabled", false)
                .getBoolean(false));
        assertFalse(
            saved.get("testworld", "enableAgentTestHarness", true)
                .getBoolean(true));
    }

    @Test
    public void manuallyEnabledNewKeyWorksEvenWhenLegacyKeyIsFalse() throws Exception {
        File file = directory.newFile("opt-in.cfg");
        Configuration configured = new Configuration(file);
        configured.get("testworld", "enabled", false)
            .set(false);
        configured.get("testworld", "enableAgentTestHarness", false)
            .set(true);
        configured.save();
        Config.synchronizeConfiguration(file);
        assertTrue(Config.testDimensionEnabled);
        harness.restart(Config.testDimensionEnabled);
        assertTrue(DebugTestGate.isEnabled());
    }

    @Test
    public void changingConfigurationCannotChangeTheAlreadyLatchedBootMode() throws Exception {
        DebugTestGate.latchAtStartup(true);
        assertFalse(DebugTestGate.isEnabled());
        harness.restart(true);
        DebugTestGate.latchAtStartup(false);
        assertTrue(DebugTestGate.isEnabled());
        assertFalse(DebugTestGate.probesActive());
    }
}
