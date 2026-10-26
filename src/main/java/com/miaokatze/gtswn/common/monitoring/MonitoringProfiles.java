package com.miaokatze.gtswn.common.monitoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.Constants;

import com.miaokatze.gtswn.common.device.DeviceMachineTypes;
import com.miaokatze.gtswn.common.device.DeviceRegistryData;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.device.KeyFormat;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.util.SavedDataUtil;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * Main-thread authority for recoverable monitoring references. Import is explicit and non-destructive;
 * an old bearer UUID is never converted into ownership. HTTP adapters must publish their own immutable projection.
 */
public final class MonitoringProfiles extends WorldSavedData {

    public static final String DATA_NAME = "gtswn_monitor_profiles";
    public static final String NBT_PROFILE = "GTSWN_MonitorProfile";
    public static final int MAX_JOB_SOURCES = 4096;
    public static final int MAX_OWNER_JOB_SOURCES = 1024;
    public static final int MAX_PROFILES = 4096;
    public static final int MAX_OWNER_PROFILES = 32;
    public static final int MAX_READERS = 64;
    public static final int MAX_SOURCES = 512;
    public static final int MAX_JOBS = 64;
    public static final int MAX_OWNER_JOBS = 4;
    public static final int HISTORY_POINTS = DeviceTerminalDataStore.FIFO_SIZE;
    private UUID epoch = UUID.randomUUID();
    private final Map<UUID, MonitorProfile> profiles = new LinkedHashMap<>();
    private final Map<UUID, UUID> migrations = new LinkedHashMap<>();
    private final Set<UUID> protectedIds = new LinkedHashSet<>();
    private transient World world;
    private transient SourceAccess sourceAccess = this::checkNativeSource;

    interface SourceAccess {

        MonitorProfile.Status check(UUID owner, String locator, SourceKey source, boolean loadedRequired);
    }

    public MonitoringProfiles(String name) {
        super(name);
    }

    public static MonitoringProfiles get(World requested) {
        MinecraftServer server = MinecraftServer.getServer();
        World root = server == null ? requested : server.worldServerForDimension(0);
        if (root == null) root = requested;
        if (root == null || root.isRemote) throw new IllegalStateException("Server world required");
        MonitoringProfiles data = SavedDataUtil.loadOrCreate(root, DATA_NAME, MonitoringProfiles.class, name -> {
            MonitoringProfiles created = new MonitoringProfiles(name);
            created.markDirty(); // A new epoch must persist; loading existing provenance is read-only.
            return created;
        });
        data.world = root;
        return data;
    }

    /** Load existing provenance only; a public read must not initialize monitoring metadata. */
    public static MonitoringProfiles getIfPresent(World requested) {
        MinecraftServer server = MinecraftServer.getServer();
        World root = server == null ? requested : server.worldServerForDimension(0);
        if (root == null) root = requested;
        if (root == null || root.isRemote) return null;
        return (MonitoringProfiles) root.perWorldStorage.loadData(MonitoringProfiles.class, DATA_NAME);
    }

    public UUID epoch() {
        return epoch;
    }

    public MonitorProfile profileForTerminal(UUID terminalId) {
        return profiles.get(terminalId);
    }

    public long authorizationRevision(UUID terminalId) {
        MonitorProfile profile = profiles.get(terminalId);
        return profile == null ? 0 : profile.revision;
    }

    public String sourceRevision(UUID terminalId) {
        MonitorProfile profile = profiles.get(terminalId);
        return epoch + ":"
            + (profile == null ? "legacy"
                : profile.sources()
                    .toString());
    }

    /** Only old UUIDs without a profile retain their historical bearer semantics. */
    public boolean canRead(UUID actor, UUID terminalId) {
        if (actor == null || terminalId == null) return false;
        MonitorProfile profile = profiles.get(terminalId);
        return profile == null ? legacyAllowed(terminalId)
            : profile.mayRead(actor) && authorizedSources(profile, false);
    }

    public boolean canModify(UUID actor, UUID terminalId) {
        MonitorProfile profile = profiles.get(terminalId);
        return actor != null && terminalId != null
            && (profile == null ? legacyAllowed(terminalId) : profile.mayModify(actor));
    }

    private boolean legacyAllowed(UUID terminalId) {
        if (protectedIds.contains(terminalId)) return false;
        DeviceTerminalDataStore.TerminalData data = world == null ? null
            : DeviceTerminalDataStore.get(world)
                .getTerminal(terminalId);
        return data == null || !data.profileManaged; // Missing/corrupt profile storage must never downgrade private
                                                     // data to bearer access.
    }

    public boolean hasOfflineObservation(String locator) {
        for (MonitorProfile profile : profiles.values()) if (profile.offline && profile.sources()
            .containsKey(locator) && authorizedSources(profile, true)) return true;
        return false;
    }

    public List<MonitorProfile> visibleProfiles(UUID actor) {
        List<MonitorProfile> result = new ArrayList<>();
        for (MonitorProfile profile : profiles.values()) if (profile.mayRead(actor)) result.add(profile);
        return Collections.unmodifiableList(result);
    }

    public static boolean hasProfileReference(ItemStack stack) {
        return stack != null && stack.stackTagCompound != null && stack.stackTagCompound.hasKey(NBT_PROFILE);
    }

