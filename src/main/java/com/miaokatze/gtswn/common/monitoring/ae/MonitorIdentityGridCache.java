package com.miaokatze.gtswn.common.monitoring.ae;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.nbt.NBTTagCompound;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridStorage;
import appeng.api.networking.pathing.IPathingGrid;

/**
 * AE-native persistence adapter. AE's IGrid.getId() is session-random and is deliberately unused.
 * Storage callbacks carry an O(1) shared origin during boot reconstruction; no ancestry list or
 * coordinate mapping is maintained. Live node migrations use the native synchronous
 * removeNode-to-addNode callback pair; plain removals never revoke a surviving grid.
 */
public final class MonitorIdentityGridCache implements IMonitorIdentityCache {

    static final String TAG = "gtswnMonitorIdentity";
    // Weak storage keys never keep discarded native grids/storage alive. Each storage has one origin,
    // and each cache holds one origin; cable additions do not create an identity/lineage chain.
    private static final Map<IGridStorage, GridIdentityState.Origin> TRANSFERS = new WeakHashMap<>();
    private static final int TRANSFER_LIMIT = 4096;
    private static final Map<IGridNode, WeakReference<MonitorIdentityGridCache>> REMOVALS = new WeakHashMap<>();
    private static long safetyGeneration;
    private long observedGeneration = safetyGeneration;
    private final IGrid grid;
    private final GridIdentityState state = new GridIdentityState();
    private boolean server;

    public MonitorIdentityGridCache(IGrid grid) {
        this.grid = grid;
    }

    /** Server-stop lifecycle only; durable identities remain in native AE storage. */
    public static void clearSession() {
        TRANSFERS.clear();
        REMOVALS.clear();
        safetyGeneration++;
    }

    private void observeGeneration() {
        if (observedGeneration != safetyGeneration) {
            state.invalidate();
            observedGeneration = safetyGeneration;
        }
    }

    @Override
    public UUID monitoringIdentity() {
        return server && observedGeneration == safetyGeneration ? state.current(booting()) : null;
    }

    @Override
    public void onUpdateTick() {
        if (!server) return;
        // Native transfers are synchronous, so no unmatched removal can cross an update tick.
        REMOVALS.clear();
        observeGeneration();
        if (server) state.update(booting());
    }

    private boolean booting() {
        IPathingGrid pathing = grid == null ? null : grid.getCache(IPathingGrid.class);
        return pathing == null || pathing.isNetworkBooting();
    }

    @Override
    public void addNode(IGridNode node, IGridHost host) {
        if (node != null && node.getWorld() != null && !node.getWorld().isRemote) server = true;
        observeGeneration();
        WeakReference<MonitorIdentityGridCache> previous = node == null ? null : REMOVALS.remove(node);
        MonitorIdentityGridCache source = previous == null ? null : previous.get();
        if (source != null && source != this) state.join(source.state.transfer());
        state.addNode();
    }

    @Override
    public void removeNode(IGridNode node, IGridHost host) {
        if (!server) {
            state.removeNode();
            return;
        }
        // A bounded weak table also handles nested third-party cache callbacks. Overflow
        // revokes publication globally rather than silently discarding transfer provenance.
        if (REMOVALS.size() >= TRANSFER_LIMIT) {
            REMOVALS.clear();
            safetyGeneration++;
        }
        observeGeneration();
        if (node != null) REMOVALS.put(node, new WeakReference<>(this));
        state.removeNode();
    }

    @Override
    public void onSplit(IGridStorage storage) {
        if (storage != null) {
            GridIdentityState.Origin origin = state.split();
            write(storage, origin);
        }
    }

    @Override
    public void onJoin(IGridStorage storage) {
        if (storage == null) return;
        GridIdentityState.Origin origin = TRANSFERS.get(storage);
        if (origin == null) {
            origin = read(storage.dataObject());
            TRANSFERS.put(storage, origin);
        }
        state.join(origin);
    }

    @Override
    public void populateGridStorage(IGridStorage storage) {
        transfer(storage);
    }

    private void transfer(IGridStorage storage) {
        if (storage == null) return;
        write(storage, state.transfer());
    }

    private void write(IGridStorage storage, GridIdentityState.Origin origin) {
        UUID saved = state.saved();
        TRANSFERS.put(storage, origin);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("version", 1);
        if (saved != null) tag.setString("id", saved.toString());
        storage.dataObject()
            .setTag(TAG, tag);
    }

    static GridIdentityState.Origin read(NBTTagCompound data) {
        if (!data.hasKey(TAG)) return new GridIdentityState.Origin(null);
        NBTTagCompound tag = data.getCompoundTag(TAG);
        String text = tag.getString("id");
        UUID id = null;
        try {
            if (tag.getInteger("version") == 1 && text.length() == 36) {
                UUID candidate = UUID.fromString(text);
                if (candidate.toString()
                    .equals(text)) id = candidate;
            }
        } catch (IllegalArgumentException ignored) {}
        GridIdentityState.Origin result = new GridIdentityState.Origin(id);
        result.retired = id == null; // Invalid/pending saved identity cannot inherit another grid's consent.
        return result;
    }
}
