// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.monitoring.ae;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.util.SavedDataUtil;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.miaokatzecore.api.io.AtomicFilePublisher;

/**
 * Durable backup of committed AE native storage provenance. AE removes a grid's own storage payload
 * when its final node unloads, while native node NBT retains that storage ID. No player or coordinate
 * can establish this mapping. Retired identities stay retired across every storage alias and restart.
 */
public final class AEGridIdentityData extends WorldSavedData {

    public static final String DATA_NAME = "gtswn_ae_grid_identities";
    private static AEGridIdentityData session;
    private static boolean stopping;
    private static boolean finalBatchWritten;
    private static boolean finalSaveCompleted;
    private final Map<Long, UUID> storages = new LinkedHashMap<>();
    private final Set<UUID> retired = new LinkedHashSet<>();
    private final Map<UUID, GridIdentityState.Origin> origins = new LinkedHashMap<>();
    private final Map<Long, UUID> changedStorages = new LinkedHashMap<>();
    private final Set<UUID> newRetirements = new LinkedHashSet<>();
    private AEGridIdentityJournal journal;
    private Path dataDirectory;
    private boolean unavailable;
    private boolean requireBackup;
    private boolean exportOnShutdown;

    public AEGridIdentityData(String name) {
        super(name);
    }

    static AEGridIdentityData get(World requested) {
        if (requested == null || requested.isRemote) throw new IllegalStateException("Server world required");
        if (!(requested instanceof WorldServer)) {
            return SavedDataUtil.loadOrCreate(requested, DATA_NAME, AEGridIdentityData.class, AEGridIdentityData::new);
        }
        if (session == null) {
            WorldServer overworld = DimensionManager.getWorld(0);
            File directory = overworld == null ? DimensionManager.getCurrentSaveRootDirectory()
                : overworld.getChunkSaveLocation();
            session = new AEGridIdentityData(DATA_NAME);
            if (directory == null) {
                session.unavailable = true;
                GTSimpleWirelessNetwork.LOG
                    .error("AE grid identity storage is unavailable: the overworld save path is unknown");
            } else {
                session.openJournal(
                    directory.toPath()
                        .resolve("data"));
            }
        }
        return session;
    }

    void openJournal(Path directory) {
        dataDirectory = directory;
        journal = new AEGridIdentityJournal(directory.resolve(DATA_NAME + "_journal"));
        try {
            if (journal.needsLegacy()) {
                Path legacy = directory.resolve(DATA_NAME + ".dat");
                if (!Files.notExists(legacy)) {
                    try (var file = Files.newInputStream(legacy)) {
                        NBTTagCompound old = CompressedStreamTools.readCompressed(file)
                            .getCompoundTag("data");
                        if (old.getInteger("format") != 1)
                            throw new IOException("Unrecognized legacy AE grid identity format");
                        readFromNBT(old);
                    }
                }
            }
            AEGridIdentityJournal.State state = journal
                .open(new AEGridIdentityJournal.State(0, storages, retired, false));
            storages.clear();
            storages.putAll(state.storages);
            retired.clear();
            retired.addAll(state.retired);
            requireBackup = state.requireBackup;
        } catch (IOException | RuntimeException failure) {
            unavailable = true;
            GTSimpleWirelessNetwork.LOG.error("AE grid identity recovery is paused for {}", directory, failure);
        }
    }

    boolean isAvailable() {
        return !unavailable;
    }

    void flushPending() throws IOException {
        journal.save(changedStorages, newRetirements);
        changedStorages.clear();
        newRetirements.clear();
    }

    public static void beforeWorldSave(WorldServer world) {
        if (session == null || world.provider.dimensionId != 0 || !session.isAvailable()) return;
        if (stopping) finalBatchWritten = finalSaveCompleted = false;
        try {
            session.flushPending();
            if (stopping) finalBatchWritten = true;
        } catch (IOException failure) {
            GTSimpleWirelessNetwork.LOG.error("AE grid identity batch was retained for retry", failure);
        }
    }

    public static void afterWorldSave(WorldServer world) {
        if (stopping && world.provider.dimensionId == 0 && finalBatchWritten) finalSaveCompleted = true;
    }

    public static void beginShutdown() {
        stopping = true;
    }

    public static void endSession() {
        try {
            if (session != null && session.isAvailable()) {
                boolean clean = stopping && finalSaveCompleted;
                session.closeJournal(clean);
                if (clean && session.exportOnShutdown) {
                    GTSimpleWirelessNetwork.LOG
                        .info("Exported AE grid identities for downgrade to {}", session.exportLegacy());
                }
            }
        } catch (IOException failure) {
            GTSimpleWirelessNetwork.LOG.error("AE grid identity save session did not close cleanly", failure);
        } finally {
            session = null;
            stopping = false;
            finalBatchWritten = false;
            finalSaveCompleted = false;
        }
    }

    void closeJournal(boolean clean) throws IOException {
        boolean saved = false;
        try {
            flushPending();
            saved = true;
        } finally {
            journal.close(clean && saved);
        }
    }

    public static void requestLegacyExport(WorldServer world) throws IOException {
        AEGridIdentityData data = get(world);
        if (!data.isAvailable() || data.requireBackup) {
            throw new IOException("Legacy export cannot represent the current AE identity recovery state");
        }
        data.exportOnShutdown = true;
    }

