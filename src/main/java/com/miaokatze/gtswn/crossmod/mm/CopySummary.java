package com.miaokatze.gtswn.crossmod.mm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

/** Server-thread MM batch receipts. No MM classes are linked from this helper. */
public final class CopySummary {

    private static final Map<EntityPlayer, Batch> ACTIVE = new WeakHashMap<>();

    private CopySummary() {}

    public static Batch begin(EntityPlayer player) {
        if (!isServerPlayer(player)) return null;
        Batch previous = ACTIVE.get(player);
        if (previous != null) finish(player, previous, false);
        Batch batch = new Batch();
        ACTIVE.put(player, batch);
        return batch;
    }

    public static void finish(EntityPlayer player, Batch batch, boolean completed) {
        if (batch == null || !isServerPlayer(player)) return;
        // An old PendingBuild may stop after a replacement batch has already begun.
        if (ACTIVE.get(player) == batch) ACTIVE.remove(player);
        IChatComponent receipt = batch.finish(completed);
        if (receipt != null) player.addChatMessage(receipt);
    }

    public static void linkInstalled(EntityPlayer player, TileEntity target, int side, int tubesConsumed) {
        Batch batch = ACTIVE.get(player);
        if (batch != null && target != null) batch.installed(key(target, side), false, tubesConsumed);
    }

    public static void linkFailed(EntityPlayer player, TileEntity target, int side, boolean missingMaterial) {
        Batch batch = ACTIVE.get(player);
        if (batch != null && target != null) batch.failed(key(target, side), false, missingMaterial);
    }

    public static void incorporationInstalled(EntityPlayer player, TileEntity target, int anchorsConsumed) {
        Batch batch = ACTIVE.get(player);
        if (batch != null && target != null) batch.installed(key(target, -1), true, anchorsConsumed);
    }

    public static void incorporationFailed(EntityPlayer player, TileEntity target, boolean missingMaterial) {
        Batch batch = ACTIVE.get(player);
        if (batch != null && target != null) batch.failed(key(target, -1), true, missingMaterial);
    }

    public static void refillDeferred(EntityPlayer player, TileEntity target, int side) {
        Batch batch = ACTIVE.get(player);
        if (batch != null && target != null) batch.deferred(key(target, side));
    }

    public static void refillCompleted(EntityPlayer player, TileEntity target, int side) {
        Batch batch = ACTIVE.get(player);
        if (batch != null && target != null) batch.refilled(key(target, side));
    }

    public static void quantumNodeRestored(EntityPlayer player, TileEntity target) {
        Batch batch = ACTIVE.get(player);
        if (batch != null && target != null) batch.nodeRestored(key(target, -1));
    }

    public static void coverConfigured(EntityPlayer player, TileEntity target, int side) {
        Batch batch = ACTIVE.get(player);
        if (batch != null && target != null) batch.configured(key(target, side));
    }

    private static boolean isServerPlayer(EntityPlayer player) {
        return player != null && player.worldObj != null && !player.worldObj.isRemote;
    }

    private static Target key(TileEntity tile, int side) {
        int dimension = tile.getWorldObj() == null ? 0 : tile.getWorldObj().provider.dimensionId;
        return new Target(dimension, tile.xCoord, tile.yCoord, tile.zCoord, side);
    }

    /** Pure receipt ledger; repeated apply attempts never count another purchase or another error. */
    public static final class Batch {

        private final Map<Target, Result> links = new HashMap<>();
        private final Map<Target, Result> incorporations = new HashMap<>();
        private final Set<Target> deferred = new HashSet<>();
        private final Set<Target> nodes = new HashSet<>();
        private final Set<Target> configurations = new HashSet<>();
        private boolean finished;

        void installed(Target target, boolean incorporation, int consumed) {
            if (finished) return;
            Map<Target, Result> results = incorporation ? incorporations : links;
            Result old = results.get(target);
            if (old == null || !old.success) results.put(target, new Result(true, false, Math.max(0, consumed)));
        }

        void failed(Target target, boolean incorporation, boolean missingMaterial) {
            if (finished) return;
            Map<Target, Result> results = incorporation ? incorporations : links;
            Result old = results.get(target);
            if (old == null || !old.success) results.put(target, new Result(false, missingMaterial, 0));
        }

        void deferred(Target target) {
            if (!finished) deferred.add(target);
        }

        void refilled(Target target) {
            if (!finished) deferred.remove(target);
        }

        void nodeRestored(Target target) {
            if (!finished) nodes.add(target);
        }

        void configured(Target target) {
            if (!finished) configurations.add(target);
        }

        Counts counts() {
            Counts counts = new Counts();
            for (Result result : links.values()) {
                if (result.success) {
                    counts.links++;
                    counts.tubes += result.consumed;
                } else if (result.missingMaterial) counts.missingTubes++;
                else counts.linkFailures++;
            }
            for (Result result : incorporations.values()) {
                if (result.success) {
                    counts.incorporations++;
                    counts.anchors += result.consumed;
                } else if (result.missingMaterial) counts.missingAnchors++;
                else counts.incorporationFailures++;
            }
            counts.deferred = deferred.size();
            counts.nodes = nodes.size();
            for (Target target : configurations) {
                Result link = links.get(target);
                if (link == null || !link.success) counts.configurations++;
            }
            return counts;
        }

        IChatComponent finish(boolean completed) {
            if (finished) return null;
            finished = true;
            Counts counts = counts();
            if (links.isEmpty() && incorporations.isEmpty()
                && deferred.isEmpty()
                && nodes.isEmpty()
                && configurations.isEmpty()) return null;
            IChatComponent message = new ChatComponentTranslation(
                completed ? "gtswn.chat.mm.processed" : "gtswn.chat.mm.stopped");
            if (counts.links > 0) append(message, "links", counts.links, counts.tubes);
            if (counts.incorporations > 0) append(message, "incorporations", counts.incorporations, counts.anchors);
            if (counts.nodes > 0) append(message, "nodes", counts.nodes);
            if (counts.configurations > 0) append(message, "configured", counts.configurations);
            if (counts.missingTubes > 0) append(message, "missing_tubes", counts.missingTubes);
            if (counts.missingAnchors > 0) append(message, "missing_anchors", counts.missingAnchors);
            if (counts.linkFailures > 0) append(message, "link_failed", counts.linkFailures);
            if (counts.incorporationFailures > 0) append(message, "incorporation_failed", counts.incorporationFailures);
            if (counts.deferred > 0) append(message, "refill_deferred", counts.deferred);
            return message;
        }

        private static void append(IChatComponent message, String suffix, Object... args) {
            message.appendSibling(new ChatComponentTranslation("gtswn.chat.mm." + suffix, args));
        }
    }

    static final class Counts {

        int links, tubes, incorporations, anchors, missingTubes, missingAnchors, linkFailures, incorporationFailures,
            deferred, nodes, configurations;
    }

    private static final class Result {

        final boolean success, missingMaterial;
        final int consumed;

        Result(boolean success, boolean missingMaterial, int consumed) {
            this.success = success;
            this.missingMaterial = missingMaterial;
            this.consumed = consumed;
        }
    }

    static final class Target {

        final int dimension, x, y, z, side;

        Target(int dimension, int x, int y, int z, int side) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.side = side;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Target target)) return false;
            return dimension == target.dimension && x == target.x
                && y == target.y
                && z == target.z
                && side == target.side;
        }

        @Override
        public int hashCode() {
            return ((((dimension * 31 + x) * 31 + y) * 31 + z) * 31 + side);
        }
    }
}
