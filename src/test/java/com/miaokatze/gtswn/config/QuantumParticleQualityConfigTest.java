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

import com.miaokatze.gtswn.client.QuantumVoxelParticleHandler;
import com.miaokatze.gtswn.common.command.CommandGTSWNClient;

import cpw.mods.fml.relauncher.FMLInjectionData;

public class QuantumParticleQualityConfigTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final Map<Field, Object> originalConfig = new HashMap<>();
    private Field minecraftHome;
    private Object originalHome;
    private final CommandGTSWNClient command = new CommandGTSWNClient();
    private final ICommandSender sender = (ICommandSender) Proxy.newProxyInstance(
        ICommandSender.class.getClassLoader(),
        new Class<?>[] { ICommandSender.class },
        (proxy, method, args) -> null);

    @Before
    public void rememberGlobalConfiguration() throws Exception {
        for (Field field : Config.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true);
                originalConfig.put(field, field.get(null));
            }
        }
        minecraftHome = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        originalHome = minecraftHome.get(null);
        minecraftHome.set(null, temporaryFolder.getRoot());
    }

    @After
    public void restoreGlobalConfiguration() throws Exception {
        for (Map.Entry<Field, Object> entry : originalConfig.entrySet()) {
            entry.getKey()
                .set(null, entry.getValue());
        }
        minecraftHome.set(null, originalHome);
    }

    @Test
    public void independentSettingsPersistAndPreserveOtherCategories() throws Exception {
        File file = temporaryFolder.newFile("independent.cfg");
        Configuration initial = new Configuration(file);
        initial.get("custom", "PreservedSetting", "keep-me")
            .set("keep-me");
        initial.get("hud", "HudXOffset", 123)
            .set(123);
        initial.get("client", "LinkNodeOpacity", .625D)
            .set(.625D);
        initial.save();
        Config.synchronizeNetworkConfiguration(file);
        assertEquals(100, Config.tapParticleDensityPercent);
        assertEquals(100, Config.quantumParticleSizePercent);
        command.processCommand(sender, new String[] { "TaP", "OFF" });
        command.processCommand(sender, new String[] { "tap", "density", "175" });
        command.processCommand(sender, new String[] { "tap", "size", "250" });
        command.processCommand(sender, new String[] { "tap", "distance", "128" });
        command.processCommand(sender, new String[] { "tap", "material", "OLDPLUS" });
        command.processCommand(sender, new String[] { "QUANTUM", "density", "200" });
        command.processCommand(sender, new String[] { "quantum", "size", "40" });
        command.processCommand(sender, new String[] { "quantum", "distance", "7" });
        Config.synchronizeNetworkConfiguration(file);
        assertFalse(Config.tapParticlesEnabled);
        assertTrue(Config.quantumParticlesEnabled);
        assertEquals(175, Config.tapParticleDensityPercent);
        assertEquals(200, Config.quantumParticleDensityPercent);
        assertEquals(250, Config.tapParticleSizePercent);
        assertEquals(40, Config.quantumParticleSizePercent);
        assertEquals(128, Config.tapRenderDistance);
        assertEquals(7, Config.quantumRenderDistance);
        assertEquals("oldplus", Config.tapMaterial);
        assertEquals(0D, Config.tapParticleDensity(), 0D);
        assertEquals(2D, Config.quantumParticleDensity(), 0D);
        assertEquals(123, Config.hudXOffset);
        assertEquals(.625D, Config.linkNodeOpacity, 0D);
        assertEquals(
            "keep-me",
            new Configuration(file).get("custom", "PreservedSetting", "missing")
                .getString());
    }

    @Test
    public void legacyMigrationOnlySuppliesMissingNewOptions() throws Exception {
        String[] quality = { "low", "medium", "high" };
        int[] density = { 0, 50, 100 };
        for (int i = 0; i < quality.length; i++) {
            File file = temporaryFolder.newFile("legacy-" + i + ".cfg");
            Configuration legacy = new Configuration(file);
            legacy.get("client", "QuantumParticleQuality", quality[i])
                .set(quality[i]);
            legacy.get("client", "ParticlesEnabled", false)
                .set(false);
            legacy.save();
            Config.synchronizeNetworkConfiguration(file);
            assertEquals(density[i], Config.tapParticleDensityPercent);
            assertEquals(density[i], Config.quantumParticleDensityPercent);
            assertEquals(quality[i], Config.tapMaterial);
            assertFalse(Config.tapParticlesEnabled);
            assertFalse(Config.quantumParticlesEnabled);
            command.processCommand(sender, new String[] { "tap", "on" });
            command.processCommand(sender, new String[] { "tap", "density", "160" });
            command.processCommand(sender, new String[] { "tap", "material", "oldplus" });
            command.processCommand(sender, new String[] { "quantum", "on" });
            command.processCommand(sender, new String[] { "quantum", "density", "180" });
            Configuration modifiedLegacy = new Configuration(file);
            modifiedLegacy.get("client", "QuantumParticleQuality", "high")
                .set("low");
            modifiedLegacy.get("client", "ParticlesEnabled", true)
                .set(false);
            modifiedLegacy.save();
            Config.synchronizeNetworkConfiguration(file);
            assertTrue(Config.tapParticlesEnabled);
            assertTrue(Config.quantumParticlesEnabled);
            assertEquals(160, Config.tapParticleDensityPercent);
            assertEquals(180, Config.quantumParticleDensityPercent);
            assertEquals("oldplus", Config.tapMaterial);
        }
    }

    @Test
    public void outOfRangeFileValuesAreNormalizedAndSaved() throws Exception {
        File file = temporaryFolder.newFile("clamped.cfg");
        Configuration initial = new Configuration(file);
        initial.get("client", "TapParticleDensityPercent", 100)
            .set(-1);
        initial.get("client", "QuantumParticleDensityPercent", 100)
            .set(201);
        initial.get("client", "TapParticleSizePercent", 100)
            .set(9);
        initial.get("client", "QuantumParticleSizePercent", 100)
            .set(401);
        initial.get("client", "TapRenderDistance", 64)
            .set(0);
        initial.get("client", "QuantumRenderDistance", 64)
            .set(257);
        initial.get("client", "TapMaterial", "high")
            .set("unknown");
        initial.save();
        Config.synchronizeNetworkConfiguration(file);
        Configuration saved = new Configuration(file);
        assertEquals(
            0,
            saved.get("client", "TapParticleDensityPercent", -99)
                .getInt());
        assertEquals(
            200,
            saved.get("client", "QuantumParticleDensityPercent", -99)
                .getInt());
        assertEquals(
            10,
            saved.get("client", "TapParticleSizePercent", -99)
                .getInt());
        assertEquals(
            400,
            saved.get("client", "QuantumParticleSizePercent", -99)
                .getInt());
        assertEquals(
            1,
            saved.get("client", "TapRenderDistance", -99)
                .getInt());
        assertEquals(
            256,
            saved.get("client", "QuantumRenderDistance", -99)
                .getInt());
        assertEquals(
            "high",
            saved.get("client", "TapMaterial", "missing")
                .getString());
    }

    @Test
    public void invalidInputsNeverMutateAndResetIsGroupSpecific() throws Exception {
        File file = temporaryFolder.newFile("commands.cfg");
        Config.synchronizeNetworkConfiguration(file);
        String[][] invalid = { { "tap", "density", "201" }, { "quantum", "density", "-1" }, { "tap", "size", "9" },
            { "quantum", "size", "401" }, { "tap", "distance", "0" }, { "quantum", "distance", "257" },
            { "tap", "density", "NaN" }, { "tap", "size", "100.0" }, { "tap", "on", "extra" },
            { "tap", "reset", "extra" }, { "quantum", "material", "high" }, { "tap", "material", "unknown" },
            { "tap", "material", "oldultra" }, { "tap", "density", "50", "extra" }, { "tap", "status", "extra" },
            { "quality", "low" }, { "particle", "off" }, { "reset", "extra" } };
        for (String[] args : invalid) {
            try {
                command.processCommand(sender, args);
                fail("Invalid command accepted");
            } catch (WrongUsageException expected) {
                assertTrue(Config.tapParticlesEnabled);
                assertTrue(Config.quantumParticlesEnabled);
                assertEquals(100, Config.tapParticleDensityPercent);
                assertEquals(100, Config.quantumParticleDensityPercent);
                assertEquals(100, Config.tapParticleSizePercent);
                assertEquals(64, Config.quantumRenderDistance);
                assertEquals("high", Config.tapMaterial);
            }
        }
        command.processCommand(sender, new String[] {});
        command.processCommand(sender, new String[] { "tap" });
        command.processCommand(sender, new String[] { "help" });
        command.processCommand(sender, new String[] { "status" });
        command.processCommand(sender, new String[] { "tap", "density", "0" });
        command.processCommand(sender, new String[] { "tap", "size", "400" });
        command.processCommand(sender, new String[] { "quantum", "density", "25" });
        command.processCommand(sender, new String[] { "tap", "reset" });
        assertEquals(100, Config.tapParticleDensityPercent);
        assertEquals(100, Config.tapParticleSizePercent);
        assertEquals(25, Config.quantumParticleDensityPercent);
        command.processCommand(sender, new String[] { "RESET" });
        Config.synchronizeNetworkConfiguration(file);
        assertEquals(100, Config.quantumParticleDensityPercent);
        assertEquals(64, Config.quantumRenderDistance);
        assertEquals(100, Config.quantumParticleSizePercent);
    }

    @Test
    public void completionIncludesOnlyValidNextArguments() {
        assertTrue(
            command.addTabCompletionOptions(sender, new String[] { "" })
                .contains("tap"));
        assertFalse(
            command.addTabCompletionOptions(sender, new String[] { "" })
                .contains("quality"));
        assertFalse(
            command.addTabCompletionOptions(sender, new String[] { "" })
                .contains("particle"));
        assertTrue(
            command.addTabCompletionOptions(sender, new String[] { "TAP", "" })
                .contains("material"));
        assertFalse(
            command.addTabCompletionOptions(sender, new String[] { "quantum", "" })
                .contains("material"));
        assertTrue(
            command.addTabCompletionOptions(sender, new String[] { "tap", "MATERIAL", "" })
                .contains("oldplus"));
        assertFalse(
            command.addTabCompletionOptions(sender, new String[] { "tap", "material", "" })
                .contains("oldultra"));
        assertTrue(
            command.addTabCompletionOptions(sender, new String[] { "quantum", "density", "" })
                .contains("200"));
        assertTrue(
            command.addTabCompletionOptions(sender, new String[] { "tap", "size", "" })
                .contains("400"));
        assertTrue(
            command.addTabCompletionOptions(sender, new String[] { "tap", "distance", "" })
                .contains("256"));
        assertTrue(
            command.addTabCompletionOptions(sender, new String[] { "tap", "on", "" })
                .isEmpty());
        assertTrue(
            command.addTabCompletionOptions(sender, new String[] { "quantum", "material", "" })
                .isEmpty());
        assertTrue(
            command.addTabCompletionOptions(sender, new String[] { "tap", "density", "100", "" })
                .isEmpty());
    }

    @Test
    public void quantumMathHonorsPercentagesAndDistanceBoundary() {
        assertEquals(.15D, QuantumVoxelParticleHandler.particleSizeMultiplier(10), 0D);
        assertEquals(1.5D, QuantumVoxelParticleHandler.particleSizeMultiplier(100), 0D);
        assertEquals(6D, QuantumVoxelParticleHandler.particleSizeMultiplier(400), 0D);
        assertTrue(QuantumVoxelParticleHandler.withinRenderDistance(3, 4, 0, 0, 0, 0, 5));
        assertFalse(QuantumVoxelParticleHandler.withinRenderDistance(3, 4, .01D, 0, 0, 0, 5));
        assertEquals(0, QuantumVoxelParticleHandler.emissionCount(.12D, .2D));
        assertEquals(1, QuantumVoxelParticleHandler.emissionCount(.24D, .2D));
        assertEquals(2, QuantumVoxelParticleHandler.emissionCount(2D, .5D));
        assertEquals(2, QuantumVoxelParticleHandler.emissionCount(1.5D, .25D));
        assertEquals(1, QuantumVoxelParticleHandler.emissionCount(1.5D, .75D));
    }
}
