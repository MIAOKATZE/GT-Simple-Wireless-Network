package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.mixins.ae2.QuantumConnectionSecurityMixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.me.helpers.AENetworkProxy;
import appeng.util.ReadOnlyCollection;

/** Exercise the production mixin's membership state and the same list mutations used by the native hooks. */
public class QuantumConnectionMembershipTest {

    @Test
    public void nodeAndIncorporationRequireEveryActualEndpointEvenDuringPartialRemoval() throws Exception {
        Endpoint local = new Endpoint(), relay = new Endpoint(), target = new Endpoint();
        IGrid grid = new QuantumConnectionSecurityContextTest.Grid(true).grid;
        local.grid = relay.grid = target.grid = grid;
        Edge connection = new Edge(local.node, relay.node), anchor = new Edge(relay.node, target.node);
        QuantumConnectionMembership.add(local.edges, connection, local.node);
        QuantumConnectionMembership.add(relay.edges, connection, relay.node);
        QuantumConnectionMembership.add(relay.edges, anchor, relay.node);
        QuantumConnectionMembership.add(target.edges, anchor, target.node);
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        node.setWorldObj(QuantumConnectionSecurityContextTest.world());
        AENetworkProxy proxy = new AENetworkProxy(node, "proxy", null, true);
        field(AENetworkProxy.class, "node").set(proxy, local.node);
        field(TileEntityNetworkQuantumNode.class, "gridProxy").set(node, proxy);
        field(TileEntityNetworkQuantumNode.class, "connection").set(node, connection);
        field(TileEntityNetworkQuantumNode.class, "anchorConnection").set(node, anchor);
        field(TileEntityNetworkQuantumNode.class, "bridgeRelayNode").set(node, relay.node);
        field(TileEntityNetworkQuantumNode.class, "bridgeTargetNode").set(node, target.node);
        QuantumIncorporationRegistry.Entry incorporated = new QuantumIncorporationRegistry.Entry();
        field(QuantumIncorporationRegistry.Entry.class, "connection").set(incorporated, connection);
        field(QuantumIncorporationRegistry.Entry.class, "billingConnection").set(incorporated, anchor);
        field(QuantumIncorporationRegistry.Entry.class, "local").set(incorporated, local.node);
        field(QuantumIncorporationRegistry.Entry.class, "billingNode").set(incorporated, relay.node);
        field(QuantumIncorporationRegistry.Entry.class, "target").set(incorporated, target.node);
        Method live = QuantumIncorporationRegistry.Entry.class.getDeclaredMethod("hasLiveConnections");
        live.setAccessible(true);
        assertTrue(node.isLinked());
        assertEquals(Boolean.TRUE, live.invoke(incorporated));
        Endpoint[] endpoints = { local, relay, relay, target };
        Edge[] connections = { connection, connection, anchor, anchor };
        for (int i = 0; i < endpoints.length; i++) {
            Endpoint endpoint = endpoints[i];
            Edge edge = connections[i];
            assertTrue(QuantumConnectionMembership.remove(endpoint.edges, edge, endpoint.node));
            assertFalse("A single missing native endpoint must break the node's AND check: " + i, node.isLinked());
            assertEquals(
                "Incorporation must retain the same AND boundary: " + i,
                Boolean.FALSE,
                live.invoke(incorporated));
            assertTrue(QuantumConnectionMembership.add(endpoint.edges, edge, endpoint.node));
            edge.swapPathingDirection();
            assertTrue(node.isLinked());
            assertEquals(Boolean.TRUE, live.invoke(incorporated));
        }
        assertEquals(0, local.searches + relay.searches + target.searches);
        field(TileEntityNetworkQuantumNode.class, "bridgeRelayNode").set(node, null);
        QuantumConnectionMembership.remove(target.edges, anchor, target.node);
        assertTrue("Adopted direct connections retain their original single-endpoint branch", node.isLinked());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    public void tenThousandHubEdgesUseNoConnectionListSearchInEitherPathingOrientation() throws Exception {
        Endpoint hub = new Endpoint();
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            Endpoint leaf = new Endpoint();
            Edge edge = new Edge(hub.node, leaf.node);
            assertTrue(QuantumConnectionMembership.add(hub.edges, edge, hub.node));
            assertTrue(QuantumConnectionMembership.add(leaf.edges, edge, leaf.node));
            if ((i & 1) == 0) edge.swapPathingDirection();
            assertTrue(QuantumConnectionMembership.contains(hub.node, edge));
            assertTrue(QuantumConnectionMembership.contains(leaf.node, edge));
            assertEquals(0, leaf.searches);
            edges.add(edge);
        }
        Method live = QuantumConnectionSecurity.class.getDeclaredMethod("isLive", IGridConnection.class);
        live.setAccessible(true);
        for (Edge edge : edges) assertEquals(Boolean.TRUE, live.invoke(null, edge));
        assertEquals("Live high-degree endpoints must never be searched", 0, hub.searches);
        assertEquals(10000, hub.edges.size());
    }

