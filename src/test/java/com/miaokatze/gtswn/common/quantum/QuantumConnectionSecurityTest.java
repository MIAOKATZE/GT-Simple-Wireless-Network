package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityProvider;
import appeng.util.ReadOnlyCollection;

public class QuantumConnectionSecurityTest {

    @Test
    public void providerFirstDiscoveryUsesPersistedAclBeforeItsGridExists() {
        boolean enabled = Config.quantumSafetyEnabled;
        try {
            Config.quantumSafetyEnabled = true;
            IGridHost provider = bootingProvider(7);
            IGridNode pending = node(provider);
            assertNull(pending.getGrid());
            assertTrue(QuantumConnectionSecurity.mayBridge(null, 0, 0, 0, pending, null, () -> 7));
            assertFalse(QuantumConnectionSecurity.mayBridge(null, 0, 0, 0, pending, null, () -> 8));
            assertFalse(QuantumConnectionSecurity.mayBridge(null, 0, 0, 0, pending, null, () -> -1));
            assertFalse(QuantumConnectionSecurity.mayBridge(null, 0, 0, 0, null, null, () -> 7));
            assertFalse(QuantumConnectionSecurity.mayBridge(null, 0, 0, 0, pending, node(bootingProvider(7)), () -> 7));
        } finally {
            Config.quantumSafetyEnabled = enabled;
        }
    }

    private static IGridHost bootingProvider(int owner) {
        return (IGridHost) Proxy.newProxyInstance(
            IGridHost.class.getClassLoader(),
            new Class<?>[] { IGridHost.class, ISecurityProvider.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getOwner")) return owner;
                if (method.getName()
                    .equals("readPermissions"))
                    ((HashMap<Integer, EnumSet<SecurityPermissions>>) args[0])
                        .put(owner, EnumSet.allOf(SecurityPermissions.class));
                return null;
            });
    }

    @Test
    public void enablingSafetyRevokesTrackedQuantumEdgesWithoutTouchingOrdinaryNativeEdges() {
        boolean enabled = Config.quantumSafetyEnabled;
        try {
            QuantumConnectionSecurity.clearConnections();
            IGridHost ordinary = (IGridHost) Proxy.newProxyInstance(
                IGridHost.class.getClassLoader(),
                new Class<?>[] { IGridHost.class },
                (proxy, method, args) -> null);
            AtomicInteger quantumDestroyed = new AtomicInteger(), ordinaryDestroyed = new AtomicInteger();
            IGridConnection quantum = trackedEdge(new TileEntityNetworkQuantumNode(), ordinary, quantumDestroyed);
            IGridConnection nativeEdge = trackedEdge(ordinary, ordinary, ordinaryDestroyed);
            Config.quantumSafetyEnabled = false;
            QuantumConnectionSecurity.recordConnection(quantum);
            QuantumConnectionSecurity.recordConnection(nativeEdge);
            Config.quantumSafetyEnabled = true;
            QuantumConnectionSecurity.restoreNativeChecks();
            QuantumConnectionSecurity.restoreNativeChecks();
            assertEquals(1, quantumDestroyed.get());
            assertEquals(0, ordinaryDestroyed.get());
        } finally {
            QuantumConnectionSecurity.clearConnections();
            Config.quantumSafetyEnabled = enabled;
        }
    }

    @Test
    public void actualPreMergeOverrideIsRecordedEvenWhenThePostMergeDomainNoLongerAllowsIt() {
        boolean enabled = Config.quantumSafetyEnabled;
        try {
            QuantumConnectionSecurity.clearConnections();
            Config.quantumSafetyEnabled = true;
            IGridHost ordinary = (IGridHost) Proxy.newProxyInstance(
                IGridHost.class.getClassLoader(),
                new Class<?>[] { IGridHost.class },
                (proxy, method, args) -> null);
            AtomicInteger destroyed = new AtomicInteger(), ordinaryDestroyed = new AtomicInteger();
            IGridConnection edge = trackedEdge(new TileEntityNetworkQuantumNode(), ordinary, destroyed);
            assertFalse(QuantumConnectionSecurity.bypassesNativeCheck(edge.a(), edge.b()));
            QuantumConnectionSecurity.recordActual(edge, true);
            QuantumConnectionSecurity.recordActual(trackedEdge(ordinary, ordinary, ordinaryDestroyed), true);
            QuantumConnectionSecurity.restoreNativeChecks();
            assertEquals(1, destroyed.get());
            assertEquals(0, ordinaryDestroyed.get());
        } finally {
            QuantumConnectionSecurity.clearConnections();
            Config.quantumSafetyEnabled = enabled;
        }
    }

