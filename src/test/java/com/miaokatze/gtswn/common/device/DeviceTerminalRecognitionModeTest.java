package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

public class DeviceTerminalRecognitionModeTest {

    @Test
    public void trackingSwitchAndLegacyMigrationPreserveIgnoredChoices() {
        assertEquals(2, DeviceTerminalRecognitionMode.next((byte) 0));
        assertEquals(0, DeviceTerminalRecognitionMode.next((byte) 2));
        assertEquals(0, DeviceTerminalRecognitionMode.migrate((byte) 0));
        assertEquals(0, DeviceTerminalRecognitionMode.migrate((byte) 1));
        assertEquals(2, DeviceTerminalRecognitionMode.migrate((byte) 2));
        assertEquals(0, DeviceTerminalRecognitionMode.migrate((byte) 99));
        assertEquals(2, DeviceTerminalRecognitionMode.effectiveCapabilities((byte) 0, (byte) 14, (byte) 1));
        assertEquals(0, DeviceTerminalRecognitionMode.effectiveCapabilities((byte) 2, (byte) 14, (byte) 1));
    }

    @Test
    public void terminalOverrideSurvivesUnbindWithoutImplicitBindingAndClearsOnReplacement() {
        DeviceTerminalDataStore store = new DeviceTerminalDataStore("test");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        String key = "0:1:64:2";
        assertEquals(2, store.cycleRecognition(a, key, 123, (byte) 1));
        assertTrue(store.getTerminal(a).boundKeys.isEmpty());
        assertEquals(0, store.recognitionMode(b, key, 123));
        store.addBinding(a, key, "Native", 0, 1, 64, 2, (byte) 0, 123, (byte) 0, (byte) 1);
        DeviceTerminalDataStore.MachineRecord ignored = store.getTerminal(a).records.get(key);
        assertEquals(3, ignored.state);
        assertEquals(0, ignored.capabilityMask);
        assertEquals(0, ignored.count);
        store.removeBinding(a, key);
        NBTTagCompound save = new NBTTagCompound();
        store.writeToNBT(save);
        DeviceTerminalDataStore loaded = new DeviceTerminalDataStore("test");
        loaded.readFromNBT(save);
        assertEquals(2, loaded.recognitionMode(a, key, 123));
        assertTrue(loaded.getTerminal(a).boundKeys.isEmpty());
        assertEquals(0, loaded.recognitionMode(a, key, 456));
        assertTrue(loaded.getTerminal(a).recognitionOverrides.isEmpty());
    }

    @Test
    public void ignoredNbtCannotReloadResidualNumbersCapabilitiesRecipesOrHistory() {
        DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
        record.instIn = record.instOut = 99;
        record.inAvg = record.outAvg = 99;
        record.fifoIn[0] = record.fifoOut[0] = 99;
        record.idx = record.count = 1;
        record.recipeIn = "recipe";
        record.recipeOut = "output";
        record.capabilityMask = 15;
        record.recognitionMode = 2;
        NBTTagCompound saved = record.toNBT();
        saved.setLong("steamInstIn", 99);
        saved.setDouble("steamAvgOut", 99);
        record.readFromNBT(saved);
        assertEquals(3, record.state);
        assertEquals(0, record.instIn + record.instOut);
        assertEquals(0, record.inAvg + record.outAvg, 0);
        assertEquals(0, record.fifoIn[0] + record.fifoOut[0], 0);
        assertEquals(0, record.count);
        assertEquals(0, record.capabilityMask);
        assertEquals(0, record.evidenceKind);
        assertEquals("", record.recipeIn);
        assertEquals("", record.recipeOut);
        assertFalse(record.recognitionWarmup);
        assertFalse(
            record.toNBT()
                .hasKey("steamInstIn"));
        assertFalse(
            record.toNBT()
                .hasKey("steamAvgOut"));
        record.resetRecognition((byte) 0, (byte) 1);
        assertTrue(record.recognitionWarmup);
        assertEquals(2, record.evidenceKind);
    }

    @Test
    public void legacyManualChoiceBecomesTrackingAndDropsConvertedElectricHistory() {
        DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
        record.recognitionMode = 1;
        record.capabilityMask = 14;
        record.instIn = 123;
        record.inAvg = 456;
        NBTTagCompound saved = record.toNBT();
        saved.setByte("energyKind", (byte) 2);
        record.readFromNBT(saved);
        assertEquals(0, record.recognitionMode);
        assertEquals(0, record.instIn);
        assertEquals(0, record.inAvg, 0);
        assertEquals(2, record.capabilityMask);
        assertTrue(record.recognitionWarmup);
        assertFalse(
            record.toNBT()
                .hasKey("energyKind"));
    }

    @Test
    public void legacyOverridesRetainIgnoredAndManualTrackingAcrossPersistence() {
        DeviceTerminalDataStore.TerminalData data = new DeviceTerminalDataStore.TerminalData();
        NBTTagCompound saved = new NBTTagCompound();
        NBTTagList overrides = new NBTTagList();
        for (int mode = 0; mode <= 2; mode++) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("key", "0:" + mode + ":64:0");
            entry.setInteger("metaTileId", 123);
            entry.setByte("mode", (byte) mode);
            overrides.appendTag(entry);
        }
        saved.setTag("recognitionOverrides", overrides);
        data.readFromNBT(saved);
        assertEquals(0, data.recognitionOverrides.get("0:0:64:0").mode);
        assertEquals(0, data.recognitionOverrides.get("0:1:64:0").mode);
        assertEquals(2, data.recognitionOverrides.get("0:2:64:0").mode);
        data.writeToNBT(saved);
        data.readFromNBT(saved);
        assertEquals(2, data.recognitionOverrides.get("0:2:64:0").mode);
    }

    @Test
    public void overrideCapacityDoesNotEvictExistingIgnoredChoices() {
        DeviceTerminalDataStore store = new DeviceTerminalDataStore("test");
        UUID terminal = UUID.randomUUID();
        for (int i = 0; i < DeviceTerminalRecognitionMode.MAX_OVERRIDES; i++)
            assertEquals(2, store.cycleRecognition(terminal, "0:" + i + ":64:0", 123, (byte) 1));
        assertEquals(-1, store.cycleRecognition(terminal, "0:2000:64:0", 123, (byte) 1));
        assertEquals(0, store.cycleRecognition(terminal, "0:0:64:0", 123, (byte) 1));
        assertEquals(
            DeviceTerminalRecognitionMode.MAX_OVERRIDES - 1,
            store.getTerminal(terminal).recognitionOverrides.size());
    }
}