    public static UUID readProfileReference(ItemStack stack) {
        if (!hasProfileReference(stack)) return null;
        try {
            return UUID.fromString(stack.stackTagCompound.getString(NBT_PROFILE));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    public boolean canUsePortable(UUID actor, ItemStack actualStack) {
        if (!hasProfileReference(actualStack)) return true; // Unchanged legacy bearer contract.
        UUID id = readProfileReference(actualStack);
        MonitorProfile profile = profiles.get(id);
        if (profile == null || !isPortable(profile)
            || !PortableProfileAdapter.matches(actualStack, profile)
            || !canRead(actor, id)) return false;
        for (Map.Entry<String, SourceKey> source : profile.sources()
            .entrySet()) {
            PortableProfileAdapter.renew(source.getKey(), source.getValue(), false);
            long sampled = PortableProfileAdapter.sampleTick(source.getKey(), source.getValue());
            if (world != null) recordNativeSample(profile, sampled, world.getTotalWorldTime());
        }
        return true;
    }

    public boolean canModifyPortable(UUID actor, ItemStack actualStack) {
        if (!hasProfileReference(actualStack)) return true;
        UUID id = readProfileReference(actualStack);
        MonitorProfile profile = profiles.get(id);
        return profile != null && isPortable(profile) && profile.mayModify(actor) && canRead(actor, id);
    }

    /**
     * Panel authorization alone is not authority for the owner's current wireless account. In particular,
     * detaching the root EU source must not let a retained AE panel follow a later wireless-team change.
     * Callers supply the actual native account being projected, never a client-supplied owner identity.
     */
    public boolean canReadPortableEU(UUID actor, ItemStack actualStack, UUID account) {
        if (account == null) return false;
        if (!hasProfileReference(actualStack)) return canUsePortable(actor, actualStack);
        MonitorProfile profile = profiles.get(readProfileReference(actualStack));
        if (profile == null) return false;
        SourceKey expected = new SourceKey(SourceKey.Kind.EU, epoch, account.toString(), 1);
        return expected.equals(
            profile.sources()
                .get("eu|" + account))
            && canUsePortable(actor, actualStack);
    }

    public boolean detachPortablePanel(UUID actor, ItemStack stack, UUID panelId) {
        if (!hasProfileReference(stack)) return true;
        MonitorProfile profile = profiles.get(readProfileReference(stack));
        if (profile == null || !profile.mayModify(actor)) return false;
        for (String locator : new ArrayList<>(
            profile.sources()
                .keySet())) {
            if (locator.startsWith("panel|") && locator.endsWith("|" + panelId)) {
                detachSource(actor, profile.id, locator);
                return true;
            }
        }
        return false;
    }

    private static boolean isPortable(MonitorProfile profile) {
        for (SourceKey source : profile.sources()
            .values()) if (source.kind == SourceKey.Kind.EU || source.kind == SourceKey.Kind.AE) return true;
        return false;
    }

    private static boolean isQuantum(MonitorProfile profile) {
        for (SourceKey source : profile.sources()
            .values()) if (source.kind == SourceKey.Kind.QUANTUM) return true;
        return false;
    }

    private MonitorProfile importNonDevice(EntityPlayerMP actor, ItemStack held) {
        UUID existingId = readProfileReference(held);
        if (existingId != null) {
            MonitorProfile existing = requireProfile(existingId);
            requireOwner(existing, actor.getUniqueID());
            if (!authorizedSources(existing, false)) throw new IllegalArgumentException(
                "Source permission/identity changed. For a migrated quantum terminal, use /monitorprofile release "
                    + existing.id
                    + " then rebind/import; the old profile remains fixed.");
            return existing;
        }
        Map<String, SourceKey> sources;
        UUID legacy;
        if (PortableProfileAdapter.supports(held)) {
            sources = PortableProfileAdapter.capture(actor, held, epoch);
            legacy = com.miaokatze.gtswn.common.hud.MonitorBindings.ensureId(held);
        } else {
            sources = QuantumProfileAdapter.capture(actor, held, epoch);
            // This lineage is generated on the actual held item, not an owner or network identity.
            if (held.stackTagCompound == null) held.stackTagCompound = new NBTTagCompound();
            String old = held.stackTagCompound.getString("MonitorImportId");
            try {
                legacy = UUID.fromString(old);
            } catch (IllegalArgumentException absent) {
                legacy = UUID.randomUUID();
                held.stackTagCompound.setString("MonitorImportId", legacy.toString());
            }
        }
        if (sources.isEmpty()) throw new IllegalArgumentException("Native terminal authorization failed");
        MonitorProfile profile = create(actor.getUniqueID(), legacy, sources);
        if (!authorizedSources(profile, false)) throw new IllegalArgumentException("Native authorization unavailable");
        if (PortableProfileAdapter.supports(held)) PortableProfileAdapter.restore(held, profile);
        else held.stackTagCompound.setString(NBT_PROFILE, profile.id.toString());
        actor.inventory.markDirty();
        com.miaokatze.gtswn.common.hud.MonitorBindings.synchronize(actor);
        ItemDeviceInfoTerminal.syncHeldTerminalSlot(actor);
        return profile;
    }

    /** Server inspected held item and loaded native devices; UUID command arguments are never proof. */
    public MonitorProfile importHeld(EntityPlayerMP actor) {
        ItemStack held = actor.getHeldItem();
        if (PortableProfileAdapter.supports(held) || QuantumProfileAdapter.supports(held))
            return importNonDevice(actor, held);
        held = requireTerminal(actor);
        UUID legacy = ItemDeviceInfoTerminal.ensureTerminalIdSynced(actor, held);
        MonitorProfile existing = profiles.get(legacy);
        if (existing != null) {
            requireOwner(existing, actor.getUniqueID());
            saveDitPresentation(actor, held);
            return existing;
        }
        UUID migrated = migrations.get(legacy);
        if (migrated != null) {
            MonitorProfile profile = requireProfile(migrated);
            requireOwner(profile, actor.getUniqueID());
            if (!authorizedSources(profile, false)) throw new IllegalArgumentException("Source permission changed");
            bindHeld(actor, held, profile.id);
            return profile;
        }
        DeviceTerminalDataStore store = DeviceTerminalDataStore.get(actor.worldObj);
        DeviceTerminalDataStore.TerminalData data = store.getTerminal(legacy);
        if (data != null && data.profileManaged) throw new IllegalArgumentException(
            "Profile metadata unavailable; restore its saved data, not legacy ownership");
        Map<String, SourceKey> sources = new LinkedHashMap<>();
        if (data != null) for (String key : data.boundKeys) {
            DeviceRegistryData.DeviceEntry entry = DeviceRegistryData.get(actor.worldObj)
                .getEntry(key);
            if (entry == null) throw new IllegalArgumentException("Source is not registered: " + key);
            int[] pos = KeyFormat.parse(key);
            World loaded = pos == null ? null : DimensionManager.getWorld(pos[0]);
            if (loaded == null || !loaded.blockExists(pos[1], pos[2], pos[3]))
                throw new IllegalArgumentException("Import requires loaded native sources");
            TileEntity liveTile = loaded.getTileEntity(pos[1], pos[2], pos[3]);
            if (!(liveTile instanceof IGregTechTileEntity) || !actor.getUniqueID()
                .equals(((IGregTechTileEntity) liveTile).getOwnerUuid()))
                throw new IllegalArgumentException("Import requires native device ownership");
            IGregTechTileEntity nativeTile = (IGregTechTileEntity) liveTile;
            if (!DeviceMachineTypes.isWorkingMachine(nativeTile.getMetaTileEntity()))
                throw new IllegalArgumentException("Native device is unavailable");
            DeviceRegistryData registry = DeviceRegistryData.get(actor.worldObj);
            registry.register(
                key,
                actor.getUniqueID(),
                nativeTile.getMetaTileEntity()
                    .getLocalName(),
                nativeTile.getMetaTileID());
            entry = registry.bindLiveInstance(key, liveTile);
            if (entry == null) throw new IllegalArgumentException("Native device identity support unavailable");
            SourceKey source = new SourceKey(SourceKey.Kind.DIT, epoch, entry.sourceId.toString(), 1);
            if (sourceAccess.check(actor.getUniqueID(), key, source, true) != MonitorProfile.Status.LIVE)
                throw new IllegalArgumentException("Import requires loaded devices owned by you: " + key);
            sources.put(key, source);
        }
        MonitorProfile profile = create(actor.getUniqueID(), legacy, sources);
        profile.captureDitPresentation(held);
        if (held.hasDisplayName()) profile.readName(held.getDisplayName());
        store.copyForProfile(legacy, profile.id);
        bindHeld(actor, held, profile.id);
        return profile;
    }

    MonitorProfile create(UUID actor, UUID legacy, Map<String, SourceKey> sources) {
        if (actor == null || sources == null || sources.size() > MAX_SOURCES || !validSourceFamily(sources))
            throw new IllegalArgumentException("Profile/source limit");
        UUID previous = legacy == null ? null : migrations.get(legacy);
        if (previous != null) {
            MonitorProfile profile = requireProfile(previous);
            requireOwner(profile, actor);
            return profile;
        }
        int owned = 0;
        for (MonitorProfile profile : profiles.values()) if (profile.owner()
            .equals(actor)) owned++;
        if (profiles.size() >= MAX_PROFILES || protectedIds.size() >= MAX_PROFILES || owned >= MAX_OWNER_PROFILES)
            throw new IllegalArgumentException("Profile quota reached");
        MonitorProfile profile = new MonitorProfile(UUID.randomUUID(), actor, legacy, sources);
        profiles.put(profile.id, profile);
        protectedIds.add(profile.id);
        if (legacy != null) migrations.put(legacy, profile.id);
        markDirty();
        return profile;
    }

    public void recover(EntityPlayerMP actor, UUID profileId) {
        MonitorProfile profile = requireProfile(profileId);
        if (!canRead(actor.getUniqueID(), profile.id)) throw new IllegalArgumentException("No profile permission");
        ItemStack held = actor.getHeldItem();
        if (isPortable(profile)) {
            PortableProfileAdapter.restore(held, profile);
            com.miaokatze.gtswn.common.hud.MonitorBindings.synchronize(actor);
            ItemDeviceInfoTerminal.syncHeldTerminalSlot(actor);
        } else if (isQuantum(profile)) {
            Map<String, SourceKey> current = QuantumProfileAdapter.capture(actor, held, epoch);
            if (!current.equals(profile.sources()))
                throw new IllegalArgumentException("Bind a new quantum terminal to the authorized native source first");
            held.stackTagCompound.setString(NBT_PROFILE, profile.id.toString());
            actor.inventory.markDirty();
            ItemDeviceInfoTerminal.syncHeldTerminalSlot(actor);
        } else bindHeld(actor, requireTerminal(actor), profile.id);
    }

    /** Explicit escape from an obsolete fixed quantum profile, not a native authority or source migration. */
    public void releaseQuantum(EntityPlayerMP actor, UUID profileId) {
        releaseQuantum(actor.getUniqueID(), actor.getHeldItem(), profileId);
        actor.inventory.markDirty();
        ItemDeviceInfoTerminal.syncHeldTerminalSlot(actor);
    }

    void releaseQuantum(UUID actor, ItemStack held, UUID profileId) {
        MonitorProfile profile = requireProfile(profileId);
        requireOwner(profile, actor); // Source can be absent or migrated; readers cannot strip the ACL.
        if (!QuantumProfileAdapter.supports(held) || !profileId.equals(readProfileReference(held)))
            throw new IllegalArgumentException("Hold the quantum terminal referencing this profile");
        held.stackTagCompound.removeTag(NBT_PROFILE);
        // A later explicit import creates a new lineage. Never remove the old server migration mapping,
        // profile, history, ACL, or native pairing credentials; copies retain their existing restrictions.
        held.stackTagCompound.removeTag("MonitorImportId");
    }

    private static ItemStack requireTerminal(EntityPlayerMP actor) {
        ItemStack held = actor.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemDeviceInfoTerminal))
            throw new IllegalArgumentException("Hold a device information terminal");
        return held;
    }

