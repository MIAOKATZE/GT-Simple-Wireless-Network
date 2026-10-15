package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Event-driven source updates; inventory synchronization checks only each player's slots once per second. */
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

    @SubscribeEvent
    public void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.worldObj.isRemote || event.player.ticksExisted % 20 != 0)
            return;
        boolean changed = false;
        for (ItemStack stack : event.player.inventory.mainInventory) {
            if (stack == null || !(stack.getItem() instanceof ItemNetworkQuantumTerminal)) continue;
            changed |= QuantumTerminalAccess.synchronize(event.player, stack);
        }
        if (changed) event.player.inventory.markDirty();
    }
}
