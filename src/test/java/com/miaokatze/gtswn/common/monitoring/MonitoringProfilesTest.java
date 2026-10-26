package com.miaokatze.gtswn.common.monitoring;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;

public class MonitoringProfilesTest {

    private MonitoringProfiles store() {
        MonitoringProfiles store = new MonitoringProfiles("test");
        store.sourceAccessForTest((owner, locator, source, loaded) -> MonitorProfile.Status.LIVE);
        return store;
    }

    private Map<String, SourceKey> sources(MonitoringProfiles store) {
        Map<String, SourceKey> sources = new LinkedHashMap<>();
        sources.put(
            "0:1:64:2",
            new SourceKey(
                SourceKey.Kind.DIT,
                store.epoch(),
                UUID.randomUUID()
                    .toString(),
                1));
        return sources;
    }

    @Test
    public void copiedLegacyUuidImportsOnceWithoutClaimingLegacyBearer() {
        MonitoringProfiles store = store();
        UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID(), legacy = UUID.randomUUID();
        MonitorProfile profile = store.create(owner, legacy, sources(store));
        assertSame(profile, store.create(owner, legacy, sources(store)));
        assertTrue(store.canRead(stranger, legacy));
        assertFalse(store.canRead(stranger, profile.id));
        try {
            store.create(stranger, legacy, sources(store));
            fail("Copied UUID cannot claim ownership");
        } catch (IllegalArgumentException expected) {
            assertEquals(
                1,
                store.visibleProfiles(owner)
                    .size());
        }
    }

    @Test
    public void sharingRevocationTransferAndNativeLossFailClosed() {
        MonitoringProfiles store = store();
        UUID owner = UUID.randomUUID(), reader = UUID.randomUUID();
        MonitorProfile profile = store.create(owner, null, sources(store));
        store.share(owner, profile.id, reader);
        assertTrue(store.canRead(reader, profile.id));
        assertFalse(store.canModify(reader, profile.id));
        long revision = profile.revision();
        store.revoke(owner, profile.id, reader);
        assertFalse(store.canRead(reader, profile.id));
        assertTrue(profile.revision() > revision);
        store.setOffline(owner, profile.id, true, 20);
        store.transfer(owner, profile.id, reader);
        assertFalse(store.canRead(owner, profile.id));
        assertTrue(store.canModify(reader, profile.id));
        assertFalse(profile.offlineEnabled());
        store.sourceAccessForTest((a, b, c, d) -> MonitorProfile.Status.NO_PERMISSION);
        assertFalse(store.canRead(reader, profile.id));
        assertEquals(MonitorProfile.Status.NO_PERMISSION, profile.status());
    }

    @Test
    public void offlineIntentAndAclSurviveRestartButNoLivePlayerOrSampleClaimDoes() {
        MonitoringProfiles original = store();
        UUID owner = UUID.randomUUID(), reader = UUID.randomUUID(), legacy = UUID.randomUUID();
        MonitorProfile profile = original.create(owner, legacy, sources(original));
        original.share(owner, profile.id, reader);
        original.setOffline(owner, profile.id, true, 100);
        original.sampled(profile.id, 400);
        NBTTagCompound saved = new NBTTagCompound();
        original.writeToNBT(saved);
        MonitoringProfiles restored = store();
        restored.readFromNBT(saved);
        MonitorProfile copy = restored.profileForTerminal(profile.id);
        assertEquals(original.epoch(), restored.epoch());
        assertTrue(copy.offlineEnabled());
        assertTrue(restored.canRead(reader, copy.id));
        assertEquals(
            Math.max(100, com.miaokatze.gtswn.config.Config.deviceSampleIntervalSeconds * 20),
            copy.intervalTicks());
        assertEquals(400, copy.sampleTime());
        assertEquals(Long.MIN_VALUE, copy.lastSampleTick);
        assertSame(copy, restored.create(owner, legacy, Collections.emptyMap()));
    }

    @Test
    public void malformedPersistedProfileDoesNotBecomeLegacyBearer() {
        MonitoringProfiles store = store();
        UUID owner = UUID.randomUUID();
        MonitorProfile profile = store.create(owner, null, sources(store));
        NBTTagCompound saved = new NBTTagCompound();
        store.writeToNBT(saved);
        saved.getTagList("profiles", 10)
            .getCompoundTagAt(0)
            .setString("owner", "invalid");
        MonitoringProfiles restored = store();
        restored.readFromNBT(saved);
        assertNull(restored.profileForTerminal(profile.id));
        assertFalse(restored.canRead(owner, profile.id));
        assertFalse(restored.canModify(owner, profile.id));
        NBTTagCompound savedAgain = new NBTTagCompound();
        restored.writeToNBT(savedAgain);
        MonitoringProfiles twice = store();
        twice.readFromNBT(savedAgain);
        assertFalse(twice.canRead(owner, profile.id));
    }

