package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.common.quantum.QuantumConnectionSecurityContextTest.MaintenanceFixture;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;

import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.AENetworkProxy;

/** Count actual production maintenance, including the local isolation and node overload paths. */
public class QuantumLiveBridgeMaintenanceTest {

    @Test
    public void stableOffIncorporationSkipsWholeGridAuthorizationButStillIsolatesAndObservesPause() throws Exception {
        boolean previousSafety = Config.quantumSafetyEnabled;
        Config.quantumSafetyEnabled = true;
        try (MaintenanceFixture fixture = new MaintenanceFixture(true, 16, 0)) {
            LocalWorld local = (LocalWorld) allocate(LocalWorld.class);
            Device device = new Device();
            device.setWorldObj(local);
            local.tile = device;
            device.node = fixture.grid.add(device);
            QuantumIncorporationRegistry.Entry entry = new QuantumIncorporationRegistry.Entry();
            entry.dim = 37175;
            entry.ay = 64;
            entry.anchorGeneration = QuantumControllerRegistry.get(fixture.world)
                .canonicalAnchor(QuantumControllerRegistry.pack(0, 64, 0)).generation;
            Class<?> billingType = Class.forName(QuantumIncorporationRegistry.class.getName() + "$BillingLeaf");
            Constructor<?> constructor = billingType.getDeclaredConstructor(entry.getClass(), DimensionalCoord.class);
            constructor.setAccessible(true);
            IGridHost billing = (IGridHost) constructor.newInstance(entry, new DimensionalCoord(fixture.own));
            IGridNode billingNode = fixture.grid.add(billing);
            field(billingType, "node").set(billing, billingNode);
            IGridConnection first = edge(fixture, device.node, billingNode);
            IGridConnection second = edge(fixture, billingNode, fixture.own.node);
            setEntry(entry, "local", device.node);
            setEntry(entry, "target", fixture.own.node);
            setEntry(entry, "billingNode", billingNode);
            setEntry(entry, "connection", first);
            setEntry(entry, "billingConnection", second);
            setEntry(entry, "loadedTile", device);
            QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("live-maintenance");
            Method maintain = method(
                QuantumIncorporationRegistry.class,
                "maintain",
                net.minecraft.world.World.class,
                QuantumIncorporationRegistry.Entry.class);
            QuantumConnectionSecurity.restoreNativeChecks();
            QuantumConnectionSecurity.restoreNativeChecks();
            assertTrue(QuantumConnectionSecurity.hasUnchangedOverride(first, second));
            fixture.resetCounts();
            fixture.own.nodeLookups = 0;
            for (int tick = 0; tick < 100; tick++) {
                local.tick = tick;
                maintain.invoke(registry, local, entry);
            }
            assertTrue(entry.isConnected());
            assertEquals(
                "Remote getGridNode is unnecessary for the same audited OFF bridge",
                0,
                fixture.own.nodeLookups);
            assertEquals("OFF maintenance must not enumerate all native machines", 0, fixture.grid.machineReads);
            assertEquals(
                "The required local 20t isolation still enumerates the eight exposed directions",
                40,
                device.nodeReads);
            assertEquals("Only the currently bound controller tile is read per tick", 100, fixture.world.tileReads);
            assertTrue(
                QuantumControllerRegistry.get(fixture.world)
                    .suspendStructure(QuantumControllerRegistry.pack(0, 64, 0)));
            local.tick = 100;
            maintain.invoke(registry, local, entry);
            assertFalse("Pausing must disconnect before the unchanged-OFF shortcut", entry.isConnected());
            assertTrue(
                "Suspension preserves the original generation",
                QuantumControllerRegistry.get(fixture.world)
                    .isSuspendedAnchor(QuantumControllerRegistry.pack(0, 64, 0), entry.anchorGeneration));
        } finally {
            Config.quantumSafetyEnabled = previousSafety;
        }
    }