    private void bindHeld(EntityPlayerMP actor, ItemStack held, UUID id) {
        if (held.stackTagCompound == null) held.stackTagCompound = new NBTTagCompound();
        held.stackTagCompound.setString(ItemDeviceInfoTerminal.NBT_UUID, id.toString());
        MonitorProfile profile = profiles.get(id);
        if (profile != null) profile.restoreDitPresentation(held);
        actor.inventory.markDirty();
        ItemDeviceInfoTerminal.syncHeldTerminalSlot(actor);
        DeviceTerminalDataStore.ensureActiveTerminal(actor.getUniqueID(), id);
        com.miaokatze.gtswn.common.device.DeviceTerminalRequestQueue.invalidate(actor);
    }

    public void rename(UUID actor, UUID id, String name) {
        MonitorProfile profile = requireProfile(id);
        requireOwner(profile, actor);
        long before = profile.revision;
        profile.rename(name);
        if (before != profile.revision) markDirty();
    }

    /** Called only after an authoritative DIT presentation action, never from client-supplied NBT. */
    public void saveDitPresentation(EntityPlayerMP actor, ItemStack actualStack) {
        if (actor == null || actualStack == null || !(actualStack.getItem() instanceof ItemDeviceInfoTerminal)) return;
        UUID id = ItemDeviceInfoTerminal.readTerminalId(actualStack);
        MonitorProfile profile = profiles.get(id);
        if (profile == null) return; // Legacy terminal behavior is unchanged.
        requireOwner(profile, actor.getUniqueID());
        long before = profile.revision;
        profile.captureDitPresentation(actualStack);
        if (before != profile.revision) markDirty();
    }

