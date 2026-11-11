// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityProvider;
import appeng.tile.networking.TileController;

/** Request-local native graph inspection. Never edits connections, registry state, permissions or unloaded chunks. */
public final class QuantumSecurityPreflight {

    private static final int MAX_VISITED_NODES = 16384;

    private QuantumSecurityPreflight() {}

    public static boolean preservesNativeSecurity(World world, Set<Long> futureMembers) {
        return inspect(world, futureMembers).safe;
    }

    static Inspection inspect(World world, Set<Long> futureMembers) {
        if (world == null || world.isRemote || futureMembers == null || futureMembers.isEmpty())
            return Inspection.UNSAFE;
        Map<IGridNode, EnumSet<ForgeDirection>> filtered = new IdentityHashMap<>();
        IGrid grid = null;
        for (long position : futureMembers) {
            int x = QuantumControllerRegistry.unpackX(position), y = QuantumControllerRegistry.unpackY(position),
                z = QuantumControllerRegistry.unpackZ(position);
            if (!world.blockExists(x, y, z)) return Inspection.UNSAFE;
            TileEntity controller = world.getTileEntity(x, y, z);
            if (!(controller instanceof TileController)) return Inspection.UNSAFE;
            IGridNode node = ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN);
            if (node == null || grid != null && node.getGrid() != grid) return Inspection.UNSAFE;
            grid = node.getGrid();
            filtered.put(
                node,
                QuantumControllerEventHandler.connectionSidesAfterQuantization(world, x, y, z, futureMembers));
        }
        IGridNode provider = null;
        for (Class<? extends IGridHost> type : grid.getMachinesClasses()) {
            if (!ISecurityProvider.class.isAssignableFrom(type)) continue;
            for (IGridNode node : grid.getMachines(type)) {
                if (!(node.getMachine() instanceof ISecurityProvider)) continue;
                if (provider != null && provider != node) return Inspection.UNSAFE;
                provider = node;
            }
        }
        // An ordinary network without a security station retains the existing local-OFF workflow.
        if (provider == null) return new Inspection(true, null, grid);
        return new Inspection(reachesProvider(filtered, provider), provider, grid);
    }

    static boolean reachesProvider(Map<IGridNode, EnumSet<ForgeDirection>> filtered, IGridNode provider) {
        Set<IGridNode> visited = Collections.newSetFromMap(new IdentityHashMap<IGridNode, Boolean>());
        ArrayDeque<IGridNode> pending = new ArrayDeque<>();
        for (IGridNode node : filtered.keySet()) if (visited.add(node)) pending.add(node);
        while (!pending.isEmpty()) {
            IGridNode node = pending.removeFirst();
            if (node == provider) return true;
            for (IGridConnection edge : node.getConnections()) {
                IGridNode other = edge.getOtherSide(node);
                if (other == null || !keepsEdge(filtered, edge, node) || !keepsEdge(filtered, edge, other)) continue;
                if (visited.add(other)) {
                    if (visited.size() > MAX_VISITED_NODES) return false;
                    pending.addLast(other);
                }
            }
        }
        return false;
    }

    private static boolean keepsEdge(Map<IGridNode, EnumSet<ForgeDirection>> filtered, IGridConnection edge,
        IGridNode endpoint) {
        EnumSet<ForgeDirection> sides = filtered.get(endpoint);
        return sides == null || !edge.hasDirection() || sides.contains(edge.getDirection(endpoint));
    }

    static final class Inspection {

        static final Inspection UNSAFE = new Inspection(false, null, null);
        final boolean safe;
        final IGridNode provider;
        final IGrid grid;

        Inspection(boolean safe, IGridNode provider, IGrid grid) {
            this.safe = safe;
            this.provider = provider;
            this.grid = grid;
        }
    }
}