    @Test
    public void stableOffNodeSkipsAuthorizationWithoutSkippingItsMaintenanceAndPolicyChangeFallsBack()
        throws Exception {
        boolean previousSafety = Config.quantumSafetyEnabled;
        Config.quantumSafetyEnabled = true;
        try (MaintenanceFixture fixture = new MaintenanceFixture(true, 16, 0)) {
            LocalWorld local = (LocalWorld) allocate(LocalWorld.class);
            CountingNode node = new CountingNode();
            node.setWorldObj(local);
            field(TileEntityNetworkQuantumNode.class, "anchorDim").setInt(node, 37175);
            field(TileEntityNetworkQuantumNode.class, "anchorY").setInt(node, 64);
            field(TileEntityNetworkQuantumNode.class, "anchorGeneration").set(
                node,
                QuantumControllerRegistry.get(fixture.world)
                    .canonicalAnchor(QuantumControllerRegistry.pack(0, 64, 0)).generation);
            IGridNode core = fixture.grid.add(node);
            AENetworkProxy proxy = new AENetworkProxy(node, "proxy", null, true);
            field(AENetworkProxy.class, "node").set(proxy, core);
            field(TileEntityNetworkQuantumNode.class, "gridProxy").set(node, proxy);
            QuantumNodeBridgeRelay relay = new QuantumNodeBridgeRelay(node, new DimensionalCoord(fixture.own));
            IGridNode relayNode = fixture.grid.add(relay);
            relay.attach(relayNode);
            field(TileEntityNetworkQuantumNode.class, "bridgeRelayNode").set(node, relayNode);
            field(TileEntityNetworkQuantumNode.class, "bridgeTargetNode").set(node, fixture.own.node);
            IGridConnection first = edge(fixture, core, relayNode);
            IGridConnection second = edge(fixture, relayNode, fixture.own.node);
            field(TileEntityNetworkQuantumNode.class, "connection").set(node, first);
            field(TileEntityNetworkQuantumNode.class, "anchorConnection").set(node, second);
            method(TileEntityNetworkQuantumNode.class, "snapshotAnchor").invoke(node);
            QuantumConnectionSecurity.restoreNativeChecks();
            QuantumConnectionSecurity.restoreNativeChecks();
            assertTrue(QuantumConnectionSecurity.hasUnchangedOverride(first, second));
            fixture.resetCounts();
            fixture.own.nodeLookups = 0;
            Method maintain = method(TileEntityNetworkQuantumNode.class, "maintainConnection");
            for (int tick = 0; tick < 20; tick++) maintain.invoke(node);
            assertTrue(node.isLinked());
            assertEquals(0, node.authorizations);
            assertEquals(0, fixture.own.nodeLookups);
            assertEquals(0, fixture.grid.machineReads);
            assertEquals(
                "Each callback still reads its anchor for both identity checks and the overload check",
                60,
                fixture.world.tileReads);
            fixture.own.gtswn$setNetworkSafetyEnabled(true);
            maintain.invoke(node);
            assertTrue("A policy change must use fresh native authorization", node.authorizations > 0);
            assertFalse("The now-protected network has no native station and must disconnect", node.isLinked());
        } finally {
            Config.quantumSafetyEnabled = previousSafety;
        }
    }

    private static IGridConnection edge(MaintenanceFixture fixture, IGridNode first, IGridNode second) {
        IGridConnection edge = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (proxy, called, args) -> {
                switch (called.getName()) {
                    case "a":
                        return first;
                    case "b":
                        return second;
                    case "getOtherSide":
                        return args[0] == first ? second : first;
                    case "getDirection":
                        return ForgeDirection.UNKNOWN;
                    case "getUsedChannels":
                        return 0;
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "destroy":
                        fixture.grid.connections.get(first)
                            .removeIf(value -> value == proxy);
                        fixture.grid.connections.get(second)
                            .removeIf(value -> value == proxy);
                        QuantumSecurityTopology.changed();
                        return null;
                    default:
                        return null;
                }
            });
        fixture.grid.connections.get(first)
            .add(edge);
        fixture.grid.connections.get(second)
            .add(edge);
        QuantumConnectionSecurity.recordActual(edge, true);
        return edge;
    }

    private static final class CountingNode extends TileEntityNetworkQuantumNode {

        int authorizations;

        @Override
        public boolean isNativeConnectionAuthorized(IGridNode first, IGridNode second) {
            authorizations++;
            return super.isNativeConnectionAuthorized(first, second);
        }
    }

    private static final class Device extends TileEntity implements IGridHost {

        IGridNode node;
        int nodeReads;

        @Override
        public IGridNode getGridNode(ForgeDirection direction) {
            nodeReads++;
            return node;
        }

        @Override
        public AECableType getCableConnectionType(ForgeDirection direction) {
            return AECableType.SMART;
        }

        @Override
        public void securityBreak() {}
    }

    private static final class LocalWorld extends WorldServer {

        long tick;
        TileEntity tile;

        private LocalWorld() {
            super(null, (ISaveHandler) null, "live-maintenance", 0, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public long getTotalWorldTime() {
            return tick;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return tile;
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public Entity getEntityByID(int id) {
            return null;
        }
    }

    private static void setEntry(QuantumIncorporationRegistry.Entry entry, String name, Object value) throws Exception {
        field(entry.getClass(), name).set(entry, value);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field(unsafe, "theUnsafe").get(null), type);
    }
}