    @Test
    public void missingSourceListNeverBecomesAuthorizedEmptyProfile() {
        MonitoringProfiles store = store();
        UUID owner = UUID.randomUUID();
        MonitorProfile profile = store.create(owner, null, sources(store));
        NBTTagCompound saved = new NBTTagCompound();
        store.writeToNBT(saved);
        saved.getTagList("profiles", 10)
            .getCompoundTagAt(0)
            .removeTag("sources");
        MonitoringProfiles restored = store();
        restored.readFromNBT(saved);
        assertNull(restored.profileForTerminal(profile.id));
        assertFalse(restored.canRead(owner, profile.id));
        assertFalse(restored.canModify(owner, profile.id));
        assertFalse(restored.maySample(profile.id));
    }

    @Test
    public void jobsAreExplicitBoundedLoadedOnlyAndRestartReady() {
        MonitoringProfiles store = store();
        UUID owner = UUID.randomUUID();
        MonitorProfile first = null;
        for (int i = 0; i <= MonitoringProfiles.MAX_OWNER_JOBS; i++) {
            MonitorProfile profile = store.create(owner, null, sources(store));
            if (first == null) first = profile;
            assertFalse(profile.offlineEnabled());
            if (i < MonitoringProfiles.MAX_OWNER_JOBS) store.setOffline(owner, profile.id, true, 20);
            else {
                try {
                    store.setOffline(owner, profile.id, true, 20);
                    fail("Quota exceeded");
                } catch (IllegalArgumentException expected) {
                    assertFalse(profile.offlineEnabled());
                }
            }
        }
        DeviceTerminalDataStore data = new DeviceTerminalDataStore("test");
        data.getOrCreateTerminal(first.id);
        assertEquals(
            1,
            store.samplingTerminals(data, 100)
                .size());
        assertTrue(
            store.samplingTerminals(data, 101)
                .isEmpty());
        assertEquals(
            1,
            store.samplingTerminals(data, 100 + first.intervalTicks())
                .size());
        assertEquals(
            1,
            store.samplingTerminals(data, 10)
                .size()); // rolled-back clock re-establishes cadence
        store.sourceAccessForTest((a, b, c, d) -> MonitorProfile.Status.SOURCE_UNLOADED);
        assertTrue(store.canRead(owner, first.id)); // authorized stale snapshot is not a new observation
        assertFalse(store.maySample(first.id));
        assertTrue(
            store.samplingTerminals(data, 200)
                .isEmpty());
        assertEquals(MonitorProfile.Status.SOURCE_UNLOADED, first.status());
    }

    @Test
    public void importCopyKeepsLegacyScopeAndPreservesProfileHistoryOnReplacement() {
        DeviceTerminalDataStore data = new DeviceTerminalDataStore("test");
        UUID legacy = UUID.randomUUID(), profile = UUID.randomUUID();
        String key = "0:1:64:2";
        data.addBinding(legacy, key, "original", 0, 1, 64, 2, (byte) 0);
        data.getTerminal(legacy).records.get(key).fifoIn[0] = 42;
        data.getTerminal(legacy).records.get(key).count = 1;
        data.copyForProfile(legacy, profile);
        data.getTerminal(profile).records.get(key).fifoIn[0] = 99;
        assertEquals(42, data.getTerminal(legacy).records.get(key).fifoIn[0], 0);
        data.copyForProfile(legacy, profile);
        assertEquals(99, data.getTerminal(profile).records.get(key).fifoIn[0], 0);
        data.removeKeyFromAll(key);
        assertTrue(data.getTerminal(legacy).records.isEmpty());
        assertEquals(99, data.getTerminal(profile).records.get(key).fifoIn[0], 0);
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        DeviceTerminalDataStore restored = new DeviceTerminalDataStore("copy");
        restored.readFromNBT(tag);
        assertTrue(restored.getTerminal(profile).profileManaged);
    }

    @Test
    public void totalUniqueJobSourceDemandIsBoundedBelowFlowMonitorCapacity() {
        MonitoringProfiles store = store();
        for (int ownerIndex = 0; ownerIndex < 9; ownerIndex++) {
            UUID owner = UUID.randomUUID();
            Map<String, SourceKey> sources = new LinkedHashMap<>();
            for (int i = 0; i < 512; i++) sources.put(
                "0:" + i + ":64:" + ownerIndex,
                new SourceKey(
                    SourceKey.Kind.DIT,
                    store.epoch(),
                    UUID.randomUUID()
                        .toString(),
                    1));
            MonitorProfile profile = store.create(owner, null, sources);
            if (ownerIndex < 8) store.setOffline(owner, profile.id, true, 100);
            else {
                try {
                    store.setOffline(owner, profile.id, true, 100);
                    fail("Aggregate source quota required");
                } catch (IllegalArgumentException expected) {
                    assertFalse(profile.offlineEnabled());
                }
            }
        }
    }

