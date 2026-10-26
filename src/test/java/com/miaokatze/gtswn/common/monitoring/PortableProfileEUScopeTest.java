package com.miaokatze.gtswn.common.monitoring;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;

public class PortableProfileEUScopeTest {

    private final MonitoringProfiles store = new MonitoringProfiles("eu-scope");
    private final UUID owner = UUID.randomUUID(), reader = UUID.randomUUID(), account = UUID.randomUUID();

    private MonitorProfile profile() {
        Map<String, SourceKey> sources = new LinkedHashMap<>();
        sources.put("eu|" + account, new SourceKey(SourceKey.Kind.EU, store.epoch(), account.toString(), 1));
        sources.put(
            "panel|0:1:64:2|" + UUID.randomUUID(),
            new SourceKey(
                SourceKey.Kind.AE,
                store.epoch(),
                UUID.randomUUID()
                    .toString(),
                1));
        store.sourceAccessForTest((a, b, c, d) -> MonitorProfile.Status.LIVE);
        MonitorProfile profile = store.create(owner, null, sources);
        profile.share(reader);
        return profile;
    }

    private ItemStack terminal(MonitorProfile profile) {
        ItemStack held = new ItemStack(new PortableWirelessNetworkMonitor());
        PortableProfileAdapter.restore(held, profile);
        return held;
    }

    @Test
    public void detachedAccountCannotLeakThroughRetainedAePanelOrFollowNewTeam() {
        MonitorProfile profile = profile();
        ItemStack held = terminal(profile);
        // Establish the imported authorization without starting the real Forge EU sampler in this headless fixture.
        assertTrue(PortableProfileAdapter.matches(held, profile));
        assertTrue(store.canRead(reader, profile.id));
        assertEquals(
            new SourceKey(SourceKey.Kind.EU, store.epoch(), account.toString(), 1),
            profile.sources()
                .get("eu|" + account));
        store.detachSource(owner, profile.id, "eu|" + account);
        assertTrue(PortableProfileAdapter.matches(held, profile));
        assertTrue(store.canUsePortable(reader, held)); // AE-only monitoring remains legitimate.
        assertFalse(store.canReadPortableEU(reader, held, account));
        assertFalse(store.canReadPortableEU(reader, held, UUID.randomUUID()));
        assertFalse(store.canReadPortableEU(reader, terminal(profile), account)); // Recovery does not widen scope.
    }

    @Test
    public void exactAccountEpochGenerationAndAclAreRequired() {
        MonitorProfile profile = profile();
        ItemStack held = terminal(profile);
        assertFalse(store.canReadPortableEU(reader, held, UUID.randomUUID()));
        assertFalse(store.canReadPortableEU(UUID.randomUUID(), held, account));
        assertFalse(store.canReadPortableEU(reader, held, null));
        store.sourceAccessForTest((a, b, c, d) -> MonitorProfile.Status.NO_PERMISSION);
        assertFalse(store.canReadPortableEU(reader, held, account));
        store.sourceAccessForTest((a, b, c, d) -> MonitorProfile.Status.LIVE);
        store.revoke(owner, profile.id, reader);
        assertFalse(store.canReadPortableEU(reader, held, account));

        for (SourceKey source : new SourceKey[] {
            new SourceKey(SourceKey.Kind.EU, UUID.randomUUID(), account.toString(), 1),
            new SourceKey(SourceKey.Kind.EU, store.epoch(), account.toString(), 2),
            new SourceKey(
                SourceKey.Kind.EU,
                store.epoch(),
                UUID.randomUUID()
                    .toString(),
                1) }) {
            MonitorProfile mismatched = store
                .create(owner, null, java.util.Collections.singletonMap("eu|" + account, source));
            assertFalse(store.canReadPortableEU(owner, terminal(mismatched), account));
        }
    }

    @Test
    public void missingAndMalformedReferencesFailClosedButLegacyBearerIsUnchanged() {
        ItemStack held = terminal(profile());
        held.stackTagCompound.setString(
            MonitoringProfiles.NBT_PROFILE,
            UUID.randomUUID()
                .toString());
        assertFalse(store.canReadPortableEU(reader, held, account));
        held.stackTagCompound.setString(MonitoringProfiles.NBT_PROFILE, "malformed");
        assertFalse(store.canReadPortableEU(reader, held, account));
        held.stackTagCompound.removeTag(MonitoringProfiles.NBT_PROFILE);
        assertTrue(store.canReadPortableEU(reader, held, account));
    }

    @Test
    public void restartDoesNotRestoreDetachedAccountPermission() {
        MonitorProfile profile = profile();
        ItemStack held = terminal(profile);
        store.detachSource(owner, profile.id, "eu|" + account);
        NBTTagCompound tag = new NBTTagCompound();
        store.writeToNBT(tag);
        MonitoringProfiles restored = new MonitoringProfiles("restored");
        restored.readFromNBT(tag);
        restored.sourceAccessForTest((a, b, c, d) -> MonitorProfile.Status.LIVE);
        assertTrue(restored.canUsePortable(reader, held));
        assertFalse(restored.canReadPortableEU(reader, held, account));
        assertFalse(restored.canReadPortableEU(reader, held, UUID.randomUUID()));
    }
}
