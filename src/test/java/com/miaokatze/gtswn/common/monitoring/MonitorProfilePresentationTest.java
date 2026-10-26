package com.miaokatze.gtswn.common.monitoring;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;

public class MonitorProfilePresentationTest {

    private MonitoringProfiles store() {
        MonitoringProfiles data = new MonitoringProfiles("test");
        data.sourceAccessForTest((actor, locator, source, loaded) -> MonitorProfile.Status.LIVE);
        return data;
    }

    private MonitorProfile profile(MonitoringProfiles store, UUID owner) {
        return store.create(
            owner,
            null,
            Collections.singletonMap(
                "0:1:64:2",
                new SourceKey(
                    SourceKey.Kind.DIT,
                    store.epoch(),
                    UUID.randomUUID()
                        .toString(),
                    1)));
    }

    @Test
    public void boundedNamesNormalizeAndRejectFormattingAndControlCharacters() {
        assertEquals("工厂 A", MonitorProfile.normalizeName(" 工厂 A "));
        assertEquals("", MonitorProfile.normalizeName("   "));
        assertEquals(
            48,
            MonitorProfile.normalizeName(String.join("", Collections.nCopies(48, "x")))
                .length());
        for (String name : new String[] { null, "line\nbreak", "tab\t", "§aGreen", "bidi\u202E",
            String.join("", Collections.nCopies(49, "x")) }) {
            try {
                MonitorProfile.normalizeName(name);
                fail("Invalid name accepted");
            } catch (IllegalArgumentException expected) {}
        }
    }

    @Test
    public void renameIsOwnerOnlyPersistedAndOldOrInvalidNamesKeepSafeFallback() {
        MonitoringProfiles store = store();
        UUID owner = UUID.randomUUID(), reader = UUID.randomUUID();
        MonitorProfile profile = profile(store, owner);
        store.share(owner, profile.id, reader);
        String fallback = profile.name();
        assertTrue(
            fallback.contains(
                profile.id.toString()
                    .substring(0, 8)));
        try {
            store.rename(reader, profile.id, "Reader takeover");
            fail("Reader renamed profile");
        } catch (IllegalArgumentException expected) {
            assertEquals(fallback, profile.name());
        }
        store.rename(owner, profile.id, "Factory North");
        long revision = profile.revision();
        store.rename(owner, profile.id, "Factory North");
        assertEquals(revision, profile.revision());
        NBTTagCompound saved = new NBTTagCompound();
        store.writeToNBT(saved);
        MonitoringProfiles restored = store();
        restored.readFromNBT(saved);
        assertEquals(
            "Factory North",
            restored.profileForTerminal(profile.id)
                .name());
        saved.getTagList("profiles", Constants.NBT.TAG_COMPOUND)
            .getCompoundTagAt(0)
            .removeTag("name");
        restored.readFromNBT(saved);
        assertEquals(
            fallback,
            restored.profileForTerminal(profile.id)
                .name());
        saved.getTagList("profiles", Constants.NBT.TAG_COMPOUND)
            .getCompoundTagAt(0)
            .setString("name", "§bad");
        restored.readFromNBT(saved);
        assertEquals(
            fallback,
            restored.profileForTerminal(profile.id)
                .name());
    }

    @Test
    public void ditRecoveryPersistsOnlyWhitelistedPresentationNotCredentialsOrIdentity() {
        MonitoringProfiles store = store();
        MonitorProfile profile = profile(store, UUID.randomUUID());
        ItemStack original = new ItemStack(new Item());
        ItemDeviceInfoTerminal.setSortColumn(original, ItemDeviceInfoTerminal.SORT_NAME);
        ItemDeviceInfoTerminal.setSortDirection(original, ItemDeviceInfoTerminal.DIR_ASC);
        ItemDeviceInfoTerminal.setCountMode(original, ItemDeviceInfoTerminal.NUM_SCIENTIFIC);
        original.setStackDisplayName("Factory terminal");
        original.stackTagCompound.setString("QT_EncryptionKey", "never-copy");
        original.stackTagCompound.setString(ItemDeviceInfoTerminal.NBT_UUID, "original-id");
        original.stackTagCompound.setString("OwnerUUID", "not-authority");
        profile.captureDitPresentation(original);
        NBTTagCompound saved = new NBTTagCompound();
        store.writeToNBT(saved);
        NBTTagCompound record = saved.getTagList("profiles", Constants.NBT.TAG_COMPOUND)
            .getCompoundTagAt(0);
        assertFalse(record.hasKey("QT_EncryptionKey"));
        assertFalse(record.hasKey(ItemDeviceInfoTerminal.NBT_UUID));
        assertFalse(record.hasKey("OwnerUUID"));
        assertFalse(record.hasKey("display"));
        MonitoringProfiles restored = store();
        restored.readFromNBT(saved);
        ItemStack fresh = new ItemStack(new Item());
        fresh.stackTagCompound = new NBTTagCompound();
        fresh.stackTagCompound.setString("QT_EncryptionKey", "replacement-secret");
        restored.profileForTerminal(profile.id)
            .restoreDitPresentation(fresh);
        assertEquals(ItemDeviceInfoTerminal.SORT_NAME, ItemDeviceInfoTerminal.getSortColumn(fresh));
        assertEquals(ItemDeviceInfoTerminal.DIR_ASC, ItemDeviceInfoTerminal.getSortDirection(fresh));
        assertEquals(ItemDeviceInfoTerminal.NUM_SCIENTIFIC, ItemDeviceInfoTerminal.getCountMode(fresh));
        assertEquals("Factory terminal", fresh.getDisplayName());
        assertEquals("replacement-secret", fresh.stackTagCompound.getString("QT_EncryptionKey"));
        assertFalse(fresh.stackTagCompound.hasKey(ItemDeviceInfoTerminal.NBT_UUID));
    }

    @Test
    public void malformedPresentationFallsBackToKnownEnumsWithoutDroppingProfile() {
        MonitorProfile profile = profile(store(), UUID.randomUUID());
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("ditPresentation", true);
        tag.setString("ditSort", "arbitrary");
        tag.setString("ditDirection", "untrusted");
        tag.setString("ditCount", "invalid");
        tag.setString("ditItemName", "§invalid");
        profile.readPresentation(tag);
        ItemStack fresh = new ItemStack(new Item());
        profile.restoreDitPresentation(fresh);
        assertEquals(ItemDeviceInfoTerminal.SORT_AVG, ItemDeviceInfoTerminal.getSortColumn(fresh));
        assertEquals(ItemDeviceInfoTerminal.DIR_DESC, ItemDeviceInfoTerminal.getSortDirection(fresh));
        assertEquals(ItemDeviceInfoTerminal.NUM_NORMAL, ItemDeviceInfoTerminal.getCountMode(fresh));
        assertFalse(fresh.hasDisplayName());
    }
}
