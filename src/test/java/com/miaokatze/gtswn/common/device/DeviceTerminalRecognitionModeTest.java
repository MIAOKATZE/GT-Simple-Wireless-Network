package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class DeviceTerminalRecognitionModeTest {

    @Test
    public void oppositePrimaryAndIgnoreCycleWorksForBothDefaults() {
        assertEquals(2, DeviceTerminalRecognitionMode.effectiveCapabilities((byte) 1, (byte) 8, (byte) 1));
        assertEquals(4, DeviceTerminalRecognitionMode.effectiveCapabilities((byte) 1, (byte) 1, (byte) 0));
        assertEquals(12, DeviceTerminalRecognitionMode.effectiveCapabilities((byte) 1, (byte) 14, (byte) 1));
        assertEquals(0, DeviceTerminalRecognitionMode.effectiveCapabilities((byte) 2, (byte) 14, (byte) 1));
        assertEquals(1, DeviceTerminalRecognitionMode.next((byte) 0));
        assertEquals(2, DeviceTerminalRecognitionMode.next((byte) 1));
        assertEquals(0, DeviceTerminalRecognitionMode.next((byte) 2));
    }

    @Test
    public void terminalOverrideSurvivesUnbindWithoutImplicitBindingAndClearsOnReplacement() {
        DeviceTerminalDataStore store = new DeviceTerminalDataStore("test");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        String key = "0:1:64:2";
        assertEquals(1, store.cycleRecognition(a, key, 123, (byte) 1));
        assertTrue(store.getTerminal(a).boundKeys.isEmpty());
        assertEquals(2, store.cycleRecognition(a, key, 123, (byte) 1));
        assertEquals(0, store.recognitionMode(b, key, 123));
        store.addBinding(a, key, "Native", 0, 1, 64, 2, (byte) 0, 123, (byte) 0, (byte) 0, (byte) 1);
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
    public void recognitionChangesEraseAllHistoryAndRestartWarmup() {
        DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
        record.instIn = record.instOut = record.steamInstIn = record.steamInstOut = 99;
        record.inAvg = record.outAvg = record.steamAvgIn = record.steamAvgOut = 99;
        record.fifoIn[0] = record.fifoOut[0] = record.fifoSteamIn[0] = record.fifoSteamOut[0] = 99;
        record.idx = record.count = 1;
        record.resetRecognition((byte) 2, (byte) 14);
        assertEquals(0, record.instIn + record.instOut + record.steamInstIn + record.steamInstOut);
        assertEquals(0, record.inAvg + record.outAvg + record.steamAvgIn + record.steamAvgOut, 0);
        assertEquals(0, record.fifoIn[0] + record.fifoOut[0] + record.fifoSteamIn[0] + record.fifoSteamOut[0], 0);
        assertEquals(0, record.count);
        assertEquals(0, record.evidenceKind);
        assertFalse(record.recognitionWarmup);
        record.resetRecognition((byte) 0, (byte) 14);
        assertTrue(record.recognitionWarmup);
        assertEquals(2, record.evidenceKind);
    }

    @Test
    public void overrideCapacityDoesNotEvictExistingIgnoredChoices() {
        DeviceTerminalDataStore store = new DeviceTerminalDataStore("test");
        UUID terminal = UUID.randomUUID();
        for (int i = 0; i < DeviceTerminalRecognitionMode.MAX_OVERRIDES; i++)
            assertEquals(1, store.cycleRecognition(terminal, "0:" + i + ":64:0", 123, (byte) 1));
        assertEquals(-1, store.cycleRecognition(terminal, "0:2000:64:0", 123, (byte) 1));
        assertEquals(2, store.cycleRecognition(terminal, "0:0:64:0", 123, (byte) 1));
        assertEquals(
            DeviceTerminalRecognitionMode.MAX_OVERRIDES,
            store.getTerminal(terminal).recognitionOverrides.size());
    }

    @Test
    public void forcedRecipeChannelReplacesFallbackWithRealDirectionAndRetainsItAtIdle() {
        DeviceTerminalDataStore.MachineRecord first = new DeviceTerminalDataStore.MachineRecord();
        DeviceTerminalDataStore.MachineRecord second = new DeviceTerminalDataStore.MachineRecord();
        first.resetRecognition(DeviceTerminalRecognitionMode.OTHER, (byte) 1);
        second.resetRecognition(DeviceTerminalRecognitionMode.AUTO, (byte) 1);
        assertEquals(4, first.capabilityMask);
        assertEquals(
            8,
            first.learnOverrideDirections((byte) 1, (byte) 4, 100, 0, 0, 200, new double[] { 100, 0, 0, 200 }));
        assertEquals(8, first.learnOverrideDirections((byte) 1, (byte) 4, 0, 0, 0, 0, new double[4]));
        DeviceTerminalDataStore.MachineRecord loaded = new DeviceTerminalDataStore.MachineRecord();
        loaded.readFromNBT(first.toNBT());
        assertEquals(8, loaded.learnOverrideDirections((byte) 1, (byte) 4, 0, 0, 0, 0, new double[4]));
        assertEquals(12, first.learnOverrideDirections((byte) 1, (byte) 4, 0, 0, 50, 0, new double[] { 0, 0, 50, 0 }));
        assertEquals(1, second.capabilityMask);
        assertEquals(0, second.observedOverrideDirections);
        first.resetRecognition(DeviceTerminalRecognitionMode.AUTO, (byte) 1);
        assertEquals(1, first.capabilityMask);
        assertEquals(0, first.observedOverrideDirections);
    }

    @Test
    public void forcedChannelRetainsNativeRolesAndNeverLearnsTheOtherEnergy() {
        DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
        record.resetRecognition(DeviceTerminalRecognitionMode.OTHER, (byte) 6);
        assertEquals(
            4,
            record.learnOverrideDirections((byte) 6, (byte) 4, 10, 20, 0, 0, new double[] { 10, 20, 0, 0 }));
        assertEquals(0, record.observedOverrideDirections);
        assertEquals(12, record.learnOverrideDirections((byte) 6, (byte) 4, 0, 0, 0, 10, new double[] { 0, 0, 0, 10 }));
        record.resetRecognition(DeviceTerminalRecognitionMode.OTHER, (byte) 8);
        assertEquals(
            1,
            record.learnOverrideDirections((byte) 8, (byte) 2, 10, 0, 0, 200, new double[] { 10, 0, 0, 200 }));
        assertEquals(1, record.learnOverrideDirections((byte) 8, (byte) 2, 0, 0, 0, 0, new double[4]));
    }
}
