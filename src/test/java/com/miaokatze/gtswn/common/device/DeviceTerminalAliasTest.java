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
        record.tags = "Power, Main";
        record.favoriteOrder = 42;
        NBTTagCompound tag = record.toNBT();
        DeviceTerminalDataStore.MachineRecord copy = new DeviceTerminalDataStore.MachineRecord();
        copy.readFromNBT(tag);
        assertEquals("Alias", copy.displayName);
        assertEquals((byte) 4, copy.nameColor);
        assertTrue(copy.nameBold);
        assertEquals("Native", copy.name);
        assertEquals("Power, Main", copy.tags);
        assertEquals(42, copy.favoriteOrder);
        tag.removeTag("displayName");
        tag.removeTag("nameColor");
        tag.removeTag("nameBold");
        tag.removeTag("tags");
        tag.removeTag("favoriteOrder");
        copy.readFromNBT(tag);
        assertEquals("", copy.displayName);
        assertEquals((byte) 15, copy.nameColor);
        assertFalse(copy.nameBold);
        assertEquals("", copy.tags);
        assertEquals(0, copy.favoriteOrder);
        tag.setString("displayName", "\u00a7cInvalid");
        tag.setByte("nameColor", (byte) 16);
        tag.setBoolean("nameBold", true);
        copy.readFromNBT(tag);
        assertEquals("", copy.displayName);
        assertEquals((byte) 15, copy.nameColor);
        assertFalse(copy.nameBold);
    }

    @Test
    public void tagsAndFavoritesKeepTerminalOwnershipAndNewFavoritesRankFirst() {
        DeviceTerminalDataStore store = new DeviceTerminalDataStore("test");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        String a = "0:1:64:2", b = "0:2:64:2";
        store.addBinding(first, a, "Native", 0, 1, 64, 2, (byte) 0);
        store.addBinding(first, b, "Other", 0, 2, 64, 2, (byte) 0);
        store.addBinding(second, a, "Native", 0, 1, 64, 2, (byte) 0);
        assertTrue(store.editBinding(first, a, "Alias", 12, true, " Main, Power, main "));
        assertEquals("Main, Power", store.getTerminal(first).records.get(a).tags);
        assertEquals("", store.getTerminal(second).records.get(a).tags);
        assertTrue(store.setFavorite(first, a, true));
        assertTrue(store.setFavorite(first, b, true));
        assertTrue(
            store.getTerminal(first).records.get(b).favoriteOrder
                > store.getTerminal(first).records.get(a).favoriteOrder);
        long version = store.getTerminal(first).version;
        assertTrue(store.setFavorite(first, b, true));
        assertEquals(version, store.getTerminal(first).version);
        assertTrue(store.setFavorite(first, a, false));
        assertEquals(0, store.getTerminal(first).records.get(a).favoriteOrder);
        assertTrue(store.setFavorite(first, a, true));
        assertTrue(
            store.getTerminal(first).records.get(a).favoriteOrder
                > store.getTerminal(first).records.get(b).favoriteOrder);
        assertEquals(0, store.getTerminal(second).records.get(a).favoriteOrder);
        store.renameBinding(first, a, "Renamed", 1, false);
        assertEquals("Main, Power", store.getTerminal(first).records.get(a).tags);
        NBTTagCompound saved = new NBTTagCompound();
        store.getTerminal(first)
            .writeToNBT(saved);
        DeviceTerminalDataStore.TerminalData restored = new DeviceTerminalDataStore.TerminalData();
        restored.readFromNBT(saved);
        assertEquals(store.getTerminal(first).records.get(a).favoriteOrder, restored.records.get(a).favoriteOrder);
        assertEquals("Main, Power", restored.records.get(a).tags);
    }
}
