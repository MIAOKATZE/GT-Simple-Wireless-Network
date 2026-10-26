package com.miaokatze.gtswn.common.monitoring;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;

public class QuantumProfileRecoveryTest {

    private final UUID owner = UUID.randomUUID(), reader = UUID.randomUUID(), legacy = UUID.randomUUID();
    private final MonitoringProfiles store = new MonitoringProfiles("recovery");
    private final MonitorProfile profile = store.create(
        owner,
        legacy,
        Collections.singletonMap(
            "quantum:0:1:64:2",
            new SourceKey(SourceKey.Kind.QUANTUM, store.epoch(), "original-controller-generation", 1)));

    private ItemStack terminal() {
        ItemStack held = new ItemStack(new ItemNetworkQuantumTerminal());
        held.stackTagCompound = new NBTTagCompound();
        held.stackTagCompound.setString(MonitoringProfiles.NBT_PROFILE, profile.id.toString());
        held.stackTagCompound.setString("MonitorImportId", legacy.toString());
        held.stackTagCompound.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X, 300); // Native migration changed
                                                                                        // locator.
        held.stackTagCompound.setString("QT_EncryptionKey", "native-pairing-fixture");
        return held;
    }

    @Test
    public void ownerCanReleaseAfterMigrationWithoutRetargetingExistingProfileOrCopies() {
        store.sourceAccessForTest((a, b, c, d) -> MonitorProfile.Status.SOURCE_REPLACED);
        ItemStack held = terminal(), copy = held.copy();
        NBTTagCompound nativeTags = (NBTTagCompound) held.stackTagCompound.copy();
        nativeTags.removeTag(MonitoringProfiles.NBT_PROFILE);
        nativeTags.removeTag("MonitorImportId");
        assertFalse(store.canRead(owner, profile.id));
        store.releaseQuantum(owner, held, profile.id);
        assertEquals(nativeTags, held.stackTagCompound);
        assertEquals(profile.id, MonitoringProfiles.readProfileReference(copy));
        assertSame(profile, store.profileForTerminal(profile.id));
        assertTrue(
            profile.sources()
                .containsKey("quantum:0:1:64:2"));
        assertFalse(store.canRead(owner, profile.id));
        // The old lineage is still reserved, even after releasing this individual item.
        assertSame(profile, store.create(owner, legacy, profile.sources()));
        try {
            store.create(reader, legacy, profile.sources());
            fail("Old lineage cannot be claimed");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void sharedReadersAndStrangersCannotRemoveManagedAcl() {
        profile.share(reader);
        ItemStack held = terminal();
        for (UUID actor : new UUID[] { reader, UUID.randomUUID(), null }) {
            NBTTagCompound before = (NBTTagCompound) held.stackTagCompound.copy();
            try {
                store.releaseQuantum(actor, held, profile.id);
                fail("Only owner may release");
            } catch (IllegalArgumentException expected) {}
            assertEquals(before, held.stackTagCompound);
        }
    }

    @Test
    public void releaseRequiresExactHeldQuantumReference() {
        for (ItemStack held : new ItemStack[] { new ItemStack(new Item()), terminal(), terminal() }) {
            if (held.getItem() instanceof ItemNetworkQuantumTerminal) held.stackTagCompound.setString(
                MonitoringProfiles.NBT_PROFILE,
                UUID.randomUUID()
                    .toString());
            try {
                store.releaseQuantum(owner, held, profile.id);
                fail("Wrong held item/reference");
            } catch (IllegalArgumentException expected) {}
        }
    }

    @Test
    public void splitOrMergeDoesNotBecomeAuthorizedByRelease() {
        store.sourceAccessForTest((a, b, c, d) -> MonitorProfile.Status.SOURCE_REPLACED);
        profile.webEnabled = true;
        profile.offline = true;
        store.releaseQuantum(owner, terminal(), profile.id);
        assertFalse(store.canRead(owner, profile.id));
        assertFalse(store.canPublish(profile.id));
        assertEquals(MonitorProfile.Status.SOURCE_REPLACED, profile.status());
    }
}
