package com.miaokatze.gtswn.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.miaokatze.gtswn.common.device.DeviceTerminalSettings;

import cpw.mods.fml.relauncher.FMLInjectionData;

public class DeviceTeleportConfigurationTest {

    @Rule
    public final TemporaryFolder directory = new TemporaryFolder();

    private Field configPath;
    private Field minecraftHome;
    private Object originalHome;
    private Object originalPath;
    private int originalCost;
    private File file;

    @Before
    public void setUp() throws Exception {
        configPath = Config.class.getDeclaredField("mainConfigFile");
        configPath.setAccessible(true);
        originalPath = configPath.get(null);
        originalCost = Config.deviceTeleportXPCost;
        minecraftHome = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        originalHome = minecraftHome.get(null);
        minecraftHome.set(null, directory.getRoot());
        file = directory.newFile("gtswn.cfg");
        Configuration initial = new Configuration(file);
        initial.get(Configuration.CATEGORY_GENERAL, "deviceTeleportXPCost", 7)
            .set(7);
        initial.get("unrelated", "preserved", 42)
            .set(42);
        initial.save();
        Config.synchronizeConfiguration(file);
    }

    @After
    public void restore() throws Exception {
        configPath.set(null, originalPath);
        Config.deviceTeleportXPCost = originalCost;
        if (minecraftHome != null) minecraftHome.set(null, originalHome);
    }

    @Test
    public void settingPersistsAndReloadsWithoutChangingOtherKeys() {
        assertTrue(Config.saveDeviceTeleportCost(1234));
        Configuration disk = new Configuration(file);
        assertEquals(
            1234,
            disk.get(Configuration.CATEGORY_GENERAL, "deviceTeleportXPCost", 0)
                .getInt());
        assertEquals(
            42,
            disk.get("unrelated", "preserved", 0)
                .getInt());
        Config.deviceTeleportXPCost = 0;
        Config.synchronizeConfiguration(file);
        assertEquals(1234, Config.deviceTeleportXPCost);
    }

    @Test
    public void legacyWorldCostCannotOverrideHostConfiguration() {
        DeviceTerminalSettings settings = new DeviceTerminalSettings("test");
        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setInteger("teleportCost", 999);
        settings.readFromNBT(legacy);
        assertEquals(7, settings.teleportCost());
        Config.deviceTeleportXPCost = 11;
        assertEquals(11, settings.teleportCost());
        NBTTagCompound written = new NBTTagCompound();
        settings.writeToNBT(written);
        assertFalse(written.hasKey("teleportCost"));
    }

    @Test
    public void invalidCostsDoNotChangeMemoryOrDisk() {
        assertFalse(Config.saveDeviceTeleportCost(-1));
        assertFalse(Config.saveDeviceTeleportCost(1000001));
        assertEquals(7, Config.deviceTeleportXPCost);
        assertEquals(
            7,
            new Configuration(file).get(Configuration.CATEGORY_GENERAL, "deviceTeleportXPCost", 0)
                .getInt());
    }
}
