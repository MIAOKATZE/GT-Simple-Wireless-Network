package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

public class DevicePassiveDefaultStoreTest {

    private static class NativeTransformer extends gregtech.api.metatileentity.implementations.MTETransformer {

        private NativeTransformer() {
            super("test", 1, new String[0], null);
        }

        @Override
        public String getLocalName() {
            return "Native Transfer";
        }
    }

    private static IMetaTileEntity transformer() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return (IMetaTileEntity) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), NativeTransformer.class);
    }

    @Test
    public void defaultIgnoreDoesNotConsumeCapacityAndExplicitAutoSurvivesReload() throws Exception {
        int meta = GregTechAPI.METATILEENTITIES.length - 1;
        IMetaTileEntity original = GregTechAPI.METATILEENTITIES[meta];
        IMetaTileEntity machine = transformer();
        try {
            GregTechAPI.METATILEENTITIES[meta] = machine;
            DeviceTerminalDataStore store = new DeviceTerminalDataStore("test");
            UUID a = UUID.randomUUID(), b = UUID.randomUUID();
            String key = "0:1:64:2";
            assertTrue(DeviceMachineTypes.isWorkingMachine(machine));
            store.addBinding(a, key, "Transfer", 0, 1, 64, 2, (byte) 0, meta, (byte) 0, (byte) 0, (byte) 3);
            store.addBinding(b, key, "Transfer", 0, 1, 64, 2, (byte) 0, meta, (byte) 0, (byte) 0, (byte) 3);
            assertEquals(2, store.recognitionMode(a, key, meta));
            assertEquals(3, store.getTerminal(a).records.get(key).state);
            assertTrue(store.getTerminal(a).recognitionOverrides.isEmpty());
            assertEquals(0, store.cycleRecognition(a, key, meta, (byte) 3, machine));
            assertEquals(2, store.recognitionMode(b, key, meta));
            assertEquals(0, store.getTerminal(a).recognitionOverrides.get(key).mode);
            NBTTagCompound saved = new NBTTagCompound();
            store.writeToNBT(saved);
            DeviceTerminalDataStore loaded = new DeviceTerminalDataStore("test");
            loaded.readFromNBT(saved);
            assertEquals(0, loaded.recognitionMode(a, key, meta));
            assertEquals(2, loaded.recognitionMode(b, key, meta));
            assertEquals(1, loaded.cycleRecognition(a, key, meta, (byte) 3, machine));
            assertEquals(2, loaded.cycleRecognition(a, key, meta, (byte) 3, machine));
            assertTrue(loaded.getTerminal(a).recognitionOverrides.isEmpty());
        } finally {
            GregTechAPI.METATILEENTITIES[meta] = original;
        }
    }

    @Test
    public void legacyStorageMigrationAndPassiveIdentityChangeClearOnceWithoutAWatch() throws Exception {
        IMetaTileEntity machine = transformer();
        DeviceTerminalDataStore store = new DeviceTerminalDataStore("test");
        UUID terminal = UUID.randomUUID();
        String key = "0:1:64:2";
        DeviceTerminalDataStore.TerminalData data = store.getOrCreateTerminal(terminal);
        data.boundKeys.add(key);
        DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
        record.metaTileId = 123;
        record.state = 1;
        record.instIn = record.instOut = 999;
        record.inAvg = record.outAvg = 999;
        record.fifoIn[0] = 999;
        record.count = 1;
        record.linkNodeType = 3;
        record.name = "Old";
        data.records.put(key, record);
        assertEquals(2, store.recognitionMode(terminal, key, 123, machine));
        assertEquals(0, record.instIn + record.instOut);
        assertEquals(0, record.inAvg + record.outAvg, 0);
        assertEquals(0, record.count);
        long version = data.version;
        assertEquals(2, store.recognitionMode(terminal, key, 123, machine));
        assertEquals(version, data.version);
        assertEquals(2, store.recognitionMode(terminal, key, 456, machine));
        assertEquals(456, record.metaTileId);
        assertEquals("Native Transfer", record.name);
        assertEquals(0, record.linkNodeType);
        version = data.version;
        assertEquals(2, store.recognitionMode(terminal, key, 456, machine));
        assertEquals(version, data.version);
        assertTrue(data.recognitionOverrides.isEmpty());
    }

    @Test
    public void registryIdentitySurvivesPersistenceAndLegacyEntriesStayUnknown() {
        DeviceRegistryData registry = new DeviceRegistryData("test");
        String key = "0:1:64:2";
        registry.register(key, UUID.randomUUID(), "Transfer", 456);
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        DeviceRegistryData loaded = new DeviceRegistryData("test");
        loaded.readFromNBT(saved);
        assertEquals(456, loaded.getEntry(key).metaTileId);
        saved.getTagList("devices", 10)
            .getCompoundTagAt(0)
            .removeTag("metaTileId");
        loaded.readFromNBT(saved);
        assertEquals(-1, loaded.getEntry(key).metaTileId);
    }
}