    @Test
    public void partialNativeNotificationsKeepExactEndpointMembershipAndLiveOrSemantics() throws Exception {
        Endpoint first = new Endpoint(), second = new Endpoint(), unrelated = new Endpoint();
        Edge edge = new Edge(first.node, second.node);
        Method live = QuantumConnectionSecurity.class.getDeclaredMethod("isLive", IGridConnection.class);
        live.setAccessible(true);
        assertFalse(QuantumConnectionMembership.contains(first.node, edge));
        assertEquals(Boolean.FALSE, live.invoke(null, edge));
        // These observations occur at the boundary where native addConnection next notifies its host.
        assertTrue(QuantumConnectionMembership.add(first.edges, edge, first.node));
        assertTrue(QuantumConnectionMembership.contains(first.node, edge));
        assertFalse(QuantumConnectionMembership.contains(second.node, edge));
        assertEquals(Boolean.TRUE, live.invoke(null, edge));
        assertTrue(QuantumConnectionMembership.add(second.edges, edge, second.node));
        edge.swapPathingDirection();
        assertTrue(QuantumConnectionMembership.contains(first.node, edge));
        assertTrue(QuantumConnectionMembership.contains(second.node, edge));
        assertFalse(QuantumConnectionMembership.contains(unrelated.node, edge));
        assertTrue(QuantumConnectionMembership.remove(first.edges, edge, first.node));
        assertFalse(QuantumConnectionMembership.contains(first.node, edge));
        assertTrue(QuantumConnectionMembership.contains(second.node, edge));
        assertEquals(
            "A destroy callback between the two removals still sees a live edge",
            Boolean.TRUE,
            live.invoke(null, edge));
        assertTrue(QuantumConnectionMembership.remove(second.edges, edge, second.node));
        assertEquals(Boolean.FALSE, live.invoke(null, edge));
        assertTrue(QuantumConnectionMembership.add(first.edges, edge, first.node));
        assertEquals("The opposite one-ended state is live too", Boolean.TRUE, live.invoke(null, edge));
        assertTrue(QuantumConnectionMembership.remove(first.edges, edge, first.node));
        assertEquals(Boolean.FALSE, live.invoke(null, edge));
    }

    @Test
    public void duplicateAddsFailedRemovesAndExceptionsPreserveTheOtherEndpoint() {
        Endpoint first = new Endpoint(), second = new Endpoint(), unrelated = new Endpoint();
        Edge edge = new Edge(first.node, second.node);
        assertTrue(QuantumConnectionMembership.add(first.edges, edge, first.node));
        assertTrue(QuantumConnectionMembership.add(first.edges, edge, first.node));
        assertTrue(QuantumConnectionMembership.add(second.edges, edge, second.node));
        assertFalse(QuantumConnectionMembership.remove(unrelated.edges, edge, unrelated.node));
        assertTrue(QuantumConnectionMembership.remove(first.edges, edge, first.node));
        assertTrue(
            "One duplicate remains in the original native list",
            QuantumConnectionMembership.contains(first.node, edge));
        assertTrue(QuantumConnectionMembership.remove(first.edges, edge, first.node));
        assertFalse(QuantumConnectionMembership.contains(first.node, edge));
        assertFalse(QuantumConnectionMembership.remove(first.edges, edge, first.node));
        assertTrue(QuantumConnectionMembership.contains(second.node, edge));
        try {
            QuantumConnectionMembership.remove(Collections.unmodifiableList(second.edges), edge, second.node);
            fail("The original list exception must propagate");
        } catch (UnsupportedOperationException expected) {
            assertTrue(QuantumConnectionMembership.contains(second.node, edge));
        }
        List<IGridConnection> refusing = new ArrayList<IGridConnection>() {

            @Override
            public boolean add(IGridConnection ignored) {
                return false;
            }
        };
        assertFalse(QuantumConnectionMembership.add(refusing, edge, first.node));
        assertFalse(QuantumConnectionMembership.contains(first.node, edge));
        try {
            QuantumConnectionMembership.add(Collections.emptyList(), edge, first.node);
            fail("A failed native add must not publish membership");
        } catch (UnsupportedOperationException expected) {
            assertFalse(QuantumConnectionMembership.contains(first.node, edge));
        }
        assertTrue(QuantumConnectionMembership.remove(second.edges, edge, second.node));
        assertFalse(QuantumConnectionMembership.contains(second.node, edge));
    }

