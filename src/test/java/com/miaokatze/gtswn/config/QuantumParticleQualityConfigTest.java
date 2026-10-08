package com.miaokatze.gtswn.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.command.ICommandSender;
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
    public void coverCommandSettingsRoundTripAndInvalidValuesLeaveStateUnchanged() throws Exception {
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
            initial.save();
            Config.synchronizeNetworkConfiguration(configFile);
            ICommandSender sender = (ICommandSender) Proxy.newProxyInstance(
                ICommandSender.class.getClassLoader(),
                new Class<?>[] { ICommandSender.class },
                (proxy, method, args) -> null);
            CommandGTSWNClient command = new CommandGTSWNClient();
            command.processCommand(sender, new String[] { "cover", "opacity", "0.625" });
            command.processCommand(sender, new String[] { "cover", "depth", "1/16" });
            command.processCommand(sender, new String[] { "cover", "relief", "-0.75" });
            Config.linkNodeOpacity = 1;
            Config.linkNodeDepth = .125;
            Config.linkNodeRelief = 1;
            Config.synchronizeNetworkConfiguration(configFile);
            assertEquals(.625, Config.linkNodeOpacity, 0);
            assertEquals(.0625, Config.linkNodeDepth, 0);
            assertEquals(-.75, Config.linkNodeRelief, 0);
            assertEquals(123, Config.hudXOffset);
            assertSavedSettings(configFile, "high");
            String[] properties = { "depth", "depth", "opacity", "opacity", "opacity", "opacity", "relief", "relief" };
            double[] invalid = { .1, Double.NaN, Double.NaN, Double.POSITIVE_INFINITY, .01, 1.1, -1.01, 1.01 };
            for (int i = 0; i < invalid.length; i++) {
                try {
                    Config.setLinkNodeVisual(properties[i], invalid[i]);
                    fail("Invalid cover value was accepted: " + properties[i] + "=" + invalid[i]);
                } catch (IllegalArgumentException expected) {
                    assertEquals(.625, Config.linkNodeOpacity, 0);
                    assertEquals(.0625, Config.linkNodeDepth, 0);
                    assertEquals(-.75, Config.linkNodeRelief, 0);
                }
            }
            Config.synchronizeNetworkConfiguration(configFile);
            assertEquals(.625, Config.linkNodeOpacity, 0);
            assertEquals(.0625, Config.linkNodeDepth, 0);
            assertEquals(-.75, Config.linkNodeRelief, 0);
            assertSavedSettings(configFile, "high");
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
