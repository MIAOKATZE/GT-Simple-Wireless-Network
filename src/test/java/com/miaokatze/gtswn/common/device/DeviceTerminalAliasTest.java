package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class DeviceTerminalAliasTest {

    @Test
    public void aliasIsPerTerminalAndNoOpDoesNotChangeVersion() {
        DeviceTerminalDataStore store = new DeviceTerminalDataStore("test");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        String key = "0:1:64:2";
        store.addBinding(first, key, "Native", 0, 1, 64, 2, (byte) 0);
        store.addBinding(second, key, "Native", 0, 1, 64, 2, (byte) 0);
        assertTrue(store.renameBinding(first, key, " Alias ", 12, true));
        DeviceTerminalDataStore.TerminalData data = store.getTerminal(first);
        long version = data.version;
        assertEquals("Alias", data.records.get(key).displayName);
        assertEquals("Native", data.records.get(key).name);
        assertEquals("", store.getTerminal(second).records.get(key).displayName);
        assertTrue(store.renameBinding(first, key, "Alias", 12, true));
        assertEquals(version, data.version);
        assertFalse(store.renameBinding(first, key, "bad\n", 12, true));
        assertEquals(version, data.version);
        assertFalse(store.renameBinding(first, "0:99:64:99", "Other", 1, false));
        assertEquals(version, data.version);
        assertTrue(store.renameBinding(first, key, "", 3, true));
        assertEquals("", data.records.get(key).displayName);
        assertEquals((byte) 15, data.records.get(key).nameColor);
        assertFalse(data.records.get(key).nameBold);
    }

    @Test
    public void persistenceKeepsAliasAndLegacyDefaultsSafely() {
        DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
        record.name = "Native";
        record.displayName = "Alias";
        record.nameColor = 4;
        record.nameBold = true;
        NBTTagCompound tag = record.toNBT();
        DeviceTerminalDataStore.MachineRecord copy = new DeviceTerminalDataStore.MachineRecord();
        copy.readFromNBT(tag);
        assertEquals("Alias", copy.displayName);
        assertEquals((byte) 4, copy.nameColor);
        assertTrue(copy.nameBold);
        assertEquals("Native", copy.name);
        tag.removeTag("displayName");
        tag.removeTag("nameColor");
        tag.removeTag("nameBold");
        copy.readFromNBT(tag);
        assertEquals("", copy.displayName);
        assertEquals((byte) 15, copy.nameColor);
        assertFalse(copy.nameBold);
        tag.setString("displayName", "\u00a7cInvalid");
        tag.setByte("nameColor", (byte) 16);
        tag.setBoolean("nameBold", true);
        copy.readFromNBT(tag);
        assertEquals("", copy.displayName);
        assertEquals((byte) 15, copy.nameColor);
        assertFalse(copy.nameBold);
    }
}