    Path exportLegacy() throws IOException {
        if (unavailable || requireBackup) throw new IOException("Legacy export would lose AE identity recovery state");
        NBTTagCompound body = new NBTTagCompound();
        writeToNBT(body);
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("data", body);
        var bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(root, bytes);
        Path target = dataDirectory.resolve(DATA_NAME + "_legacy_export.dat");
        AtomicFilePublisher.publishAtomic(target, bytes.toByteArray());
        return target;
    }

    GridIdentityState.Origin restore(long storage, NBTTagCompound nativeData, GridIdentityState.Origin transfer) {
        GridIdentityState.Origin nativeOrigin = MonitorIdentityGridCache.read(nativeData);
        if (unavailable) {
            nativeOrigin.retire();
            return nativeOrigin;
        }
        UUID backup = storages.get(storage);
        boolean tagged = nativeData.hasKey(MonitorIdentityGridCache.TAG);
        if (tagged && nativeOrigin.id == null) {
            retire(backup);
            return nativeOrigin; // Malformed/pending native provenance must not inherit a backup's consent.
        }
        if (backup != null && nativeOrigin.id != null && !backup.equals(nativeOrigin.id)) {
            retire(backup);
            retire(nativeOrigin.id);
            nativeOrigin.retire();
            return nativeOrigin;
        }
        UUID id = tagged ? nativeOrigin.id : backup;
        if (id == null && transfer != null) id = transfer.id;
        if (transfer != null && (transfer.retired || transfer.id != null && id != null && !transfer.id.equals(id))) {
            retire(id);
            retire(transfer.id);
            transfer.retire();
            return transfer;
        }
        if (id == null) return transfer == null ? nativeOrigin : transfer;
        if (requireBackup && backup == null && (transfer == null || origins.get(id) != transfer)) {
            retire(id);
            nativeOrigin.retire();
            return nativeOrigin;
        }
        GridIdentityState.Origin origin = transfer != null && id.equals(transfer.id) ? transfer : origins.get(id);
        if (origin == null) origin = new GridIdentityState.Origin(id);
        return bind(origin);
    }

    /** Only called after cache topology has settled, never by a monitoring read. */
    boolean commit(long storage, GridIdentityState.Origin origin) {
        if (unavailable || storage < 0 || origin.id == null || bind(origin).retired) return false;
        UUID previous = storages.get(storage);
        if (!origin.id.equals(previous)) {
            // A genuinely new native grid reusing a number cannot silently acquire an old source.
            retire(previous);
            storages.put(storage, origin.id);
            if (journal == null) markDirty();
            else changedStorages.put(storage, origin.id);
        }
        return true;
    }

    private GridIdentityState.Origin bind(GridIdentityState.Origin origin) {
        GridIdentityState.Origin existing = origins.get(origin.id);
        if (existing != null && existing != origin && existing.liveGrids > 0 && origin.liveGrids > 0) {
            retire(origin.id); // Conflicting live provenance is a boundary, never a guessed reunion.
            origin.retire();
        }
        origins.put(origin.id, origin);
        origin.persistRetirement(this::retire);
        if (retired.contains(origin.id)) origin.retire();
        return origin;
    }

    private void retire(UUID id) {
        if (id == null || unavailable) return;
        if (retired.add(id)) {
            if (journal == null) markDirty();
            else newRetirements.add(id);
        }
        GridIdentityState.Origin origin = origins.get(id);
        if (origin != null) origin.retire();
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        // The optional SafeWriteNBT mixin lets Hodgepodge hand this tree to its IO thread as-is.
        // Keep every mutable tag local to this save, even when the underlying identities are unchanged.
        tag.setInteger("format", 1);
        NBTTagList records = new NBTTagList();
        for (Map.Entry<Long, UUID> entry : storages.entrySet()) {
            NBTTagCompound record = new NBTTagCompound();
            record.setLong("storage", entry.getKey());
            record.setString(
                "identity",
                entry.getValue()
                    .toString());
            records.appendTag(record);
        }
        tag.setTag("storages", records);
        NBTTagList tombstones = new NBTTagList();
        for (UUID id : retired) {
            NBTTagCompound record = new NBTTagCompound();
            record.setString("identity", id.toString());
            tombstones.appendTag(record);
        }
        tag.setTag("retired", tombstones);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        storages.clear();
        retired.clear();
        origins.clear();
        if (tag.getInteger("format") != 1) return;
        NBTTagList tombstones = tag.getTagList("retired", 10);
        for (int i = 0; i < tombstones.tagCount(); i++) {
            try {
                retired.add(
                    UUID.fromString(
                        tombstones.getCompoundTagAt(i)
                            .getString("identity")));
            } catch (IllegalArgumentException invalid) { /* Unrepresentable identity has no capability. */ }
        }
        NBTTagList records = tag.getTagList("storages", 10);
        for (int i = 0; i < records.tagCount(); i++) {
            NBTTagCompound record = records.getCompoundTagAt(i);
            long storage = record.getLong("storage");
            if (storage < 0 || !record.hasKey("storage", 4)) continue;
            try {
                UUID id = UUID.fromString(record.getString("identity"));
                UUID previous = storages.put(storage, id);
                if (previous != null && !previous.equals(id)) {
                    retired.add(previous);
                    retired.add(id);
                }
            } catch (IllegalArgumentException invalid) { /* Never guess a damaged identity. */ }
        }
    }
}
