package com.miaokatze.gtswn.common.monitoring.ae;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

import com.miaokatze.gtswn.common.util.SavedDataUtil;

/**
 * Durable backup of committed AE native storage provenance. AE removes a grid's own storage payload
 * when its final node unloads, while native node NBT retains that storage ID. No player or coordinate
 * can establish this mapping. Retired identities stay retired across every storage alias and restart.
 */
public final class AEGridIdentityData extends WorldSavedData {

    public static final String DATA_NAME = "gtswn_ae_grid_identities";
    private final Map<Long, UUID> storages = new LinkedHashMap<>();
    private final Set<UUID> retired = new LinkedHashSet<>();
    private final Map<UUID, GridIdentityState.Origin> origins = new LinkedHashMap<>();

    public AEGridIdentityData(String name) {
        super(name);
    }

    static AEGridIdentityData get(World requested) {
        MinecraftServer server = MinecraftServer.getServer();
        World root = server == null ? requested : server.worldServerForDimension(0);
        if (root == null) root = requested;
        if (root == null || root.isRemote) throw new IllegalStateException("Server world required");
        return SavedDataUtil.loadOrCreate(root, DATA_NAME, AEGridIdentityData.class, AEGridIdentityData::new);
    }

    GridIdentityState.Origin restore(long storage, NBTTagCompound nativeData, GridIdentityState.Origin transfer) {
        GridIdentityState.Origin nativeOrigin = MonitorIdentityGridCache.read(nativeData);
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
        GridIdentityState.Origin origin = transfer != null && id.equals(transfer.id) ? transfer : origins.get(id);
        if (origin == null) origin = new GridIdentityState.Origin(id);
        return bind(origin);
    }

    /** Only called after cache topology has settled, never by a monitoring read. */
    boolean commit(long storage, GridIdentityState.Origin origin) {
        if (storage < 0 || origin.id == null || bind(origin).retired) return false;
        UUID previous = storages.get(storage);
        if (!origin.id.equals(previous)) {
            // A genuinely new native grid reusing a number cannot silently acquire an old source.
            retire(previous);
            storages.put(storage, origin.id);
            markDirty();
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
        if (id == null) return;
        if (retired.add(id)) markDirty();
        GridIdentityState.Origin origin = origins.get(id);
        if (origin != null) origin.retire();
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
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
