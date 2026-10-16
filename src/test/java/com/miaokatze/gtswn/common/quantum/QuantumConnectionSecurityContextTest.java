package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.security.ISecurityProvider;
import appeng.tile.networking.TileController;
import appeng.util.ReadOnlyCollection;

/** Exercise the production authorization entry points with independently split native grids and mutable ACLs. */
public class QuantumConnectionSecurityContextTest {

    private boolean originalSafety;

    @Before
    public void enableSafety() {
        originalSafety = Config.quantumSafetyEnabled;
        Config.quantumSafetyEnabled = true;
    }

    @After
    public void restoreSafety() {
        Config.quantumSafetyEnabled = originalSafety;
    }

    @Test
    public void offForeignComponentsAreReadOnceAndDoNotInitializeUnrelatedControllerNodes() throws Exception {
        FixtureWorld world = world();
        Grid grid = new Grid();
        List<Controller> own = new ArrayList<>(), foreign = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            own.add(controller(world, i, grid));
            foreign.add(controller(world, 32 + i, grid));
        }
        assertTrue(
            QuantumConnectionSecurity.bypassesNetworkSafety(world, 0, 64, 0, own.get(0).node, foreign.get(0).node));
        int reads = 0;
        for (Controller tile : own) reads += tile.policyReads;
        for (Controller tile : foreign) {
            reads += tile.policyReads;
            assertEquals("Scope deduplication must use existing machine-set nodes", 0, tile.nodeLookups);
        }
        assertTrue(
            "Two components require linear policy work, not one full scan per controller: " + reads,
            reads <= 12 * 6);
        QuantumNetworkSecurityRegistry.get(world)
            .setSafety(world, component(world, 32), true);
        assertFalse(
            "The next call must observe foreign ON immediately",
            QuantumConnectionSecurity.bypassesNetworkSafety(world, 0, 64, 0, own.get(0).node, foreign.get(0).node));
    }

    @Test
    public void foreignSplitComponentKeepsTheProviderlessNativeGridAndUnloadedMiddleMember() throws Exception {
        FixtureWorld world = world();
        Controller source = controller(world, 0, new Grid());
        Grid firstGrid = new Grid(), secondGrid = new Grid();
        Controller first = controller(world, 32, firstGrid);
        Controller middle = controller(world, 33, firstGrid);
        Controller second = controller(world, 34, secondGrid);
        world.tiles.remove(QuantumControllerRegistry.pack(33, 64, 0));
        firstGrid.remove(middle.node);
        firstGrid.add(new Provider().host);
        QuantumNetworkSecurityRegistry.get(world)
            .setSafety(world, component(world, 32), true);
        assertEquals(3, component(world, 32).size());
        assertFalse(
            "A foreign ON component's empty split grid cannot be hidden behind its authorized provider grid",
            QuantumConnectionSecurity.mayBridge(world, source.xCoord, 64, 0, first.node, second.node, () -> 7));
        assertTrue("The providerless representative must actually be checked", second.nodeLookups > 0);
    }

    @Test
    public void nativeAclOwnerAndProviderInventoryAreFreshOnEveryAuthorization() throws Exception {
        FixtureWorld world = world();
        Grid grid = new Grid();
        Controller tile = controller(world, 0, grid);
        Provider provider = new Provider();
        IGridNode station = grid.add(provider.host);
        QuantumNetworkSecurityRegistry.get(world)
            .setSafety(world, component(world, 0), true);
        assertTrue(QuantumConnectionSecurity.mayBridge(world, 0, 64, 0, tile.node, tile.node, () -> 7));
        provider.granted = false;
        assertFalse(QuantumConnectionSecurity.mayBridge(world, 0, 64, 0, tile.node, tile.node, () -> 7));
        provider.granted = true;
        provider.owner = -1;
        assertFalse(QuantumConnectionSecurity.mayBridge(world, 0, 64, 0, tile.node, tile.node, () -> 7));
        provider.owner = 1;
        assertTrue(QuantumConnectionSecurity.mayBridge(world, 0, 64, 0, tile.node, tile.node, () -> 7));
        grid.remove(station);
        assertFalse(QuantumConnectionSecurity.mayBridge(world, 0, 64, 0, tile.node, tile.node, () -> 7));
        grid.add(provider.host);
        QuantumNetworkSecurityRegistry.get(world)
            .setSafety(world, component(world, 0), false);
        int aclReads = provider.aclReads;
        assertTrue(
            QuantumConnectionSecurity.mayBridge(
                world,
                0,
                64,
                0,
                tile.node,
                tile.node,
                () -> { throw new AssertionError("OFF own domain must not resolve an actor"); }));
        assertEquals("OFF own domain does not read its provider ACL", aclReads, provider.aclReads);
    }

    @Test
    public void unloadedControllerRepresentativeIsNotReplacedByItsLoadedGridPeer() throws Exception {
        FixtureWorld world = world();
        Controller source = controller(world, 0, new Grid());
        Grid foreign = new Grid();
        Controller missing = controller(world, 32, foreign), loaded = controller(world, 33, foreign);
        foreign.add(new Provider().host);
        QuantumNetworkSecurityRegistry.get(world)
            .setSafety(world, component(world, 32), true);
        world.tiles.remove(QuantumControllerRegistry.pack(missing.xCoord, 64, 0));
        assertFalse(QuantumConnectionSecurity.mayBridge(world, 0, 64, 0, loaded.node, source.node, () -> 7));
        assertEquals("Scope collection must not initialize an unloaded anchor", 0, missing.nodeLookups);
    }

    @Test
    public void normalLazyAnchorLookupInvalidatesEarlierProviderInventories() throws Exception {
        // ON skips the OFF override's early lookup: the outer inventory really contains the original station first.
        for (int variant = 0; variant < 4; variant++) {
            FixtureWorld world = world();
            Grid grid = new Grid();
            Controller source = controller(world, 0, grid);
            QuantumNetworkSecurityRegistry.get(world)
                .setSafety(world, component(world, 0), variant != 3);
            Provider initial = new Provider(), replacement = new Provider();
            replacement.owner = variant == 0 || variant == 3 ? -1 : 1;
            boolean removeOnly = variant == 2;
            boolean enableDuringLookup = variant == 3;
            IGridNode initialNode = grid.add(initial.host);
            IGridHost ordinary = (IGridHost) Proxy.newProxyInstance(
                IGridHost.class.getClassLoader(),
                new Class<?>[] { IGridHost.class },
                (proxy, method, args) -> null);
            IGridNode peer = grid.add(ordinary);
            source.onLookup = () -> {
                grid.remove(initialNode);
                if (!removeOnly) grid.add(replacement.host);
                if (enableDuringLookup) QuantumNetworkSecurityRegistry.get(world)
                    .setSafety(world, component(world, 0), true);
            };
            int[] actorReads = { 0 };
            assertEquals(
                "Only the currently authorized replacement may grant access; variant=" + variant,
                variant == 1,
                QuantumConnectionSecurity.mayBridge(world, 0, 64, 0, source.node, peer, () -> {
                    actorReads[0]++;
                    return 7;
                }));
            assertEquals("A removed provider's owner must not authorize this call", 0, initial.ownerReads);
            assertEquals("A removed provider's ACL must not authorize this call", 0, initial.aclReads);
            assertEquals(removeOnly ? 0 : 1, replacement.ownerReads);
            assertEquals(variant == 1 ? 1 : 0, replacement.aclReads);
            assertEquals(removeOnly ? 0 : 1, actorReads[0]);
        }
    }

    @Test(timeout = 3000)
    public void bootingNullAndInvalidAnchorsDoNotRepeatLazyLookups() throws Exception {
        FixtureWorld world = world();
        Grid grid = new Grid();
        Controller source = controller(world, 0, grid);
        Provider station = new Provider();
        grid.add(station.host);
        QuantumNetworkSecurityRegistry.get(world)
            .setSafety(world, component(world, 0), true);
        IGridNode endpoint = source.node;
        source.onLookup = () -> source.node = null;
        assertTrue(QuantumConnectionSecurity.mayBridge(world, 0, 64, 0, endpoint, endpoint, () -> 7));
        assertEquals("A synchronous query cannot advance a booting anchor's readiness", 1, source.nodeLookups);
        assertEquals(1, station.ownerReads);
        assertEquals(1, station.aclReads);

        Controller foreign = controller(world, 32, grid);
        QuantumNetworkSecurityRegistry.get(world)
            .setSafety(world, component(world, 32), true);
        foreign.invalid = true;
        assertFalse(QuantumConnectionSecurity.mayBridge(world, 0, 64, 0, endpoint, endpoint, () -> 7));
        assertEquals("An invalid tile must not be initialized while awaiting removal", 0, foreign.nodeLookups);

        FixtureWorld offWorld = world();
        Controller off = controller(offWorld, 0, new Grid());
        off.onLookup = () -> off.invalid = true;
        assertFalse(
            "An OFF exemption must not survive its anchor becoming invalid during lookup",
            QuantumConnectionSecurity.bypassesNetworkSafety(offWorld, 0, 64, 0, off.node, off.node));
    }

    @Test(timeout = 3000)
    public void replacedAnchorCannotRetainItsFormerGridProvider() throws Exception {
        FixtureWorld world = world();
        Grid oldGrid = new Grid(), splitGrid = new Grid();
        Controller original = controller(world, 0, oldGrid);
        Controller peer = controller(world, 1, splitGrid);
        Provider station = new Provider();
        oldGrid.add(station.host);
        QuantumNetworkSecurityRegistry.get(world)
            .setSafety(world, component(world, 0), true);
        peer.onLookup = () -> {
            original.invalid = true;
            world.tiles.put(QuantumControllerRegistry.pack(0, 64, 0), new TileEntity());
        };
        assertFalse(
            "A later anchor lookup must discard a former anchor's grid instead of retaining its provider",
            QuantumConnectionSecurity.mayBridge(
                world,
                0,
                64,
                0,
                peer.node,
                peer.node,
                () -> { throw new AssertionError("A removed anchor's station cannot authorize this call"); }));
        assertEquals(0, station.ownerReads);
        assertEquals(0, station.aclReads);
    }

    @Test(timeout = 3000)
    public void foreignQuantumHostsIncludeTheirRemoteOnAnchorProviders() throws Exception {
        int dimension = 37174;
        Map<Integer, WorldServer> worlds = (Map<Integer, WorldServer>) field(DimensionManager.class, "worlds")
            .get(null);
        WorldServer previous = worlds.get(dimension);
        try {
            for (int variant = 0; variant < 4; variant++) {
                FixtureWorld world = world();
                world.provider.dimensionId = dimension;
                worlds.put(dimension, world);
                Grid localGrid = new Grid(), remoteGrid = new Grid();
                Controller own = controller(world, 0, localGrid);
                controller(world, 32, remoteGrid);
                QuantumNetworkSecurityRegistry registry = QuantumNetworkSecurityRegistry.get(world);
                registry.setSafety(world, component(world, 0), true);
                registry.setSafety(world, component(world, 32), true);
                Provider local = new Provider(), remote = new Provider();
                if (variant < 2) localGrid.add(local.host);
                if (variant != 1) remoteGrid.add(remote.host);
                remote.owner = variant == 3 ? 1 : -1;
                // A normal quantum host resides in G1 while its controller anchor belongs to G2.
                TileEntityNetworkQuantumNode quantum = (TileEntityNetworkQuantumNode) allocate(
                    TileEntityNetworkQuantumNode.class);
                quantum.setWorldObj(world);
                field(TileEntityNetworkQuantumNode.class, "anchorDim").setInt(quantum, dimension);
                field(TileEntityNetworkQuantumNode.class, "anchorX").setInt(quantum, 32);
                field(TileEntityNetworkQuantumNode.class, "anchorY").setInt(quantum, 64);
                IGridNode endpoint = localGrid.add(quantum);
                int[] actorReads = { 0 };
                assertEquals(
                    "Only one current, authorized station may authorize both scopes; variant=" + variant,
                    variant == 3,
                    QuantumConnectionSecurity.mayBridge(world, 0, 64, 0, own.node, endpoint, () -> {
                        actorReads[0]++;
                        return 7;
                    }));
                assertEquals(0, local.ownerReads);
                assertEquals(0, local.aclReads);
                assertEquals(variant >= 2 ? 1 : 0, remote.ownerReads);
                assertEquals(variant == 3 ? 1 : 0, remote.aclReads);
                assertEquals(variant >= 2 ? 1 : 0, actorReads[0]);
            }
        } finally {
            if (previous == null) worlds.remove(dimension);
            else worlds.put(dimension, previous);
        }
    }

    @Test
    public void inFlightPolicyEpochInvalidatesTheCallsEarlierOffRead() throws Exception {
        FixtureWorld world = world();
        Controller tile = controller(world, 0, new Grid());
        Class<?> contextType = Class.forName(QuantumConnectionSecurity.class.getName() + "$Context");
        Constructor<?> contextConstructor = contextType.getDeclaredConstructor();
        contextConstructor.setAccessible(true);
        Object context = contextConstructor.newInstance();
        Class<?> scopeType = Class.forName(QuantumConnectionSecurity.class.getName() + "$Scope");
        Constructor<?> scopeConstructor = scopeType
            .getDeclaredConstructor(World.class, int.class, int.class, int.class, int.class);
        scopeConstructor.setAccessible(true);
        Object scope = scopeConstructor.newInstance(world, 0, 0, 64, 0);
        Method enabled = contextType.getDeclaredMethod("safetyEnabled", scopeType);
        enabled.setAccessible(true);
        assertEquals(Boolean.FALSE, enabled.invoke(context, scope));
        tile.gtswn$setNetworkSafetyEnabled(true);
        QuantumPolicyEpoch.changed(); // The production controller mixin emits this from its real setter.
        assertEquals(Boolean.TRUE, enabled.invoke(context, scope));
    }

    private static java.util.Set<Long> component(FixtureWorld world, int x) {
        return QuantumControllerRegistry.get(world)
            .registeredStructure(QuantumControllerRegistry.pack(x, 64, 0));
    }

    private static FixtureWorld world() throws Exception {
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        world.tiles = new HashMap<>();
        WorldProvider provider = new WorldProviderSurface();
        provider.dimensionId = 0;
        field(World.class, "provider").set(world, provider);
        field(World.class, "perWorldStorage").set(world, new MapStorage(null));
        return world;
    }

    private static Controller controller(FixtureWorld world, int x, Grid grid) throws Exception {
        Controller tile = (Controller) allocate(Controller.class);
        tile.identity = "";
        tile.xCoord = x;
        tile.yCoord = 64;
        tile.setWorldObj(world);
        world.tiles.put(QuantumControllerRegistry.pack(x, 64, 0), tile);
        tile.node = grid.add(tile);
        QuantumControllerRegistry.get(world)
            .quantize(x, 64, 0);
        return tile;
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

    private static final class Provider {

        int owner = 1, ownerReads, aclReads;
        boolean granted = true;
        final IGridHost host = (IGridHost) Proxy.newProxyInstance(
            IGridHost.class.getClassLoader(),
            new Class<?>[] { IGridHost.class, ISecurityProvider.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getOwner")) {
                    ownerReads++;
                    return owner;
                }
                if (method.getName()
                    .equals("readPermissions")) {
                    aclReads++;
                    if (granted) ((HashMap<Integer, EnumSet<SecurityPermissions>>) args[0])
                        .put(7, EnumSet.of(SecurityPermissions.BUILD));
                }
                return null;
            });
    }

    private static final class Controller extends TileController implements QuantumControllerIdentity {

        String identity;
        IGridNode node;
        boolean safety, invalid;
        int policyReads, nodeLookups;
        Runnable onLookup;

        @Override
        public boolean gtswn$isNetworkSafetyEnabled() {
            policyReads++;
            return safety;
        }

        @Override
        public void gtswn$setNetworkSafetyEnabled(boolean enabled) {
            safety = enabled;
        }

        @Override
        public String gtswn$getControllerIdentity() {
            return identity;
        }

        @Override
        public void gtswn$setControllerIdentity(String value) {
            identity = value;
        }

        @Override
        public void markDirty() {}

        @Override
        public boolean isInvalid() {
            return invalid;
        }

        @Override
        public IGridNode getGridNode(ForgeDirection direction) {
            nodeLookups++;
            Runnable action = onLookup;
            onLookup = null;
            if (action != null) action.run();
            return node;
        }
    }

    private static final class Grid {

        final Map<Class<? extends IGridHost>, List<IGridNode>> machines = new LinkedHashMap<>();
        final IGrid grid = (IGrid) Proxy
            .newProxyInstance(IGrid.class.getClassLoader(), new Class<?>[] { IGrid.class }, (proxy, method, args) -> {
                if (method.getName()
                    .equals("getMachinesClasses")) return new ReadOnlyCollection<>(machines.keySet());
                if (method.getName()
                    .equals("getMachines"))
                    return new Machines((Class<? extends IGridHost>) args[0], machines.get(args[0]));
                return null;
            });

        IGridNode add(IGridHost host) {
            IGridNode node = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(),
                new Class<?>[] { IGridNode.class },
                (proxy, method, args) -> method.getName()
                    .equals("getGrid") ? grid
                        : method.getName()
                            .equals("getMachine") ? host : null);
            machines.computeIfAbsent(host.getClass(), ignored -> new ArrayList<>())
                .add(node);
            return node;
        }

        void remove(IGridNode node) {
            List<IGridNode> entries = machines.get(
                node.getMachine()
                    .getClass());
            entries.removeIf(value -> value == node);
            if (entries.isEmpty()) machines.remove(
                node.getMachine()
                    .getClass());
        }
    }

    private static final class Machines extends ReadOnlyCollection<IGridNode> implements IMachineSet {

        private final Class<? extends IGridHost> type;

        Machines(Class<? extends IGridHost> type, List<IGridNode> nodes) {
            super(nodes);
            this.type = type;
        }

        @Override
        public Class<? extends IGridHost> getMachineClass() {
            return type;
        }
    }

    private static final class FixtureWorld extends WorldServer {

        Map<Long, TileEntity> tiles;

        private FixtureWorld() {
            super(null, (ISaveHandler) null, "fixture", 0, (WorldSettings) null, (Profiler) null);
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
            return tiles.containsKey(QuantumControllerRegistry.pack(x, y, z));
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return tiles.get(QuantumControllerRegistry.pack(x, y, z));
        }
    }
}
