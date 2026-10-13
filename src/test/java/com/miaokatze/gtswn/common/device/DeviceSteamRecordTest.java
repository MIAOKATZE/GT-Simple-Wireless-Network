package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class DeviceSteamRecordTest {

    @Test
    public void oldNbtUsesFallbackUntilExactCapabilitiesAreSampled() {
        NBTTagCompound old = new NBTTagCompound();
        old.setByte("energyKind", DeviceSteamAdapter.MIXED);
        old.setByte("powerType", (byte) 1);
        DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
        record.readFromNBT(old);
        assertEquals(6, record.capabilityMask);
        assertTrue(
            record.applySample(
                0,
                (byte) 1,
                100,
                DeviceSteamAdapter.MIXED,
                (byte) 0,
                (byte) 0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "Turbine",
                "",
                "",
                (byte) 14));
        DeviceTerminalDataStore.MachineRecord reloaded = new DeviceTerminalDataStore.MachineRecord();
        reloaded.readFromNBT(record.toNBT());
        assertEquals(14, reloaded.capabilityMask);
        assertFalse(
            record.applySample(
                0,
                (byte) 1,
                100,
                DeviceSteamAdapter.MIXED,
                (byte) 0,
                (byte) 0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "Turbine",
                "",
                "",
                (byte) 14));
        assertTrue(
            record.applySample(
                0,
                (byte) 1,
                100,
                DeviceSteamAdapter.MIXED,
                (byte) 0,
                (byte) 0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "Turbine",
                "",
                "",
                (byte) 6));
    }
}
