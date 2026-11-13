// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.exceptions.ExistingConnectionException;
import appeng.api.exceptions.FailedConnection;
import appeng.api.exceptions.SecurityConnectionException;
import appeng.api.networking.IGridBlock;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.api.util.AEColor;
import appeng.util.ReadOnlyCollection;

/** Issue #128: production recovery with controlled native endpoints; real AE nodes live in testlab. */
public class QuantumAdjacentRecoveryTest {

    @Test
    public void anchorAuthorizationBecomingReadyRepairsWithoutAnotherNeighborEvent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.add(ForgeDirection.EAST);
            fixture.tile.authorized = false;
            fixture.tick(0);
            assertEquals(0, fixture.tile.connects);
            fixture.tile.authorized = true;
            for (int tick = 1; tick <= 20; tick++) fixture.tick(tick);
            assertEquals(1, fixture.tile.connects);
            assertEquals(2, fixture.tile.authorizations);
            assertEquals(
                ForgeDirection.EAST,
                fixture.core.edges.get(0)
                    .getDirection(fixture.core.node));
            assertTrue(
                QuantumConnectionMembership
                    .contains(fixture.devices.get(ForgeDirection.EAST).endpoint.node, fixture.core.edges.get(0)));
            for (int tick = 21; tick <= 200; tick++) fixture.tick(tick);
            assertEquals(
                "Stable local edges do not reauthorize the whole native graph",
                2,
                fixture.tile.authorizations);
            assertEquals(1, fixture.tile.connects);
        }
    }

    @Test
    public void eitherEndpointCanBecomeReadyLaterAndSameTileCanReplaceItsGridNode() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Device device = fixture.add(ForgeDirection.WEST);
            Endpoint saved = device.endpoint;
            device.endpoint = null;
            fixture.tick(0);
            assertEquals(0, fixture.tile.connects);
            device.endpoint = saved;
            fixture.tick(20);
            assertEquals(1, fixture.tile.connects);
            IGridConnection old = fixture.core.edges.get(0);
            device.endpoint = new Endpoint(fixture.world);
            fixture.tick(40);
            assertEquals(2, fixture.tile.connects);
            assertFalse(fixture.core.edges.contains(old));
            assertFalse(saved.edges.contains(old));
            assertSame(
                device.endpoint.node,
                fixture.core.edges.get(0)
                    .getOtherSide(fixture.core.node));
        }
    }

    @Test
    public void persistentDenialUsesBoundedBackoffAndNeverConstructsOrBreaksMachines() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Device device = fixture.add(ForgeDirection.NORTH);
            fixture.tile.authorized = false;
            for (int tick = 0; tick <= 500; tick++) fixture.tick(tick);
            assertEquals("Attempts at 0,20,60,140,300,500", 6, fixture.tile.authorizations);
            assertEquals(0, fixture.tile.connects);
            assertEquals(0, device.securityBreaks);
            assertTrue(fixture.core.edges.isEmpty());
        }
    }

    @Test
    public void nativeConstructorRefusalAlsoBacksOffWithoutMachineSecurityBreak() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Device device = fixture.add(ForgeDirection.SOUTH);
            fixture.tile.constructorRejects = true;
            for (int tick = 0; tick <= 140; tick++) fixture.tick(tick);
            assertEquals(4, fixture.tile.connects);
            assertEquals(0, device.securityBreaks);
            assertTrue(fixture.core.edges.isEmpty());
            fixture.tile.constructorRejects = false;
            fixture.tick(300);
            assertEquals(1, fixture.core.edges.size());
        }
    }

    @Test
    public void repairHonorsBothNativeSideMasksColorsAndQuantumNodeExclusion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Device occupied = fixture.add(ForgeDirection.EAST);
            fixture.core.sides.remove(ForgeDirection.EAST); // Native CBC side-part/facade mask.
            Device blockedPeer = fixture.add(ForgeDirection.WEST);
            blockedPeer.endpoint.sides.remove(ForgeDirection.EAST);
            Device colored = fixture.add(ForgeDirection.NORTH);
            fixture.core.color = AEColor.Red;
            colored.endpoint.color = AEColor.Blue;
            TileEntityNetworkQuantumNode otherQuantum = new TileEntityNetworkQuantumNode();
            otherQuantum.setWorldObj(fixture.world);
            fixture.world.tiles.put(ForgeDirection.SOUTH, otherQuantum);
            fixture.tick(0);
            assertEquals(0, fixture.tile.authorizations);
            assertEquals(0, fixture.tile.connects);
            fixture.core.sides.add(ForgeDirection.EAST);
            blockedPeer.endpoint.sides.add(ForgeDirection.EAST);
            colored.endpoint.color = AEColor.Transparent;
            fixture.tick(20);
            fixture.tick(40);
            assertEquals(3, fixture.tile.connects);
            assertEquals(1, occupied.endpoint.edges.size());
            assertEquals(1, blockedPeer.endpoint.edges.size());
            assertEquals(1, colored.endpoint.edges.size());
        }
    }

    @Test
    public void unloadedInvalidAndOldWorldEndpointsCannotBeUsedEvenWithSameDimension() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Device unloaded = fixture.add(ForgeDirection.EAST);
            fixture.world.unloaded.add(ForgeDirection.EAST);
            Device invalid = fixture.add(ForgeDirection.WEST);
            invalid.invalidate();
            Device stale = fixture.add(ForgeDirection.NORTH);
            FixtureWorld oldWorld = (FixtureWorld) allocate(FixtureWorld.class);
            stale.endpoint = new Endpoint(oldWorld);
            fixture.tick(0);
            assertEquals(0, unloaded.reads);
            assertEquals(0, invalid.reads);
            assertEquals(0, fixture.tile.authorizations);
            assertEquals(0, fixture.tile.connects);
            stale.endpoint = new Endpoint(fixture.world);
            fixture.tick(20);
            assertEquals(1, fixture.tile.connects);
            assertEquals("No API was allowed to fetch a tile in an unloaded chunk", 0, fixture.world.unloadedReads);
        }
    }

    @Test
    public void allSixFacesShareTheExistingTwoAttemptBudgetAndEventuallyRecover() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) fixture.add(side);
            fixture.tick(0);
            assertEquals(2, fixture.tile.connects);
            fixture.tick(20);
            assertEquals(4, fixture.tile.connects);
            fixture.tick(40);
            assertEquals(6, fixture.tile.connects);
            for (Device device : fixture.devices.values()) assertEquals(1, device.endpoint.edges.size());
        }
    }

    @Test
    public void removingPhysicalEndpointDropsOnlyItsDirectionalEdgeAndKeepsUnknownBridge() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Device device = fixture.add(ForgeDirection.UP);
            Endpoint remote = new Endpoint(fixture.world);
            edge(fixture.core, remote, ForgeDirection.UNKNOWN);
            fixture.tick(0);
            assertEquals(2, fixture.core.edges.size());
            fixture.world.tiles.remove(ForgeDirection.UP);
            fixture.tick(20);
            assertTrue(device.endpoint.edges.isEmpty());
            assertEquals(1, fixture.core.edges.size());
            assertEquals(
                ForgeDirection.UNKNOWN,
                fixture.core.edges.get(0)
                    .getDirection(fixture.core.node));
        }
    }

    @Test
    public void unknownConnectionToSamePeerIsNotMistakenForAPhysicalFace() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Device device = fixture.add(ForgeDirection.UP);
            edge(fixture.core, device.endpoint, ForgeDirection.UNKNOWN);
            fixture.tick(0);
            assertEquals(1, fixture.tile.connects);
            assertEquals(1, fixture.core.edges.size());
            fixture.tick(20);
            fixture.tick(60);
            assertEquals(
                "Duplicate native connections back off instead of declaring a face repaired",
                3,
                fixture.tile.connects);
            assertEquals(
                ForgeDirection.UNKNOWN,
                fixture.core.edges.get(0)
                    .getDirection(fixture.core.node));
        }
    }

    private static final class RecoveringNode extends TileEntityNetworkQuantumNode {

        Endpoint core;
        boolean authorized = true, constructorRejects;
        int authorizations, connects;

        @Override
        public IGridNode getCoreGridNode() {
            return core.node;
        }

        @Override
        public boolean isNativeConnectionAuthorized(IGridNode first, IGridNode second) {
            authorizations++;
            return authorized;
        }

        @Override
        protected void connectAdjacentNodes(IGridNode first, IGridNode second, ForgeDirection side)
            throws FailedConnection {
            connects++;
            if (constructorRejects) throw new SecurityConnectionException();
            for (IGridConnection existing : core.edges)
                if (existing.getOtherSide(first) == second) throw new ExistingConnectionException("Existing endpoint");
            for (TileEntity neighbor : ((FixtureWorld) getWorldObj()).tiles.values()) {
                if (neighbor instanceof Device && ((Device) neighbor).endpoint != null
                    && ((Device) neighbor).endpoint.node == second) {
                    Device device = (Device) neighbor;
                    edge(core, device.endpoint, side);
                    return;
                }
            }
            fail("Connected a node not owned by a current loaded endpoint");
        }
    }

    private static final class Endpoint {

        final List<IGridConnection> edges = new ArrayList<>();
        final EnumSet<ForgeDirection> sides = EnumSet.allOf(ForgeDirection.class);
        AEColor color = AEColor.Transparent;
        final IGridNode node;

        Endpoint(World world) {
            IGridBlock block = (IGridBlock) Proxy.newProxyInstance(
                IGridBlock.class.getClassLoader(),
                new Class<?>[] { IGridBlock.class },
                (ignored, method, args) -> {
                    if (method.getName()
                        .equals("getConnectableSides")) return sides;
                    if (method.getName()
                        .equals("getGridColor")) return color;
                    if (method.getName()
                        .equals("isWorldAccessible")) return true;
                    return null;
                });
            node = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(),
                new Class<?>[] { IGridNode.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getConnections":
                            return new ReadOnlyCollection<>(edges);
                        case "getWorld":
                            return world;
                        case "getGridBlock":
                            return block;
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                    }
                    return null;
                });
        }
    }

    private static void edge(Endpoint first, Endpoint second, ForgeDirection side) {
        IGridConnection edge = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "a":
                        return first.node;
                    case "b":
                        return second.node;
                    case "getOtherSide":
                        return args[0] == first.node ? second.node : first.node;
                    case "getDirection":
                        return args[0] == first.node ? side : side.getOpposite();
                    case "destroy":
                        first.edges.remove(proxy);
                        second.edges.remove(proxy);
                        return null;
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                }
                return null;
            });
        first.edges.add(edge);
        second.edges.add(edge);
    }

    private static final class Device extends TileEntity implements IGridHost {

        Endpoint endpoint;
        int reads, securityBreaks;

        @Override
        public IGridNode getGridNode(ForgeDirection side) {
            reads++;
            return endpoint == null ? null : endpoint.node;
        }

        @Override
        public AECableType getCableConnectionType(ForgeDirection side) {
            return AECableType.SMART;
        }

        @Override
        public void securityBreak() {
            securityBreaks++;
        }
    }

    private static final class Fixture implements AutoCloseable {

        final FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        final Endpoint core = new Endpoint(world);
        final RecoveringNode tile = new RecoveringNode();
        final Map<ForgeDirection, Device> devices = new EnumMap<>(ForgeDirection.class);
        final Field singleton;
        final Object previousServer;
        final MinecraftServer server = (MinecraftServer) allocate(DedicatedServer.class);
        final long budgetTick = field(TileEntityNetworkQuantumNode.class, "connectBudgetTick").getLong(null);
        final int budgetCount = field(TileEntityNetworkQuantumNode.class, "connectsThisTick").getInt(null);

        Fixture() throws Exception {
            world.tiles = new EnumMap<>(ForgeDirection.class);
            world.unloaded = EnumSet.noneOf(ForgeDirection.class);
            tile.setWorldObj(world);
            tile.core = core;
            Field found = null;
            for (Field candidate : MinecraftServer.class.getDeclaredFields()) {
                if (Modifier.isStatic(candidate.getModifiers()) && candidate.getType() == MinecraftServer.class) {
                    candidate.setAccessible(true);
                    found = candidate;
                }
            }
            assertNotNull(found);
            singleton = found;
            previousServer = singleton.get(null);
            singleton.set(null, server);
            field(TileEntityNetworkQuantumNode.class, "connectBudgetTick").setLong(null, Long.MIN_VALUE);
        }

        Device add(ForgeDirection side) {
            Device device = new Device();
            device.setWorldObj(world);
            device.endpoint = new Endpoint(world);
            devices.put(side, device);
            world.tiles.put(side, device);
            return device;
        }

        void tick(int tick) throws Exception {
            world.tick = tick;
            field(MinecraftServer.class, "tickCounter").setInt(server, tick);
            Method repair = TileEntityNetworkQuantumNode.class.getDeclaredMethod("repairAdjacentConnections");
            repair.setAccessible(true);
            repair.invoke(tile);
        }

        @Override
        public void close() throws Exception {
            singleton.set(null, previousServer);
            field(TileEntityNetworkQuantumNode.class, "connectBudgetTick").setLong(null, budgetTick);
            field(TileEntityNetworkQuantumNode.class, "connectsThisTick").setInt(null, budgetCount);
        }
    }

    private static final class FixtureWorld extends WorldServer {

        long tick;
        Map<ForgeDirection, TileEntity> tiles;
        EnumSet<ForgeDirection> unloaded;
        int unloadedReads;

        private FixtureWorld() {
            super(null, (ISaveHandler) null, "adjacent-fixture", 0, (WorldSettings) null, (Profiler) null);
        }

        private ForgeDirection side(int x, int y, int z) {
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS)
                if (x == side.offsetX && y == side.offsetY && z == side.offsetZ) return side;
            throw new AssertionError("Read beyond the six physical neighbors");
        }

        @Override
        public long getTotalWorldTime() {
            return tick;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            ForgeDirection side = side(x, y, z);
            if (unloaded.contains(side)) {
                unloadedReads++;
                throw new AssertionError("Unloaded tile read");
            }
            return tiles.get(side);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return !unloaded.contains(side(x, y, z));
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

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field(unsafe, "theUnsafe").get(null), type);
    }
}