    @Test
    public void foreignConnectionsUseTheirActualListsAndKeepNullQueryBehavior() {
        Endpoint node = new Endpoint();
        IGridConnection foreign = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (proxy, method, args) -> method.getName()
                .equals("equals") ? proxy == args[0] : null);
        assertFalse(QuantumConnectionMembership.contains(node.node, foreign));
        assertTrue(QuantumConnectionMembership.add(node.edges, foreign, node.node));
        assertTrue(QuantumConnectionMembership.contains(node.node, foreign));
        assertTrue(QuantumConnectionMembership.remove(node.edges, foreign, node.node));
        assertFalse(QuantumConnectionMembership.contains(node.node, foreign));
        assertEquals(3, node.searches);
        assertFalse(QuantumConnectionMembership.contains(null, foreign));
        node.edges.add(null);
        assertTrue(
            "An untracked null lookup retains the original collection result",
            QuantumConnectionMembership.contains(node.node, null));
        List<IGridConnection> foreignEdges = new ArrayList<>();
        IGridNode foreignNode = (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> method.getName()
                .equals("getConnections") ? new ReadOnlyCollection<>(foreignEdges) : null);
        Edge tracked = new Edge(node.node, foreignNode);
        foreignEdges.add(tracked);
        assertTrue(
            "Foreign nodes retain their own list even when the edge has native tracking",
            QuantumConnectionMembership.contains(foreignNode, tracked));
        foreignEdges.clear();
        assertFalse(QuantumConnectionMembership.contains(foreignNode, tracked));
    }

    private static final class Endpoint {

        final List<IGridConnection> edges = new LinkedList<>();
        IGrid grid;
        int searches;
        final ReadOnlyCollection<IGridConnection> view = new ReadOnlyCollection<IGridConnection>(edges) {

            @Override
            public boolean contains(Object edge) {
                searches++;
                return super.contains(edge);
            }
        };
        final IGridNode node = (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class, QuantumConnectionMembership.TrackedNode.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getConnections")) return view;
                if (method.getName()
                    .equals("getGrid")) return grid;
                return null;
            });
    }

    /** Only API plumbing is supplied here; all attachment facts come from the actual production mixin. */
    private static final class Edge extends QuantumConnectionSecurityMixin implements IGridConnection {

        private IGridNode first, second;

        Edge(IGridNode first, IGridNode second) {
            this.first = first;
            this.second = second;
        }

        void swapPathingDirection() {
            IGridNode previous = first;
            first = second;
            second = previous;
        }

        @Override
        public IGridNode a() {
            return first;
        }

        @Override
        public IGridNode b() {
            return second;
        }

        @Override
        public IGridNode getOtherSide(IGridNode node) {
            return node == first ? second : first;
        }

        @Override
        public ForgeDirection getDirection(IGridNode node) {
            return ForgeDirection.UNKNOWN;
        }

        @Override
        public boolean hasDirection() {
            return false;
        }

        @Override
        public int getUsedChannels() {
            return 0;
        }

        @Override
        public void setAdHocChannels(int channels) {}

        @Override
        public void destroy() {}
    }
}
