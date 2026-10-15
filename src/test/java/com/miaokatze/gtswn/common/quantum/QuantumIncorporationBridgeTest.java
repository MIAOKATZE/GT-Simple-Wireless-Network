package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridBlock;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.util.ReadOnlyCollection;

public class QuantumIncorporationBridgeTest {

    @Test
    public void relayIsOneNativeDenseNodeWithoutConsumingAChannel() throws Exception {
        Class<?> leaf = Class.forName(QuantumIncorporationRegistry.class.getName() + "$BillingLeaf");
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        IGridBlock bridge = (IGridBlock) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(leaf);
        assertEquals(EnumSet.of(GridFlags.DENSE_CAPACITY), bridge.getFlags());
        assertFalse(bridge.isWorldAccessible());
        assertTrue(
            bridge.getConnectableSides()
                .isEmpty());
    }

    @Test
    public void twoEdgeRelayIsLiveAndCountsChannelsOnceAndReclaimsBothEdges() throws Exception {
        IGrid grid = (IGrid) Proxy.newProxyInstance(
            IGrid.class.getClassLoader(),
            new Class<?>[] { IGrid.class },
            (proxy, method, args) -> null);
        ArrayList<IGridConnection> localEdges = new ArrayList<>(), relayEdges = new ArrayList<>(),
            targetEdges = new ArrayList<>();
        AtomicInteger localDestroyed = new AtomicInteger(), targetDestroyed = new AtomicInteger(),
            relayDestroyed = new AtomicInteger();
        IGridNode local = node(localEdges, grid, new AtomicInteger()), relay = node(relayEdges, grid, relayDestroyed),
            target = node(targetEdges, grid, new AtomicInteger());
        IGridConnection deviceEdge = edge(localEdges, relayEdges, localDestroyed),
            controllerEdge = edge(relayEdges, targetEdges, targetDestroyed);
        QuantumIncorporationRegistry.Entry entry = new QuantumIncorporationRegistry.Entry();
        set(entry, "local", local);
        set(entry, "billingNode", relay);
        set(entry, "target", target);
        set(entry, "connection", deviceEdge);
        set(entry, "billingConnection", controllerEdge);
        Field activeField = QuantumIncorporationRegistry.class.getDeclaredField("ACTIVE");
        activeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<AnchorKey, Set<QuantumIncorporationRegistry.Entry>> active = (Map<AnchorKey, Set<QuantumIncorporationRegistry.Entry>>) activeField
            .get(null);
        AnchorKey anchor = new AnchorKey(0, 0, 0, 0);
        Set<QuantumIncorporationRegistry.Entry> indexed = Collections.newSetFromMap(new IdentityHashMap<>());
        indexed.add(entry);
        Set<QuantumIncorporationRegistry.Entry> previous = active.put(anchor, indexed);
        try {
            assertTrue(entry.isConnected());
            assertEquals(32, entry.usedChannels());
            assertArrayEquals(
                new int[] { 1, 32, 32 },
                QuantumIncorporationRegistry.incorporationStats(
                    0,
                    Collections.singleton(QuantumControllerRegistry.pack(0, 0, 0)),
                    grid,
                    Collections.emptySet()));
            java.lang.reflect.Method disconnect = entry.getClass()
                .getDeclaredMethod("disconnect");
            disconnect.setAccessible(true);
            disconnect.invoke(entry);
            disconnect.invoke(entry);
            assertFalse(entry.isConnected());
            assertEquals(1, localDestroyed.get());
            assertEquals(1, targetDestroyed.get());
            assertEquals(1, relayDestroyed.get());
        } finally {
            active.remove(anchor);
            if (previous != null) active.put(anchor, previous);
        }
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static IGridNode node(ArrayList<IGridConnection> edges, IGrid grid, AtomicInteger destroyed) {
        return (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getConnections")) return new ReadOnlyCollection<>(edges);
                if (method.getName()
                    .equals("getGrid")) return grid;
                if (method.getName()
                    .equals("destroy")) destroyed.incrementAndGet();
                return null;
            });
    }

    private static IGridConnection edge(ArrayList<IGridConnection> first, ArrayList<IGridConnection> second,
        AtomicInteger destroyed) {
        IGridConnection edge = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("equals")) return proxy == args[0];
                if (method.getName()
                    .equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName()
                    .equals("getUsedChannels")) return 32;
                if (method.getName()
                    .equals("destroy")) {
                    destroyed.incrementAndGet();
                    first.remove(proxy);
                    second.remove(proxy);
                }
                return null;
            });
        first.add(edge);
        second.add(edge);
        return edge;
    }
}
