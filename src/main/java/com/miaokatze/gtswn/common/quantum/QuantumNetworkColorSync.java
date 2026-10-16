package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

/** Event-driven source updates; carried terminals pull only after their source epoch changes. */
public final class QuantumNetworkColorSync {

    private static final Map<AnchorKey, Set<TileEntityNetworkQuantumNode>> NODES = new HashMap<>();

    public static void track(TileEntityNetworkQuantumNode node) {
        if (node.getWorldObj() == null || node.getWorldObj().isRemote || !node.hasAnchor()) return;
        untrack(node);
        AnchorKey key = new AnchorKey(node.getAnchorDim(), node.getAnchorX(), node.getAnchorY(), node.getAnchorZ());
        NODES.computeIfAbsent(key, ignored -> new HashSet<>())
            .add(node);
        if (DimensionManager.getWorld(node.getAnchorDim()) != null) {
            node.setColorIndex(
                QuantumNetworkColor
                    .resolve(node.getAnchorDim(), node.getAnchorX(), node.getAnchorY(), node.getAnchorZ()));
        }
    }

    public static void untrack(TileEntityNetworkQuantumNode node) {
        AnchorKey key = new AnchorKey(node.getAnchorDim(), node.getAnchorX(), node.getAnchorY(), node.getAnchorZ());
        Set<TileEntityNetworkQuantumNode> sources = NODES.get(key);
        if (sources != null) {
            sources.remove(node);
            if (sources.isEmpty()) NODES.remove(key);
        }
    }

    /** Snapshot before rebinding: replaceAnchor mutates the same source index. */
    public static void migrateNodes(QuantumMigrationJournal journal, int dimension, Set<Long> members) {
        Set<TileEntityNetworkQuantumNode> affected = new HashSet<>();
        for (long position : members) {
            Set<TileEntityNetworkQuantumNode> indexed = NODES.get(
                new AnchorKey(
                    dimension,
                    QuantumControllerRegistry.unpackX(position),
                    QuantumControllerRegistry.unpackY(position),
                    QuantumControllerRegistry.unpackZ(position)));
            if (indexed != null) affected.addAll(indexed);
        }
        for (TileEntityNetworkQuantumNode node : affected) if (!node.isInvalid()) QuantumNetworkMigration.finishStep(
            "quantum node at " + node.xCoord + "," + node.yCoord + "," + node.zCoord,
            () -> node.applyPendingMigrations(journal));
    }

    public static void changed(int dimension, Set<Long> members, int index) {
        for (long position : members) {
            AnchorKey key = new AnchorKey(
                dimension,
                QuantumControllerRegistry.unpackX(position),
                QuantumControllerRegistry.unpackY(position),
                QuantumControllerRegistry.unpackZ(position));
            Set<TileEntityNetworkQuantumNode> sources = NODES.get(key);
            if (sources != null)
                for (TileEntityNetworkQuantumNode node : new ArrayList<>(sources)) node.setColorIndex(index);
        }
        for (WorldServer world : DimensionManager.getWorlds()) QuantumIncorporationRegistry.get(world)
            .recolor(world, dimension, members, index);
    }

}