    @Test
    public void partialProfileMetadataCannotWidenManagedDeviceReadOrSampleScope() {
        MonitoringProfiles profiles = store();
        DeviceTerminalDataStore data = new DeviceTerminalDataStore("test");
        UUID original = UUID.randomUUID(), profile = UUID.randomUUID();
        data.addBinding(original, "0:1:64:2", "one", 0, 1, 64, 2, (byte) 0);
        data.addBinding(original, "0:2:64:2", "two", 0, 2, 64, 2, (byte) 0);
        data.copyForProfile(original, profile);
        Map<String, SourceKey> onlyOne = sources(profiles);
        assertFalse(MonitoringProfiles.fixedDeviceScopeMatches(data.getTerminal(profile), onlyOne));
        onlyOne.put(
            "0:2:64:2",
            new SourceKey(
                SourceKey.Kind.DIT,
                profiles.epoch(),
                UUID.randomUUID()
                    .toString(),
                1));
        assertTrue(MonitoringProfiles.fixedDeviceScopeMatches(data.getTerminal(profile), onlyOne));
        data.getTerminal(profile).records.remove("0:2:64:2");
        assertFalse(MonitoringProfiles.fixedDeviceScopeMatches(data.getTerminal(profile), onlyOne));
    }

    @Test
    public void perOwnerUniqueSourceQuotaIsIndependentOfJobCount() {
        MonitoringProfiles store = store();
        UUID owner = UUID.randomUUID();
        for (int job = 0; job < 3; job++) {
            Map<String, SourceKey> sources = new LinkedHashMap<>();
            for (int i = 0; i < 512; i++) sources.put(
                "0:" + i + ":64:" + job,
                new SourceKey(
                    SourceKey.Kind.DIT,
                    store.epoch(),
                    UUID.randomUUID()
                        .toString(),
                    1));
            MonitorProfile profile = store.create(owner, null, sources);
            if (job < 2) store.setOffline(owner, profile.id, true, 100);
            else {
                try {
                    store.setOffline(owner, profile.id, true, 100);
                    fail("Owner source quota required below4joblimit");
                } catch (IllegalArgumentException expected) {
                    assertFalse(profile.offlineEnabled());
                }
            }
        }
    }

    @Test
    public void requestedSlowPortableCadenceIsNeverAcceleratedToNativeFloor() {
        MonitoringProfiles store = store();
        UUID owner = UUID.randomUUID();
        MonitorProfile profile = store.create(
            owner,
            null,
            Collections.singletonMap(
                "eu|account",
                new SourceKey(
                    SourceKey.Kind.EU,
                    store.epoch(),
                    UUID.randomUUID()
                        .toString(),
                    1)));
        store.setOffline(owner, profile.id, true, 72000);
        assertEquals(72000, profile.intervalTicks());
        assertTrue(MonitoringProfiles.jobDue(profile, 100));
        profile.lastSampleTick = 100;
        assertFalse(MonitoringProfiles.jobDue(profile, 120));
        assertFalse(MonitoringProfiles.jobDue(profile, 72099));
        assertTrue(MonitoringProfiles.jobDue(profile, 72100));
        assertTrue(MonitoringProfiles.jobDue(profile, 10));
        store.setOffline(owner, profile.id, false, 72000);
        assertFalse(MonitoringProfiles.jobDue(profile, 100000));
    }

    @Test
    public void actualCollectorTimestampIsNeverReplacedByJobAttemptTime() {
        MonitoringProfiles store = store();
        MonitorProfile profile = store.create(UUID.randomUUID(), null, sources(store));
        MonitoringProfiles.recordNativeSample(profile, 0, 200);
        assertEquals(0, profile.sampleTime());
        assertFalse(profile.sampledThisSession);
        MonitoringProfiles.recordNativeSample(profile, 100, 200);
        assertEquals(100, profile.sampleTime());
        MonitoringProfiles.recordNativeSample(profile, 100, 400);
        assertEquals(100, profile.sampleTime()); // The same shared cached sample was read later, not sampled later.
        MonitoringProfiles.recordNativeSample(profile, 900, 400);
        assertEquals(100, profile.sampleTime()); // A future/rolled-back timestamp cannot make a snapshot live.
    }