    private static IGridConnection trackedEdge(IGridHost first, IGridHost second, AtomicInteger destroyed) {
        List<IGridConnection> edges = new ArrayList<>();
        java.util.function.Function<IGridHost, IGridNode> endpoint = host -> (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getMachine")) return host;
                if (method.getName()
                    .equals("getConnections")) return new ReadOnlyCollection<>(edges);
                return null;
            });
        IGridNode a = endpoint.apply(first), b = endpoint.apply(second);
        IGridConnection edge = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("a")) return a;
                if (method.getName()
                    .equals("b")) return b;
                if (method.getName()
                    .equals("equals")) return proxy == args[0];
                if (method.getName()
                    .equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName()
                    .equals("destroy")) {
                    destroyed.incrementAndGet();
                    edges.clear();
                }
                return null;
            });
        edges.add(edge);
        return edge;
    }

    @Test
    public void bridgeAclUsesCurrentNativeBuildAndExplicitDefaultButNeverUnknownActorOrOwner() {
        HashMap<Integer, EnumSet<SecurityPermissions>> permissions = new HashMap<>();
        permissions.put(7, EnumSet.of(SecurityPermissions.SECURITY));
        ISecurityProvider provider = (ISecurityProvider) Proxy.newProxyInstance(
            ISecurityProvider.class.getClassLoader(),
            new Class<?>[] { ISecurityProvider.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getOwner")) return 1;
                if (method.getName()
                    .equals("readPermissions")) {
                    ((HashMap<Integer, EnumSet<SecurityPermissions>>) args[0]).putAll(permissions);
                }
                return null;
            });
        assertFalse(QuantumConnectionSecurity.permitsProvider(provider, 7));
        permissions.put(7, EnumSet.of(SecurityPermissions.BUILD));
        assertTrue(QuantumConnectionSecurity.permitsProvider(provider, 7));
        permissions.clear();
        assertFalse(QuantumConnectionSecurity.permitsProvider(provider, 7));
        permissions.put(-1, EnumSet.of(SecurityPermissions.BUILD));
        assertTrue(QuantumConnectionSecurity.permitsProvider(provider, 7));
        assertFalse(QuantumConnectionSecurity.permitsProvider(provider, -1));
        assertFalse(QuantumConnectionSecurity.permitsProvider(null, 7));
    }

    @Test
    public void offOnlyExemptsRealQuantumEndpointsAndOnRestoresNativeChecks() throws Exception {
        boolean enabled = Config.quantumSafetyEnabled;
        try {
            IGridNode ordinary = node(
                (IGridHost) Proxy.newProxyInstance(
                    IGridHost.class.getClassLoader(),
                    new Class<?>[] { IGridHost.class },
                    (proxy, method, args) -> null));
            IGridNode quantum = node(new TileEntityNetworkQuantumNode());
            Class<?> billingType = Class
                .forName("com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry$BillingLeaf");
            IGridHost billingHost = (IGridHost) allocate(billingType);
            Field entry = billingType.getDeclaredField("entry");
            entry.setAccessible(true);
            entry.set(billingHost, new QuantumIncorporationRegistry.Entry());
            IGridNode billing = node(billingHost);
            for (IGridNode endpoint : new IGridNode[] { quantum, billing }) {
                Config.quantumSafetyEnabled = true;
                assertFalse(QuantumConnectionSecurity.bypassesNativeCheck(endpoint, ordinary));
                Config.quantumSafetyEnabled = false;
                assertTrue(QuantumConnectionSecurity.bypassesNativeCheck(endpoint, ordinary));
                assertTrue(QuantumConnectionSecurity.bypassesNativeCheck(ordinary, endpoint));
                assertFalse(QuantumConnectionSecurity.bypassesNativeCheck(ordinary, ordinary));
                Config.quantumSafetyEnabled = true;
                assertFalse(QuantumConnectionSecurity.bypassesNativeCheck(endpoint, ordinary));
            }
            Config.quantumSafetyEnabled = false;
            assertFalse(QuantumConnectionSecurity.bypassesNativeCheck(null, ordinary));
            assertFalse(QuantumConnectionSecurity.bypassesNativeCheck(null, null));
        } finally {
            Config.quantumSafetyEnabled = enabled;
        }
    }

    private static IGridNode node(IGridHost host) {
        return (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> method.getName()
                .equals("getMachine") ? host : null);
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), type);
    }
}
