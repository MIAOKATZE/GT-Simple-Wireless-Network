package com.miaokatze.gtswn.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraftforge.common.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.miaokatze.gtswn.common.command.CommandGTSWNClient;

import cpw.mods.fml.relauncher.FMLInjectionData;

public class QuantumParticleQualityConfigTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final Map<Field, Object> originalConfig = new HashMap<>();

    @Before
    public void rememberGlobalConfiguration() throws IllegalAccessException {
        for (Field field : Config.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true);
                originalConfig.put(field, field.get(null));
            }
        }
    }

    @After
    public void restoreGlobalConfiguration() throws IllegalAccessException {
        for (Map.Entry<Field, Object> entry : originalConfig.entrySet()) {
            entry.getKey()
                .set(null, entry.getValue());
        }
    }

    @Test
    public void particleTogglePersistsWithoutChangingQualityOrExistingVisualSettings() throws Exception {
        Field minecraftHome = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        Object originalHome = minecraftHome.get(null);
        minecraftHome.set(null, temporaryFolder.getRoot());
        try {
            File configFile = temporaryFolder.newFile("gtswn_network.cfg");
            Configuration initial = new Configuration(configFile);
            initial.get("hud", "HudXOffset", 123)
                .set(123);
            initial.get("custom", "PreservedSetting", "keep-me")
                .set("keep-me");
            initial.get("client", "LinkNodeOpacity", .625D)
                .set(.625D);
            initial.get("client", "LinkNodeDepth", .0625D)
                .set(.0625D);
            initial.get("client", "LinkNodeRelief", -.75D)
                .set(-.75D);
            initial.save();
            Config.synchronizeNetworkConfiguration(configFile);
            assertTrue(Config.particlesEnabled);
            ICommandSender sender = (ICommandSender) Proxy.newProxyInstance(
                ICommandSender.class.getClassLoader(),
                new Class<?>[] { ICommandSender.class },
                (proxy, method, args) -> null);
            CommandGTSWNClient command = new CommandGTSWNClient();
            String[] qualities = { "low", "medium", "high" };
            double[] densities = { 0D, .5D, 1D };
            for (int i = 0; i < qualities.length; i++) {
                assertTrue(Config.setQuantumParticleQuality(qualities[i]));
                command.processCommand(sender, new String[] { "particle", "off" });
                assertFalse(Config.particlesEnabled);
                assertEquals(qualities[i], Config.quantumParticleQuality);
                assertEquals(0D, Config.quantumParticleDensity(), 0D);
                Config.particlesEnabled = true;
                Config.synchronizeNetworkConfiguration(configFile);
                assertFalse(Config.particlesEnabled);
                assertEquals(qualities[i], Config.quantumParticleQuality);
                assertEquals(0D, Config.quantumParticleDensity(), 0D);
                command.processCommand(sender, new String[] { "particle" });
                assertFalse(Config.particlesEnabled);
                command.processCommand(sender, new String[] { "particle", "on" });
                Config.particlesEnabled = false;
                Config.synchronizeNetworkConfiguration(configFile);
                assertTrue(Config.particlesEnabled);
                assertEquals(qualities[i], Config.quantumParticleQuality);
                assertEquals(densities[i], Config.quantumParticleDensity(), 0D);
                assertEquals(.625, Config.linkNodeOpacity, 0);
                assertEquals(.0625, Config.linkNodeDepth, 0);
                assertEquals(-.75, Config.linkNodeRelief, 0);
                assertSavedSettings(configFile, qualities[i]);
            }
            for (String[] invalid : new String[][] { { "particle", "invalid" }, { "particle", "off", "extra" },
                { "cover", "opacity", "0.5" } }) {
                try {
                    command.processCommand(sender, invalid);
                    fail("Invalid or removed command was accepted");
                } catch (WrongUsageException expected) {
                    assertTrue(Config.particlesEnabled);
                    assertEquals("high", Config.quantumParticleQuality);
                }
            }
            assertTrue(
                command.addTabCompletionOptions(sender, new String[] { "" })
                    .contains("particle"));
            assertFalse(
                command.addTabCompletionOptions(sender, new String[] { "" })
                    .contains("cover"));
            assertTrue(
                command.addTabCompletionOptions(sender, new String[] { "particle", "" })
                    .contains("off"));
        } finally {
            minecraftHome.set(null, originalHome);
        }
    }

    @Test
    public void qualityPersistsAcrossReloadsWithoutLosingExistingSettings() throws Exception {
        Field minecraftHome = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        Object originalHome = minecraftHome.get(null);
        minecraftHome.set(null, temporaryFolder.getRoot());
        try {
            verifyQualityPersistence();
        } finally {
            minecraftHome.set(null, originalHome);
        }
    }

    private void verifyQualityPersistence() throws Exception {
        File configFile = temporaryFolder.newFile("gtswn_network.cfg");
        Configuration initial = new Configuration(configFile);
        initial.get("hud", "HudXOffset", 123)
            .set(123);
        initial.get("custom", "PreservedSetting", "keep-me")
            .set("keep-me");
        initial.save();

        Config.synchronizeNetworkConfiguration(configFile);
        assertEquals("high", Config.quantumParticleQuality);
        assertEquals(1D, Config.quantumParticleDensity(), 0D);
        assertSavedSettings(configFile, "high");

        String[] qualities = { "low", "medium", "high" };
        double[] densities = { 0D, .5D, 1D };
        for (int i = 0; i < qualities.length; i++) {
            assertTrue(Config.setQuantumParticleQuality(qualities[i]));
            assertEquals(densities[i], Config.quantumParticleDensity(), 0D);
            assertSavedSettings(configFile, qualities[i]);
            // Read through the real startup path, after deliberately changing the in-memory value.
            Config.quantumParticleQuality = "unloaded";
            Config.synchronizeNetworkConfiguration(configFile);
            assertEquals(qualities[i], Config.quantumParticleQuality);
            assertEquals(densities[i], Config.quantumParticleDensity(), 0D);
            assertEquals(123, Config.hudXOffset);
        }
    }

    private static void assertSavedSettings(File configFile, String quality) {
        Configuration saved = new Configuration(configFile);
        assertEquals(
            quality,
            saved.get("client", "QuantumParticleQuality", "missing")
                .getString());
        assertEquals(
            123,
            saved.get("hud", "HudXOffset", -1)
                .getInt());
        assertEquals(
            "keep-me",
            saved.get("custom", "PreservedSetting", "missing")
                .getString());
    }
}