    public void share(UUID actor, UUID id, UUID reader) {
        MonitorProfile profile = requireProfile(id);
        requireOwner(profile, actor);
        if (reader == null || profile.readers()
            .size() >= MAX_READERS
            && !profile.readers()
                .contains(reader))
            throw new IllegalArgumentException("Reader quota reached");
        long before = profile.revision;
        profile.share(reader);
        if (before == profile.revision) return;
        authorizationChanged(id);
        markDirty();
    }

    public void revoke(UUID actor, UUID id, UUID reader) {
        MonitorProfile profile = requireProfile(id);
        requireOwner(profile, actor);
        long before = profile.revision;
        profile.revoke(reader);
        if (before == profile.revision) return;
        authorizationChanged(id);
        markDirty();
    }

    public void detachSource(UUID actor, UUID id, String locator) {
        MonitorProfile profile = profiles.get(id);
        if (profile == null) return;
        requireOwner(profile, actor);
        profile.detach(locator);
        authorizationChanged(id);
        markDirty();
    }

    private void authorizationChanged(UUID id) {
        if (world != null) DeviceTerminalDataStore.get(world)
            .bumpVersion(id);
        com.miaokatze.gtswn.common.device.DeviceTerminalRequestQueue.authorizationChanged(id);
        com.miaokatze.gtswn.network.PortableMonitorRequestQueue.invalidateProfile(id);
        com.miaokatze.gtswn.common.monitoring.web.ReadonlyWebService.revoke(id);
    }

    public void transfer(UUID actor, UUID id, UUID recipient) {
        MonitorProfile profile = requireProfile(id);
        requireOwner(profile, actor);
        if (recipient == null) throw new IllegalArgumentException("Missing new owner");
        // With no native source there is no recipient authority to verify. Allowing this would let one
        // player dump empty profiles onto arbitrary UUIDs and bypass the per-owner creation quota.
        if (profile.sources()
            .isEmpty()) throw new IllegalArgumentException("Empty profiles cannot be transferred");
        for (Map.Entry<String, SourceKey> source : profile.sources()
            .entrySet())
            if (sourceAccess.check(recipient, source.getKey(), source.getValue(), true) != MonitorProfile.Status.LIVE)
                throw new IllegalArgumentException("New owner must own all loaded sources");
        int owned = 0;
        for (MonitorProfile candidate : profiles.values()) if (candidate.owner()
            .equals(recipient)) owned++;
        if (!recipient.equals(actor) && owned >= MAX_OWNER_PROFILES)
            throw new IllegalArgumentException("Profile quota reached");
        profile.transfer(recipient);
        authorizationChanged(id);
        markDirty();
    }

    public void setWebEnabled(UUID actor, UUID id, boolean enabled) {
        MonitorProfile profile = requireProfile(id);
        requireOwner(profile, actor);
        if (enabled == profile.webEnabled) return;
        if (enabled && !authorizedSources(profile, false))
            throw new IllegalArgumentException("Source permission changed");
        profile.setWeb(enabled);
        if (!enabled) com.miaokatze.gtswn.common.monitoring.web.ReadonlyWebService.revoke(id);
        markDirty();
    }

    public void setOffline(UUID actor, UUID id, boolean enabled, int intervalTicks) {
        MonitorProfile profile = requireProfile(id);
        requireOwner(profile, actor);
        if (intervalTicks < 20 || intervalTicks > 72000)
            throw new IllegalArgumentException("Interval must be 20..72000 ticks");
        if (enabled && !profile.offline) {
            int total = 0, owned = 0;
            for (MonitorProfile candidate : profiles.values()) if (candidate.offline) {
                total++;
                if (candidate.owner()
                    .equals(actor)) owned++;
            }
            if (total >= MAX_JOBS || owned >= MAX_OWNER_JOBS)
                throw new IllegalArgumentException("Offline job quota reached");
            Set<SourceKey> demand = new LinkedHashSet<>(
                profile.sources()
                    .values());
            Set<SourceKey> ownedDemand = new LinkedHashSet<>(
                profile.sources()
                    .values());
            for (MonitorProfile candidate : profiles.values()) if (candidate.offline) {
                demand.addAll(
                    candidate.sources()
                        .values());
                if (candidate.owner()
                    .equals(actor))
                    ownedDemand.addAll(
                        candidate.sources()
                            .values());
            }
            if (ownedDemand.size() > MAX_OWNER_JOB_SOURCES)
                throw new IllegalArgumentException("Owner offline unique source quota reached (1024)");
            if (demand.size() > MAX_JOB_SOURCES)
                throw new IllegalArgumentException("Offline unique source quota reached (4096)");
            if (!authorizedSources(profile, true))
                throw new IllegalArgumentException("Offline activation requires loaded authorized sources");
        }
        // Never accelerate a user's requested cadence. Native samplers impose a minimum, not a replacement period.
        intervalTicks = Math.max(intervalTicks, nativeSampleInterval(profile));
        profile.setOffline(enabled, intervalTicks);
        markDirty();
    }