    @Test
    public void freshnessRequiresCurrentSessionRealTimeAndEffectiveCadence() {
        MonitoringProfiles store = store();
        MonitorProfile profile = store.create(UUID.randomUUID(), null, sources(store));
        MonitoringProfiles.recordNativeSample(profile, 100, 100);
        assertEquals(MonitorProfile.Status.LIVE, MonitoringProfiles.sampleStatus(profile, 300, 100));
        assertEquals(MonitorProfile.Status.CACHED, MonitoringProfiles.sampleStatus(profile, 301, 100));
        assertEquals(MonitorProfile.Status.CACHED, MonitoringProfiles.sampleStatus(profile, 99, 100));
        profile.sampledThisSession = false;
        assertEquals(MonitorProfile.Status.CACHED, MonitoringProfiles.sampleStatus(profile, 100, 100));
        profile.sampledThisSession = true;
        profile.setOffline(true, 1000);
        assertEquals(MonitorProfile.Status.LIVE, MonitoringProfiles.sampleStatus(profile, 2100, 100));
        assertEquals(MonitorProfile.Status.CACHED, MonitoringProfiles.sampleStatus(profile, 2101, 100));
        assertEquals(100, profile.sampleTime());
        assertEquals(
            1,
            profile.sources()
                .size());
    }

    @Test
    public void disablingJobPreservesHistoryAndReadCannotResumePausedSampling() {
        MonitoringProfiles store = store();
        MonitorProfile profile = store.create(UUID.randomUUID(), null, sources(store));
        profile.setOffline(true, 72000);
        MonitoringProfiles.recordNativeSample(profile, 100, 100);
        profile.setOffline(false, 72000);
        assertTrue(store.canRead(profile.owner(), profile.id));
        assertEquals(MonitorProfile.Status.JOB_PAUSED, profile.status());
        MonitoringProfiles.recordNativeSample(profile, 100, 120);
        assertEquals(MonitorProfile.Status.JOB_PAUSED, MonitoringProfiles.sampleStatus(profile, 120, 100));
        assertEquals(100, profile.sampleTime());
        assertEquals(
            1,
            profile.sources()
                .size());
        MonitoringProfiles.recordNativeSample(profile, 200, 200); // A real online observer collected another sample.
        assertEquals(MonitorProfile.Status.LIVE, MonitoringProfiles.sampleStatus(profile, 200, 100));
        assertEquals(MonitorProfile.Status.CACHED, MonitoringProfiles.sampleStatus(profile, 401, 100));
        assertEquals(200, profile.sampleTime()); // Disabled slow-job cadence cannot stretch online freshness.
    }

    @Test
    public void mixedProjectionCannotBorrowFreshestSourcesTimestamp() {
        MonitoringProfiles store = store();
        MonitorProfile profile = store.create(UUID.randomUUID(), null, sources(store));
        MonitoringProfiles.recordNativeSample(profile, 1000, 1000);
        long oldest = MonitoringProfiles.oldestProjectionSample(Long.MAX_VALUE, 1000, 1000);
        oldest = MonitoringProfiles.oldestProjectionSample(oldest, 100, 1000);
        assertEquals(100, oldest);
        assertEquals(MonitorProfile.Status.CACHED, MonitoringProfiles.sampleStatus(profile, 1000, 100, oldest));
        assertEquals(1000, profile.sampleTime()); // Projection does not rewrite collector bookkeeping.
        assertEquals(0, MonitoringProfiles.oldestProjectionSample(oldest, -1, 1000));
        assertEquals(0, MonitoringProfiles.oldestProjectionSample(oldest, 1001, 1000));
        assertEquals(0, MonitoringProfiles.oldestProjectionSample(0, 1000, 1000));
        assertEquals(MonitorProfile.Status.LIVE, MonitoringProfiles.sampleStatus(profile, 1000, 100, 900));
        assertEquals(MonitorProfile.Status.CACHED, MonitoringProfiles.sampleStatus(profile, 1000, 100, 0));
    }

    @Test
    public void rawHistoryExportsChronologicalBoundedValues() {
        assertArrayEquals(
            new double[] { 3, 4, 1, 2 },
            MonitoringProfiles.rawHistory(new double[] { 1, 2, 3, 4 }, 2, 99),
            0);
        double[] unknown = MonitoringProfiles.rawHistory(new double[] { Double.NaN, 2, 3 }, 2, 2);
        assertTrue(Double.isNaN(unknown[0]));
        assertEquals(2, unknown[1], 0);
        assertEquals(0, MonitoringProfiles.rawHistory(new double[0], 7, 3).length);
    }
}
