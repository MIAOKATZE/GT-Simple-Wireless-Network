package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.networking.IGridBlock;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.parts.networking.PartCable;
import appeng.parts.networking.PartDenseCable;
import appeng.parts.networking.PartQuartzFiber;
import appeng.util.ReadOnlyCollection;

/** Exercises the production pair predicate and actual edge cleanup, rather than duplicating either rule. */
public class QuantumIncorporationConnectionPolicyTest {

    @Test
    public void standardAndDenseCableAreBlockedWithEitherIncorporatedEndpointOrder() throws Exception {
        FixtureWorld world = world();
        Device device = device(world, 0, true);
        for (Class<?> cableClass : new Class<?>[] { PartCable.class, PartDenseCable.class }) {
            IGridNode cable = node(allocate(cableClass));
            assertTrue(QuantumIncorporationRegistry.blocksCableConnection(device.node, cable));
            assertTrue(QuantumIncorporationRegistry.blocksCableConnection(cable, device.node));
        }
    }

    @Test
    public void unregisteredOrReplacedDeviceDoesNotInheritCableRestriction() throws Exception {
        FixtureWorld world = world();
        Device device = device(world, 0, false);
        IGridNode cable = node(allocate(PartCable.class));
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(device.node, cable));
        register(world, device);
        device.identity = "replacement";
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(device.node, cable));
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(cable, device.node));
    }

    @Test
    public void hatchAndQuartzFiberPeersKeepNativeConnections() throws Exception {
        FixtureWorld world = world();
        Device incorporated = device(world, 0, true);
        Device neighbor = device(world, 1, false);
        IGridNode fiber = node(allocate(PartQuartzFiber.class));
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(incorporated.node, neighbor.node));
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(neighbor.node, incorporated.node));
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(incorporated.node, fiber));
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(fiber, incorporated.node));
        register(world, neighbor);
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(incorporated.node, neighbor.node));
    }

    @Test
    public void quantumCoreAndVirtualCableAreNotOrdinaryCablePeers() throws Exception {
        FixtureWorld world = world();
        Device incorporated = device(world, 0, true);
        TileEntityNetworkQuantumNode quantum = new TileEntityNetworkQuantumNode();
        IGridNode core = node(quantum);
        IGridNode virtual = node(quantum.getCablePart());
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(incorporated.node, core));
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(core, incorporated.node));
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(incorporated.node, virtual));
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(virtual, incorporated.node));
    }

    @Test
    public void unloadedOwnerLocationNeverTriggersTileRead() throws Exception {
        FixtureWorld world = world();
        Device incorporated = device(world, 0, true);
        IGridNode cable = node(allocate(PartCable.class));
        world.loaded = false;
        world.tileReads = 0;
        assertFalse(QuantumIncorporationRegistry.blocksCableConnection(incorporated.node, cable));
        assertEquals(0, world.tileReads);
    }

    @Test
    public void maintenanceCutsOnlyCableDirectionsAndForeignUnknownEdges() throws Exception {
        FixtureWorld world = world();
        Device incorporated = device(world, 0, true);
        Device neighbor = device(world, 1, false);
        AtomicInteger cable = new AtomicInteger(), dense = new AtomicInteger(), hatch = new AtomicInteger(),
            quantum = new AtomicInteger(), fiber = new AtomicInteger(), internal = new AtomicInteger(),
            foreign = new AtomicInteger(), ownQuantumBridge = new AtomicInteger();
        edge(incorporated, node(allocate(PartCable.class)), ForgeDirection.EAST, cable);
        edge(incorporated, node(allocate(PartDenseCable.class)), ForgeDirection.WEST, dense);
        edge(incorporated, neighbor.node, ForgeDirection.NORTH, hatch);
        edge(incorporated, node(new TileEntityNetworkQuantumNode()), ForgeDirection.SOUTH, quantum);
        edge(incorporated, node(allocate(PartQuartzFiber.class)), ForgeDirection.UP, fiber);
        edge(incorporated, node(incorporated), ForgeDirection.UNKNOWN, internal);
        edge(incorporated, neighbor.node, ForgeDirection.UNKNOWN, foreign);
        IGridConnection preserve = edge(incorporated, neighbor.node, ForgeDirection.UNKNOWN, ownQuantumBridge);
        Method cleanup = QuantumIncorporationRegistry.class
            .getDeclaredMethod("disconnectExternal", TileEntity.class, IGridConnection.class);
        cleanup.setAccessible(true);
        cleanup.invoke(null, incorporated, preserve);
        assertEquals(1, cable.get());
        assertEquals(1, dense.get());
        assertEquals(1, foreign.get());
        assertEquals(0, hatch.get());
        assertEquals(0, quantum.get());
        assertEquals(0, fiber.get());
        assertEquals(0, internal.get());
        assertEquals(0, ownQuantumBridge.get());
    }

    @Test
    public void foreignUnknownEdgeCannotClaimInternalOwnershipUsingOnlyHostCoordinates() throws Exception {
        FixtureWorld world = world();
        Device incorporated = device(world, 0, true);
        IGridBlock foreignBlock = (IGridBlock) Proxy.newProxyInstance(
            IGridBlock.class.getClassLoader(),
            new Class<?>[] { IGridBlock.class },
            (ignored, method, args) -> method.getName()
                .equals("getLocation") ? new DimensionalCoord(incorporated) : null);
        // Same coordinates are not ownership proof: this node is not exposed by the local tile.
        IGridNode foreign = node(null, new ArrayList<>(), foreignBlock);
        AtomicInteger destroyed = new AtomicInteger();
        edge(incorporated, foreign, ForgeDirection.UNKNOWN, destroyed);
        Method cleanup = QuantumIncorporationRegistry.class
            .getDeclaredMethod("disconnectExternal", TileEntity.class, IGridConnection.class);
        cleanup.setAccessible(true);
        cleanup.invoke(null, incorporated, null);
        assertEquals(1, destroyed.get());
    }

    private static Device device(FixtureWorld world, int x, boolean registered) {
        Device device = new Device();
        device.xCoord = x;
        device.yCoord = 64;
        device.identity = "fixture-" + x;
        device.setWorldObj(world);
        device.node = node(device, device.edges);
        world.tiles.put(QuantumControllerRegistry.pack(x, 64, 0), device);
        if (registered) register(world, device);
        return device;
    }

    private static void register(FixtureWorld world, Device device) {
        QuantumIncorporationRegistry registry = QuantumIncorporationRegistry.get(world);
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        NBTTagList entries = saved.getTagList("entries", 10);
        NBTTagCompound entry = new NBTTagCompound();
        entry.setLong("pos", QuantumControllerRegistry.pack(device.xCoord, 64, 0));
        entry.setString("identity", device.identity);
        entry.setString("owner", "fixture-owner");
        entry.setInteger("dim", 7);
        entries.appendTag(entry);
        saved.setTag("entries", entries);
        registry.readFromNBT(saved);
    }

    private static IGridNode node(Object machine) {
        return node(machine, new ArrayList<>());
    }

    private static IGridNode node(Object machine, ArrayList<IGridConnection> edges) {
        return node(machine, edges, null);
    }

    private static IGridNode node(Object machine, ArrayList<IGridConnection> edges, IGridBlock block) {
        return (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getMachine")) return machine;
                if (method.getName()
                    .equals("getGridBlock")) return block;
                if (method.getName()
                    .equals("getConnections")) return new ReadOnlyCollection<>(edges);
                if (method.getName()
                    .equals("equals")) return proxy == args[0];
                if (method.getName()
                    .equals("hashCode")) return System.identityHashCode(proxy);
                return null;
            });
    }

    private static IGridConnection edge(Device local, IGridNode peer, ForgeDirection direction,
        AtomicInteger destroyed) {
        IGridConnection connection = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getDirection")) return direction;
                if (method.getName()
                    .equals("getOtherSide")) return peer;
                if (method.getName()
                    .equals("destroy")) destroyed.incrementAndGet();
                if (method.getName()
                    .equals("equals")) return proxy == args[0];
                if (method.getName()
                    .equals("hashCode")) return System.identityHashCode(proxy);
                return null;
            });
        local.edges.add(connection);
        return connection;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), type);
    }

    private static FixtureWorld world() throws Exception {
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        world.loaded = true;
        world.tiles = new HashMap<>();
        Field provider = World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new WorldProviderSurface());
        Field storage = World.class.getDeclaredField("perWorldStorage");
        storage.setAccessible(true);
        storage.set(world, new MapStorage(null));
        return world;
    }

    private static final class Device extends TileEntity implements IGridHost, QuantumIncorporationIdentity {

        String identity;
        IGridNode node;
        final ArrayList<IGridConnection> edges = new ArrayList<>();

        @Override
        public IGridNode getGridNode(ForgeDirection side) {
            return node;
        }

        @Override
        public AECableType getCableConnectionType(ForgeDirection side) {
            return AECableType.SMART;
        }

        @Override
        public void securityBreak() {}

        @Override
        public String gtswn$getIncorporationIdentity() {
            return identity;
        }

        @Override
        public void gtswn$setIncorporationIdentity(String value) {
            identity = value;
        }

        @Override
        public void markDirty() {}
    }

    private static final class FixtureWorld extends World {

        Map<Long, TileEntity> tiles;
        boolean loaded;
        int tileReads;

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
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

        @Override
        public boolean blockExists(int x, int y, int z) {
            return loaded;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            assertTrue("Attempted to read unloaded tile", loaded);
            tileReads++;
            return tiles.get(QuantumControllerRegistry.pack(x, y, z));
        }
    }
}