    /** Explicit persisted jobs only; no online player references, no forced dimension/chunk loading. */
    public void tickNonDeviceJobs(long now) {
        if (now % 20 != 0) return;
        for (MonitorProfile profile : profiles.values()) {
            if (!profile.offline || !(isPortable(profile) || isQuantum(profile)) || !authorizedSources(profile, true))
                continue;
            if (!jobDue(profile, now)) continue;
            for (Map.Entry<String, SourceKey> source : profile.sources()
                .entrySet()) {
                long sampled;
                if (source.getValue().kind == SourceKey.Kind.QUANTUM)
                    sampled = QuantumProfileAdapter.renewJob(profile.owner(), source.getKey(), source.getValue(), now);
                else {
                    PortableProfileAdapter.renew(source.getKey(), source.getValue(), true);
                    sampled = PortableProfileAdapter.sampleTick(source.getKey(), source.getValue());
                }
                recordNativeSample(profile, sampled, now);
            }
            profile.lastSampleTick = now;
            markDirty();
        }
    }

    static boolean jobDue(MonitorProfile profile, long now) {
        return profile.offline && (profile.lastSampleTick == Long.MIN_VALUE || now < profile.lastSampleTick
            || now - profile.lastSampleTick >= profile.intervalTicks);
    }

    static void recordNativeSample(MonitorProfile profile, long sampled, long now) {
        if (sampled <= 0 || sampled > now) return;
        boolean newSample = sampled > profile.sampleTime || profile.sampleTime > now;
        profile.sampleTime = profile.sampleTime > now ? sampled : Math.max(profile.sampleTime, sampled);
        profile.sampledThisSession = true;
        // Re-reading the same cached value must not undo an explicit job pause.
        if (newSample && profile.status == MonitorProfile.Status.JOB_PAUSED)
            profile.status = MonitorProfile.Status.CACHED;
    }

    private static int nativeSampleInterval(MonitorProfile profile) {
        int ticks = 100;
        if (!isPortable(profile) && !isQuantum(profile))
            return Math.max(20, com.miaokatze.gtswn.config.Config.deviceSampleIntervalSeconds * 20);
        for (SourceKey source : profile.sources()
            .values())
            if (source.kind == SourceKey.Kind.AE)
                ticks = Math.max(ticks, com.miaokatze.gtswn.config.Config.aeSampleInterval);
        return ticks;
    }

    /** Freshness is a projection of actual collector time, never of a read or job attempt. */
    static MonitorProfile.Status sampleStatus(MonitorProfile profile, long now, int nativeInterval) {
        return sampleStatus(profile, now, nativeInterval, profile.sampleTime);
    }

    static MonitorProfile.Status sampleStatus(MonitorProfile profile, long now, int nativeInterval, long sampled) {
        if (!profile.offline && profile.status == MonitorProfile.Status.JOB_PAUSED)
            return MonitorProfile.Status.JOB_PAUSED;
        long cadence = profile.offline ? Math.max(nativeInterval, profile.intervalTicks) : nativeInterval;
        boolean fresh = profile.sampledThisSession && sampled > 0
            && now >= sampled
            && now - sampled <= Math.max(200L, cadence * 2L);
        return fresh ? MonitorProfile.Status.LIVE : MonitorProfile.Status.CACHED;
    }

    /** Unknown or future samples make a mixed projection stale rather than borrowing a newer source's time. */
    static long oldestProjectionSample(long oldest, long sampled, long now) {
        return Math.min(oldest, sampled > 0 && sampled <= now ? sampled : 0);
    }

    public int sourceCount(UUID id) {
        MonitorProfile profile = profiles.get(id);
        return profile == null ? -1
            : profile.sources()
                .size();
    }

    public boolean hasWebConsent(UUID id) {
        MonitorProfile profile = profiles.get(id);
        return profile != null && profile.webEnabled;
    }

    /** Native authorization for the operator registry's fixed references; never activates observation. */
    public boolean canPublish(UUID id) {
        MonitorProfile profile = profiles.get(id);
        return profile != null && profile.webEnabled && authorizedSources(profile, false);
    }

    public com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot exportWebSnapshot(UUID id) {
        return exportWebSnapshot(id, 128);
    }

