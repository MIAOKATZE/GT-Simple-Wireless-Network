package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.util.ReadOnlyCollection;

public class QuantumNodeBridgeRelayTest {

    @Test
    public void relayAddsNeitherIdleDemandNorChannelDemandAndMustBeOwnedByTheActualNode() throws Exception {
        TileEntityNetworkQuantumNode owner = new TileEntityNetworkQuantumNode();
        QuantumNodeBridgeRelay relay = new QuantumNodeBridgeRelay(owner, null);
        assertEquals(0, relay.getIdlePowerUsage(), 0);
        assertEquals(EnumSet.of(GridFlags.DENSE_CAPACITY), relay.getFlags());
        assertFalse(relay.isWorldAccessible());
        assertTrue(
            relay.getConnectableSides()
                .isEmpty());
        assertNull(relay.getMachineRepresentation());
        IGridNode endpoint = endpoint(new ArrayList<>(), new AtomicInteger());
        relay.attach(endpoint);
        assertFalse(relay.isOwned());
        field("bridgeRelayNode").set(owner, endpoint);
        assertTrue(relay.isOwned());
    }

    @Test
    public void dequantizationDestroysBothEdgesAndRelayOnceWhileKeepingTheAnchor() throws Exception {
        TileEntityNetworkQuantumNode owner = new TileEntityNetworkQuantumNode();
        owner.setAnchor(7, 11, 64, 33);
        List<IGridConnection> localEdges = new ArrayList<>(), relayEdges = new ArrayList<>(),
            targetEdges = new ArrayList<>();
        AtomicInteger localDestroyed = new AtomicInteger(), targetDestroyed = new AtomicInteger(),
            relayDestroyed = new AtomicInteger();
        IGridNode local = endpoint(localEdges, new AtomicInteger()), relay = endpoint(relayEdges, relayDestroyed),
            target = endpoint(targetEdges, new AtomicInteger());
        IGridConnection localEdge = edge(local, relay, localEdges, relayEdges, localDestroyed),
            targetEdge = edge(relay, target, relayEdges, targetEdges, targetDestroyed);
        field("connection").set(owner, localEdge);
        field("anchorConnection").set(owner, targetEdge);
        field("bridgeRelayNode").set(owner, relay);
        field("bridgeTargetNode").set(owner, target);
        owner.disconnectFromDequantizedAnchor();
        owner.disconnectFromDequantizedAnchor();
        owner.invalidate();
        owner.onChunkUnload();
        assertEquals(1, localDestroyed.get());
        assertEquals(1, targetDestroyed.get());
        assertEquals(1, relayDestroyed.get());
        assertTrue(localEdges.isEmpty());
        assertTrue(relayEdges.isEmpty());
        assertTrue(targetEdges.isEmpty());
        assertNull(field("connection").get(owner));
        assertNull(field("anchorConnection").get(owner));
        assertNull(field("bridgeRelayNode").get(owner));
        assertEquals(7, owner.getAnchorDim());
        assertEquals(11, owner.getAnchorX());
    }

    @Test
    public void nativeRelayOwnsTheSplitBeforeEitherBridgeEdgeIsManuallyDestroyed() throws Exception {
        TileEntityNetworkQuantumNode owner = new TileEntityNetworkQuantumNode();
        List<IGridConnection> localEdges = new ArrayList<>(), relayEdges = new ArrayList<>(),
            targetEdges = new ArrayList<>();
        AtomicInteger localDestroyed = new AtomicInteger(), targetDestroyed = new AtomicInteger(),
            relayDestroyed = new AtomicInteger();
        IGridNode local = endpoint(localEdges, new AtomicInteger()),
            target = endpoint(targetEdges, new AtomicInteger());
        IGridNode relay = (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getConnections")) return new ReadOnlyCollection<>(relayEdges);
                if (method.getName()
                    .equals("destroy")) {
                    relayDestroyed.incrementAndGet();
                    assertEquals(
                        "Native teardown must see both original edges before selecting retained pivots",
                        2,
                        relayEdges.size());
                    // Match native connection insertion order: anchor first, local second.
                    while (!relayEdges.isEmpty()) relayEdges.get(0)
                        .destroy();
                }
                return null;
            });
        IGridConnection targetEdge = edge(relay, target, relayEdges, targetEdges, targetDestroyed),
            localEdge = edge(local, relay, localEdges, relayEdges, localDestroyed);
        field("connection").set(owner, localEdge);
        field("anchorConnection").set(owner, targetEdge);
        field("bridgeRelayNode").set(owner, relay);
        field("bridgeTargetNode").set(owner, target);

        owner.invalidate();
        owner.onChunkUnload();
        owner.disconnectFromDequantizedAnchor();

        assertEquals(1, relayDestroyed.get());
        assertEquals(1, targetDestroyed.get());
        assertEquals(1, localDestroyed.get());
        assertTrue(localEdges.isEmpty());
        assertTrue(relayEdges.isEmpty());
        assertTrue(targetEdges.isEmpty());
        assertNull(field("connection").get(owner));
        assertNull(field("anchorConnection").get(owner));
        assertNull(field("bridgeRelayNode").get(owner));
        assertNull(field("bridgeTargetNode").get(owner));
    }

    private static Field field(String name) throws Exception {
        Field field = TileEntityNetworkQuantumNode.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static IGridNode endpoint(List<IGridConnection> edges, AtomicInteger destroyed) {
        return (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getConnections")) return new ReadOnlyCollection<>(edges);
                if (method.getName()
                    .equals("destroy")) destroyed.incrementAndGet();
                return null;
            });
    }

    private static IGridConnection edge(IGridNode first, IGridNode second, List<IGridConnection> firstEdges,
        List<IGridConnection> secondEdges, AtomicInteger destroyed) {
        IGridConnection edge = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("a")) return first;
                if (method.getName()
                    .equals("b")) return second;
                if (method.getName()
                    .equals("equals")) return proxy == args[0];
                if (method.getName()
                    .equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName()
                    .equals("destroy")) {
                    destroyed.incrementAndGet();
                    firstEdges.remove(proxy);
                    secondEdges.remove(proxy);
                }
                return null;
            });
        firstEdges.add(edge);
        secondEdges.add(edge);
        return edge;
    }
}
