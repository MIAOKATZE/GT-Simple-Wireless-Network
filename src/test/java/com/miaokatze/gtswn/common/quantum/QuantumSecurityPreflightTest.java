// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.util.IReadOnlyCollection;

/** Native graph contract: proposed side filtering is simulated without mutating a connection or node. */
public class QuantumSecurityPreflightTest {

    @Test
    public void ordinaryCablePathCannotKeepTheProviderAfterItsControllerFaceIsFiltered() {
        Node controller = new Node(), cable = new Node(), provider = new Node();
        connect(controller, cable, ForgeDirection.EAST, true);
        connect(cable, provider, ForgeDirection.EAST, true);
        Map<IGridNode, EnumSet<ForgeDirection>> sides = filtered(controller, EnumSet.noneOf(ForgeDirection.class));
        assertFalse(QuantumSecurityPreflight.reachesProvider(sides, provider.node));
    }

    @Test
    public void allowedEnergyPathCanKeepANonAdjacentProvider() {
        Node controller = new Node(), energyCell = new Node(), cable = new Node(), provider = new Node();
        connect(controller, energyCell, ForgeDirection.EAST, true);
        connect(energyCell, cable, ForgeDirection.EAST, true);
        connect(cable, provider, ForgeDirection.EAST, true);
        assertTrue(
            QuantumSecurityPreflight
                .reachesProvider(filtered(controller, EnumSet.of(ForgeDirection.EAST)), provider.node));
    }

    @Test
    public void aProviderBehindAFilteredFaceOfAnyMemberIsNotRetained() {
        Node left = new Node(), right = new Node(), provider = new Node();
        connect(left, right, ForgeDirection.EAST, true);
        connect(left, provider, ForgeDirection.WEST, true);
        Map<IGridNode, EnumSet<ForgeDirection>> sides = filtered(left, EnumSet.of(ForgeDirection.WEST));
        sides.put(right.node, EnumSet.of(ForgeDirection.WEST));
        // Start at both future members, but neither can reach a provider placed behind a filtered face.
        sides.put(left.node, EnumSet.of(ForgeDirection.EAST));
        assertFalse(QuantumSecurityPreflight.reachesProvider(sides, provider.node));
        sides.put(left.node, EnumSet.of(ForgeDirection.EAST, ForgeDirection.WEST));
        assertTrue(QuantumSecurityPreflight.reachesProvider(sides, provider.node));
    }

    @Test
    public void physicalSideFilterDoesNotPretendToDestroyAnExistingInvisibleEdge() {
        Node controller = new Node(), provider = new Node();
        connect(controller, provider, ForgeDirection.UNKNOWN, false);
        assertTrue(
            QuantumSecurityPreflight
                .reachesProvider(filtered(controller, EnumSet.noneOf(ForgeDirection.class)), provider.node));
    }

    private static Map<IGridNode, EnumSet<ForgeDirection>> filtered(Node controller, EnumSet<ForgeDirection> sides) {
        Map<IGridNode, EnumSet<ForgeDirection>> result = new IdentityHashMap<>();
        result.put(controller.node, sides);
        return result;
    }

    private static final class Node {

        final List<IGridConnection> edges = new ArrayList<>();
        final IGridNode node;

        Node() {
            Object connections = Proxy.newProxyInstance(
                IReadOnlyCollection.class.getClassLoader(),
                new Class<?>[] { IReadOnlyCollection.class },
                (proxy, method, args) -> {
                    if ("iterator".equals(method.getName())) return edges.iterator();
                    throw new AssertionError("Unexpected collection mutation/read " + method.getName());
                });
            node = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(),
                new Class<?>[] { IGridNode.class },
                (proxy, method, args) -> {
                    if ("getConnections".equals(method.getName())) return connections;
                    throw new AssertionError("Preflight must not mutate native node: " + method.getName());
                });
        }
    }

    private static void connect(Node a, Node b, ForgeDirection direction, boolean directional) {
        IGridConnection edge = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (proxy, method, args) -> {
                if ("getOtherSide".equals(method.getName())) return args[0] == a.node ? b.node : a.node;
                if ("hasDirection".equals(method.getName())) return directional;
                if ("getDirection".equals(method.getName()))
                    return args[0] == a.node ? direction : direction.getOpposite();
                throw new AssertionError("Preflight must not mutate native edge: " + method.getName());
            });
        a.edges.add(edge);
        b.edges.add(edge);
    }
}