    /** Direct fixed-reference lookup; card budgets apply before allocating any raw history arrays. */
    public com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot exportWebSnapshot(UUID id, int maxCards) {
        if (world == null || id == null || !canPublish(id)) return null;
        MonitorProfile profile = profiles.get(id);
        int limit = Math.max(0, Math.min(128, maxCards));
        DeviceTerminalDataStore.TerminalData data = DeviceTerminalDataStore.get(world)
            .getTerminal(profile.id);
        List<com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot.Card> cards = new ArrayList<>();
        int totalCards = 0;
        long now = world.getTotalWorldTime();
        long oldestSample = Long.MAX_VALUE;
        if (data != null) for (String locator : profile.sources()
            .keySet()) {
                DeviceTerminalDataStore.MachineRecord record = data.records.get(locator);
                if (record == null) {
                    oldestSample = 0;
                    continue;
                }
                oldestSample = oldestProjectionSample(oldestSample, record.lastSampleTick, now);
                totalCards += 2;
                if (cards.size() >= limit) continue;
                String name = record.displayName.isEmpty() ? record.name : record.displayName;
                boolean known = record.evidenceKind <= 1;
                cards.add(
                    new com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot.Card(
                        name + " input",
                        known ? Long.toString(record.instIn) : "unknown",
                        "EU/t",
                        rawHistory(record.fifoIn, record.idx, record.count)));
                if (cards.size() >= limit) continue;
                cards.add(
                    new com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot.Card(
                        name + " output",
                        known ? Long.toString(record.instOut) : "unknown",
                        "EU/t",
                        rawHistory(record.fifoOut, record.idx, record.count)));
            }
        for (Map.Entry<String, SourceKey> source : profile.sources()
            .entrySet()) {
            if (source.getValue().kind == SourceKey.Kind.EU || source.getValue().kind == SourceKey.Kind.AE) {
                totalCards += PortableProfileAdapter.cardCount(source.getKey(), source.getValue());
                if (cards.size() < limit) cards
                    .addAll(PortableProfileAdapter.export(source.getKey(), source.getValue(), limit - cards.size()));
                long sampled = PortableProfileAdapter.sampleTick(source.getKey(), source.getValue());
                recordNativeSample(profile, sampled, now);
                oldestSample = oldestProjectionSample(oldestSample, sampled, now);
            } else if (source.getValue().kind == SourceKey.Kind.QUANTUM) {
                List<com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot.Card> quantum = QuantumProfileAdapter
                    .export(profile.owner(), source.getKey(), source.getValue());
                totalCards += quantum.size();
                if (cards.size() < limit)
                    cards.addAll(quantum.subList(0, Math.min(limit - cards.size(), quantum.size())));
                long sampled = QuantumProfileAdapter.sampleTick(profile.owner(), source.getKey(), source.getValue());
                recordNativeSample(profile, sampled, now);
                oldestSample = oldestProjectionSample(oldestSample, sampled, now);
            }
        }
        long projectionSample = oldestSample == Long.MAX_VALUE ? 0 : oldestSample;
        MonitorProfile.Status status = profile.status;
        if (status == MonitorProfile.Status.LIVE || status == MonitorProfile.Status.CACHED
            || status == MonitorProfile.Status.JOB_PAUSED)
            status = sampleStatus(profile, now, nativeSampleInterval(profile), projectionSample);
        return new com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot(
            profile.id,
            profile.name(),
            profile.revision + (data == null ? 0 : data.version),
            status.name(),
            projectionSample,
            cards,
            Math.max(0, totalCards - cards.size()));
    }

    /** Compatibility projection; the operator registry uses bounded direct ID lookups instead. */
    public List<com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot> exportWebSnapshots(int limit) {
        List<com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot> snapshots = new ArrayList<>();
        int count = Math
            .max(0, Math.min(limit, com.miaokatze.gtswn.common.monitoring.web.ReadonlyWebService.maxDashboards()));
        for (MonitorProfile profile : profiles.values()) {
            if (snapshots.size() >= count) break;
            com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot snapshot = exportWebSnapshot(profile.id);
            if (snapshot != null) snapshots.add(snapshot);
        }
        return snapshots;
    }

    static double[] rawHistory(double[] fifo, int index, int count) {
        int size = Math.max(0, Math.min(count, fifo.length));
        double[] history = new double[size];
        if (fifo.length == 0) return history;
        int start = Math.floorMod(index - size, fifo.length);
        for (int i = 0; i < size; i++) {
            double value = fifo[(start + i) % fifo.length];
            history[i] = Double.isNaN(value) || Double.isInfinite(value) ? Double.NaN : value;
        }
        return history;
    }

    /** One source is sampled once by the existing deduplicating scheduler, including online + offline overlap. */
    public List<Map.Entry<UUID, DeviceTerminalDataStore.TerminalData>> samplingTerminals(DeviceTerminalDataStore store,
        long now) {
        Map<UUID, DeviceTerminalDataStore.TerminalData> selected = new LinkedHashMap<>();
        for (Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> entry : store.getActiveTerminals()) {
            MonitorProfile profile = profiles.get(entry.getKey());
            if (profile == null && !entry.getValue().profileManaged && !protectedIds.contains(entry.getKey()))
                selected.put(entry.getKey(), entry.getValue());
            else if (profile == null) continue;
            else for (UUID holder : DeviceTerminalDataStore.holders(profile.id)) if (canRead(holder, profile.id)) {
                selected.put(profile.id, entry.getValue());
                break;
            }
        }
        for (MonitorProfile profile : profiles.values())
            if (profile.offline && !isPortable(profile) && !isQuantum(profile) && authorizedSources(profile, true)) {
                if (jobDue(profile, now)) {
                    DeviceTerminalDataStore.TerminalData data = store.getTerminal(profile.id);
                    if (data != null) selected.put(profile.id, data);
                    profile.lastSampleTick = now;
                }
            }
        return new ArrayList<>(selected.entrySet());
    }

    /** Recheck after queued work: revocation between refill and drain must stop observation. */
    public boolean maySample(UUID id) {
        MonitorProfile profile = profiles.get(id);
        if (profile == null) return legacyAllowed(id) && DeviceTerminalDataStore.isTerminalActive(id);
        if (!authorizedSources(profile, true)) return false;
        if (profile.offline) return true;
        for (UUID holder : DeviceTerminalDataStore.holders(id)) if (profile.mayRead(holder)) return true;
        return false;
    }

    public void sampled(UUID id, long now) {
        MonitorProfile profile = profiles.get(id);
        if (profile != null) {
            profile.sampleTime = now;
            profile.sampledThisSession = true;
            profile.status = MonitorProfile.Status.LIVE;
            markDirty();
        }
    }

    static boolean validSourceFamily(Map<String, SourceKey> sources) {
        int family = 0;
        for (SourceKey source : sources.values()) {
            if (source == null) return false;
            int current = source.kind == SourceKey.Kind.DIT ? 1 : source.kind == SourceKey.Kind.QUANTUM ? 2 : 3;
            if (family != 0 && current != family) return false;
            family = current;
        }
        return true;
    }

    static boolean fixedDeviceScopeMatches(DeviceTerminalDataStore.TerminalData data, Map<String, SourceKey> sources) {
        for (SourceKey source : sources.values()) if (source.kind != SourceKey.Kind.DIT) return false;
        return data != null && data.profileManaged
            && data.boundKeys.equals(sources.keySet())
            && data.records.keySet()
                .equals(sources.keySet());
    }

    private boolean authorizedSources(MonitorProfile profile, boolean loadedRequired) {
        if (world != null) {
            DeviceTerminalDataStore.TerminalData data = DeviceTerminalDataStore.get(world)
                .getTerminal(profile.id);
            boolean deviceProfile = data != null && data.profileManaged || !isPortable(profile) && !isQuantum(profile);
            if (deviceProfile && !fixedDeviceScopeMatches(data, profile.sources())) {
                boolean changed = profile.status != MonitorProfile.Status.NO_PERMISSION;
                profile.status = MonitorProfile.Status.NO_PERMISSION;
                if (changed) {
                    markDirty();
                    authorizationChanged(profile.id);
                }
                return false; // Torn/missing profile metadata never widens the snapshot's fixed source scope.
            }
        }
        MonitorProfile.Status status = MonitorProfile.Status.LIVE;
        for (Map.Entry<String, SourceKey> source : profile.sources()
            .entrySet()) {
            MonitorProfile.Status current;
            try {
                current = sourceAccess.check(profile.owner(), source.getKey(), source.getValue(), loadedRequired);
            } catch (RuntimeException unavailable) {
                current = MonitorProfile.Status.NO_PERMISSION;
            }
            if (current == MonitorProfile.Status.NO_PERMISSION || current == MonitorProfile.Status.SOURCE_REPLACED) {
                boolean changed = profile.status != current;
                profile.status = current;
                if (changed) {
                    markDirty();
                    authorizationChanged(profile.id);
                }
                return false;
            }
            if (current != MonitorProfile.Status.LIVE) status = current;
            if (current == MonitorProfile.Status.SOURCE_UNLOADED && source.getValue().kind != SourceKey.Kind.DIT
                || current == MonitorProfile.Status.JOB_PAUSED) {
                boolean changed = profile.status != current;
                profile.status = current;
                if (changed) {
                    markDirty();
                    authorizationChanged(profile.id);
                }
                return false; // Unloaded AE/quantum native security cannot be revalidated from stale item data.
            }
        }
        MonitorProfile.Status reported = status == MonitorProfile.Status.LIVE
            ? sampleStatus(
                profile,
                world == null ? Long.MIN_VALUE : world.getTotalWorldTime(),
                nativeSampleInterval(profile))
            : status;
        if (profile.status != reported) {
            profile.status = reported;
            markDirty();
        }
        return status == MonitorProfile.Status.LIVE
            || !loadedRequired && status == MonitorProfile.Status.SOURCE_UNLOADED;
    }

    private MonitorProfile.Status checkNativeSource(UUID owner, String locator, SourceKey source,
        boolean loadedRequired) {
        if (world != null && (source.kind == SourceKey.Kind.EU || source.kind == SourceKey.Kind.AE))
            return PortableProfileAdapter.check(owner, locator, source, epoch);
        if (world != null && source.kind == SourceKey.Kind.QUANTUM)
            return QuantumProfileAdapter.check(owner, locator, source, epoch, loadedRequired);
        if (world == null || source.kind != SourceKey.Kind.DIT || !epoch.equals(source.epoch))
            return MonitorProfile.Status.SOURCE_REPLACED;
        DeviceRegistryData.DeviceEntry entry = DeviceRegistryData.get(world)
            .getEntry(locator);
        if (entry == null || !entry.sourceId.toString()
            .equals(source.nativeId)) return MonitorProfile.Status.SOURCE_REPLACED;
        int[] pos = KeyFormat.parse(locator);
        if (pos == null) return MonitorProfile.Status.SOURCE_REPLACED;
        World loaded = DimensionManager.getWorld(pos[0]);
        if (loaded == null || loaded.getChunkProvider() == null
            || !loaded.getChunkProvider()
                .chunkExists(pos[1] >> 4, pos[3] >> 4))
            return owner.equals(entry.owner) ? MonitorProfile.Status.SOURCE_UNLOADED
                : MonitorProfile.Status.NO_PERMISSION;
        TileEntity tile = loaded.getTileEntity(pos[1], pos[2], pos[3]);
        if (!(tile instanceof IGregTechTileEntity) || tile.isInvalid()) return MonitorProfile.Status.SOURCE_REPLACED;
        entry = DeviceRegistryData.get(world)
            .bindLiveInstance(locator, tile);
        if (entry == null || !entry.sourceId.toString()
            .equals(source.nativeId)) return MonitorProfile.Status.SOURCE_REPLACED;
        IGregTechTileEntity nativeTile = (IGregTechTileEntity) tile;
        if (!DeviceMachineTypes.isWorkingMachine(nativeTile.getMetaTileEntity())
            || entry.metaTileId != nativeTile.getMetaTileID()) return MonitorProfile.Status.SOURCE_REPLACED;
        if (!owner.equals(nativeTile.getOwnerUuid())) return MonitorProfile.Status.NO_PERMISSION;
        if (!owner.equals(entry.owner)) DeviceRegistryData.get(world)
            .register(locator, owner, entry.localName, entry.metaTileId);
        return MonitorProfile.Status.LIVE;
    }

    private MonitorProfile requireProfile(UUID id) {
        MonitorProfile profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("Unknown profile");
        return profile;
    }

    private static void requireOwner(MonitorProfile profile, UUID actor) {
        if (!profile.mayModify(actor)) throw new IllegalArgumentException("Only the profile owner may do this");
    }

    void sourceAccessForTest(SourceAccess sourceAccess) {
        this.sourceAccess = sourceAccess;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setInteger("format", 1);
        tag.setString("epoch", epoch.toString());
        NBTTagList list = new NBTTagList();
        for (MonitorProfile profile : profiles.values()) {
            NBTTagCompound item = new NBTTagCompound();
            item.setString("id", profile.id.toString());
            item.setString(
                "owner",
                profile.owner()
                    .toString());
            profile.writePresentation(item);
            if (profile.legacyId != null) item.setString("legacy", profile.legacyId.toString());
            item.setLong("revision", profile.revision);
            item.setBoolean("offline", profile.offline);
            item.setBoolean("web", profile.webEnabled);
            item.setInteger("interval", profile.intervalTicks);
            item.setLong("sampleTime", profile.sampleTime);
            item.setString("status", profile.status.name());
            item.setInteger("retainedPoints", profile.historyPoints());
            NBTTagList readers = new NBTTagList();
            for (UUID reader : profile.readers()) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setString("id", reader.toString());
                readers.appendTag(entry);
            }
            item.setTag("readers", readers);
            NBTTagList sources = new NBTTagList();
            for (Map.Entry<String, SourceKey> source : profile.sources()
                .entrySet()) {
                NBTTagCompound entry = source.getValue()
                    .write();
                entry.setString("locator", source.getKey());
                sources.appendTag(entry);
            }
            item.setTag("sources", sources);
            list.appendTag(item);
        }
        tag.setTag("profiles", list);
        NBTTagList reserved = new NBTTagList();
        for (UUID id : protectedIds) {
            NBTTagCompound item = new NBTTagCompound();
            item.setString("id", id.toString());
            reserved.appendTag(item);
        }
        tag.setTag("protected", reserved);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        profiles.clear();
        migrations.clear();
        protectedIds.clear();
        NBTTagList reserved = tag.getTagList("protected", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < Math.min(MAX_PROFILES, reserved.tagCount()); i++) {
            try {
                protectedIds.add(
                    UUID.fromString(
                        reserved.getCompoundTagAt(i)
                            .getString("id")));
            } catch (IllegalArgumentException invalid) { /* malformed reserved ID has no representable capability */ }
        }
        try {
            epoch = UUID.fromString(tag.getString("epoch"));
        } catch (IllegalArgumentException invalid) {
            epoch = UUID.randomUUID();
        }
        NBTTagList list = tag.getTagList("profiles", Constants.NBT.TAG_COMPOUND);
        int jobs = 0;
        Map<UUID, Integer> ownerJobs = new LinkedHashMap<>();
        Set<SourceKey> jobSources = new LinkedHashSet<>();
        Map<UUID, Set<SourceKey>> ownerSources = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(MAX_PROFILES, list.tagCount()); i++) {
            try {
                NBTTagCompound item = list.getCompoundTagAt(i);
                UUID id = UUID.fromString(item.getString("id"));
                protectedIds.add(id);
                if (tag.getInteger("format") != 1 || !item.hasKey("sources", Constants.NBT.TAG_LIST)) continue;
                UUID owner = UUID.fromString(item.getString("owner"));
                UUID legacy = item.hasKey("legacy") ? UUID.fromString(item.getString("legacy")) : null;
                if (profiles.containsKey(id) || legacy != null && migrations.containsKey(legacy)) continue;
                Map<String, SourceKey> sources = new LinkedHashMap<>();
                NBTTagList rawSources = (NBTTagList) item.getTag("sources");
                if (rawSources.tagCount() > 0 && rawSources.func_150303_d() != Constants.NBT.TAG_COMPOUND) continue;
                NBTTagList sourceTags = item.getTagList("sources", Constants.NBT.TAG_COMPOUND);
                if (sourceTags.tagCount() > MAX_SOURCES) continue;
                for (int j = 0; j < sourceTags.tagCount(); j++) {
                    NBTTagCompound entry = sourceTags.getCompoundTagAt(j);
                    String locator = entry.getString("locator");
                    if (locator.isEmpty() || locator.length() > 160) throw new IllegalArgumentException("Bad locator");
                    if (sources.containsKey(locator)) throw new IllegalArgumentException("Duplicate source locator");
                    SourceKey source = SourceKey.read(entry);
                    if (source.kind == SourceKey.Kind.DIT && KeyFormat.parse(locator) == null)
                        throw new IllegalArgumentException("Invalid device locator");
                    sources.put(locator, source);
                }
                if (!validSourceFamily(sources)) continue;
                MonitorProfile profile = new MonitorProfile(id, owner, legacy, sources);
                profile.readPresentation(item);
                NBTTagList readers = item.getTagList("readers", Constants.NBT.TAG_COMPOUND);
                for (int j = 0; j < Math.min(MAX_READERS, readers.tagCount()); j++) profile.share(
                    UUID.fromString(
                        readers.getCompoundTagAt(j)
                            .getString("id")));
                profile.revision = Math.max(1, item.getLong("revision"));
                profile.webEnabled = item.getBoolean("web");
                profile.intervalTicks = Math.max(20, Math.min(72000, item.getInteger("interval")));
                profile.sampleTime = Math.max(0, item.getLong("sampleTime"));
                try {
                    profile.status = MonitorProfile.Status.valueOf(item.getString("status"));
                } catch (IllegalArgumentException invalidStatus) {
                    profile.status = MonitorProfile.Status.CACHED;
                }
                if (profile.status == MonitorProfile.Status.LIVE) profile.status = MonitorProfile.Status.CACHED;
                profile.offline = item.getBoolean("offline") && jobs < MAX_JOBS
                    && ownerJobs.getOrDefault(owner, 0) < MAX_OWNER_JOBS;
                if (profile.offline) {
                    Set<SourceKey> demand = new LinkedHashSet<>(jobSources);
                    demand.addAll(sources.values());
                    Set<SourceKey> ownedDemand = new LinkedHashSet<>(
                        ownerSources.getOrDefault(owner, Collections.emptySet()));
                    ownedDemand.addAll(sources.values());
                    if (demand.size() > MAX_JOB_SOURCES || ownedDemand.size() > MAX_OWNER_JOB_SOURCES)
                        profile.offline = false;
                    else {
                        jobSources = demand;
                        ownerSources.put(owner, ownedDemand);
                        jobs++;
                        ownerJobs.put(owner, ownerJobs.getOrDefault(owner, 0) + 1);
                    }
                }
                profiles.put(id, profile);
                if (legacy != null) migrations.put(legacy, id);
            } catch (IllegalArgumentException invalid) {
                // A malformed profile is not made public or reinterpreted as a different owner.
            }
        }
    }
}
