package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
import appeng.api.networking.IGridConnection;
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
        QuantumConnectionSecurity.clearConnections();
    }

    @After
    public void restoreSafety() {
        QuantumConnectionSecurity.clearConnections();
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

    @Test
    public void productionRestoreSharesOneDirtyBatchAndDoesNoWorldQueriesOnUnchangedTicks() throws Exception {
        try (MaintenanceFixture fixture = new MaintenanceFixture(true, 48, 12)) {
            fixture.resetCounts();
            QuantumConnectionSecurity.restoreNativeChecks();
            assertTrue("One dirty batch must share its controller scan", fixture.grid.machineReads < 12);
            assertTrue("Controller reads must be linear in controllers plus edges", fixture.world.tileReads < 300);
            assertEquals(0, fixture.destroyed);
            // Initial policy synchronization is itself a real event; settle it before counting steady ticks.
            QuantumConnectionSecurity.restoreNativeChecks();
            fixture.resetCounts();
            for (int tick = 0; tick < 40; tick++) QuantumConnectionSecurity.restoreNativeChecks();
            assertEquals("Unchanged production ticks must not enumerate native machines", 0, fixture.grid.machineReads);
            assertEquals("Unchanged production ticks must not touch the world", 0, fixture.world.tileReads);
        }
    }

    @Test
    public void liveOverrideShortcutRequiresBothAuditedEdgesAndEveryCurrentFactVersion() throws Exception {
        try (MaintenanceFixture fixture = new MaintenanceFixture(true, 12, 3)) {
            IGridConnection first = fixture.edges.get(0), second = fixture.edges.get(1);
            assertFalse(QuantumConnectionSecurity.hasUnchangedOverride(first, second));
            QuantumConnectionSecurity.restoreNativeChecks();
            QuantumConnectionSecurity.restoreNativeChecks();
            fixture.resetCounts();
            for (int tick = 0; tick < 40; tick++) {
                assertTrue(QuantumConnectionSecurity.hasUnchangedOverride(first, second));
                QuantumConnectionSecurity.restoreNativeChecks();
            }
            assertEquals(0, fixture.world.tileReads);
            assertEquals(0, fixture.grid.machineReads);
            assertFalse(
                "A direct/unknown edge has no proven two-edge OFF bridge",
                QuantumConnectionSecurity.hasUnchangedOverride(first, null));
            QuantumPolicyEpoch.changed();
            assertFalse(QuantumConnectionSecurity.hasUnchangedOverride(first, second));
            QuantumConnectionSecurity.restoreNativeChecks();
            assertTrue(QuantumConnectionSecurity.hasUnchangedOverride(first, second));
            QuantumTerminalSyncEpoch.changed();
            assertFalse(QuantumConnectionSecurity.hasUnchangedOverride(first, second));
            QuantumConnectionSecurity.restoreNativeChecks();
            fixture.addEdge();
            assertFalse(QuantumConnectionSecurity.hasUnchangedOverride(first, second));
            QuantumConnectionSecurity.restoreNativeChecks();
            assertTrue(QuantumConnectionSecurity.hasUnchangedOverride(first, second));
            first.destroy();
            assertFalse(QuantumConnectionSecurity.hasUnchangedOverride(first, second));
            Config.quantumSafetyEnabled = false;
            assertTrue(QuantumConnectionSecurity.hasUnchangedOverride(null, null));
        }
    }

    @Test
    public void hookedLazyLookupStillReplacesProviderInventoryWithinTheSameNativeGrid() throws Exception {
        FixtureWorld world = world();
        Grid grid = new Grid(true);
        Controller controller = controller(world, 0, grid);
        QuantumNetworkSecurityRegistry.get(world)
            .setSafety(world, component(world, 0), true);
        Provider initial = new Provider(), replacement = new Provider();
        replacement.owner = -1;
        IGridNode initialNode = grid.add(initial.host);
        controller.onLookup = () -> {
            grid.remove(initialNode);
            grid.add(replacement.host);
        };
        assertFalse(QuantumConnectionSecurity.mayBridge(world, 0, 64, 0, controller.node, controller.node, () -> 7));
        assertEquals(0, initial.ownerReads);
        assertEquals(0, initial.aclReads);
        assertEquals(1, replacement.ownerReads);
    }

    @Test
    public void untrackedNativeGridsKeepTheConservativeProductionPolling() throws Exception {
        try (MaintenanceFixture fixture = new MaintenanceFixture(false, 8, 3)) {
            QuantumConnectionSecurity.restoreNativeChecks();
            assertFalse(QuantumConnectionSecurity.hasUnchangedOverride(fixture.edges.get(0), fixture.edges.get(1)));
            fixture.resetCounts();
            QuantumConnectionSecurity.restoreNativeChecks();
            assertTrue(
                "Missing native hooks must never silently enable the event-only shortcut",
                fixture.world.tileReads > 0);
            fixture.own.gtswn$setNetworkSafetyEnabled(true);
            QuantumConnectionSecurity.restoreNativeChecks();
            assertEquals(3, fixture.destroyed);
        }
    }

    @Test
    public void partiallyLoadedComponentSleepsUntilItsRealControllerLifecycleChanges() throws Exception {
        try (MaintenanceFixture fixture = new MaintenanceFixture(true, 16, 4)) {
            Controller missing = fixture.controllers.get(15);
            long position = QuantumControllerRegistry.pack(missing.xCoord, 64, 0);
            fixture.world.tiles.remove(position);
            fixture.grid.remove(missing.node);
            QuantumPolicyEpoch.changed();
            QuantumConnectionSecurity.restoreNativeChecks();
            QuantumConnectionSecurity.restoreNativeChecks();
            fixture.resetCounts();
            for (int tick = 0; tick < 20; tick++) QuantumConnectionSecurity.restoreNativeChecks();
            assertEquals(
                "An unloaded saved member must not restart whole-component polling",
                0,
                fixture.world.tileReads);
            fixture.world.tiles.put(position, missing);
            missing.node = fixture.grid.add(missing);
            missing.gtswn$setNetworkSafetyEnabled(true);
            QuantumPolicyEpoch.changed(); // Real validate/readFromNBT emits this too.
            QuantumConnectionSecurity.restoreNativeChecks();
            assertEquals("A loaded ON member must revoke the old OFF edges immediately", 4, fixture.destroyed);
        }
    }

    @Test
    public void unchangedNativeSecurityKeyStillObservesTwoToThreeProvidersAndAclRevocation() throws Exception {
        try (MaintenanceFixture fixture = new MaintenanceFixture(true, 8, 4)) {
            Provider first = new Provider(), second = new Provider();
            IGridNode firstNode = fixture.grid.add(first.host), secondNode = fixture.grid.add(second.host);
            QuantumConnectionSecurity.restoreNativeChecks();
            QuantumConnectionSecurity.restoreNativeChecks();
            fixture.resetCounts();
            Provider remaining = new Provider();
            fixture.grid.add(remaining.host);
            assertFalse(QuantumConnectionSecurity.hasUnchangedOverride(fixture.edges.get(0), fixture.edges.get(1)));
            // Native SecurityCache has key=-1 both before and after 2 -> 3 and emits no security event.
            QuantumConnectionSecurity.restoreNativeChecks();
            assertTrue(
                "Membership must dirty the production sweep even without a key-change event",
                fixture.grid.machineReads > 0);
            assertEquals(0, fixture.destroyed);
            fixture.grid.remove(firstNode);
            fixture.grid.remove(secondNode);
            remaining.granted = false;
            fixture.grid.grid.postEvent(new appeng.api.networking.events.MENetworkSecurityChange());
            fixture.own.gtswn$setNetworkSafetyEnabled(true);
            QuantumConnectionSecurity.restoreNativeChecks();
            assertEquals(4, fixture.destroyed);
            assertTrue("The currently installed native provider ACL must be read", remaining.aclReads > 0);
        }
    }

    @Test
    public void policyChangePartwayThroughProductionBatchRemainsPendingForEarlierEdges() throws Exception {
        try (MaintenanceFixture fixture = new MaintenanceFixture(true, 24, 12)) {
            Controller foreign = controller(fixture.world, 128, fixture.grid);
            QuantumNetworkSecurityRegistry.get(fixture.world)
                .setSafety(fixture.world, component(fixture.world, 128), false);
            QuantumConnectionSecurity.restoreNativeChecks();
            QuantumConnectionSecurity.restoreNativeChecks();
            fixture.resetCounts();
            QuantumSecurityTopology.changed();
            fixture.world.onRead = () -> {
                if (fixture.world.tileReads >= 70) {
                    fixture.world.onRead = null;
                    foreign.gtswn$setNetworkSafetyEnabled(true);
                }
            };
            QuantumConnectionSecurity.restoreNativeChecks();
            assertNull("Mutation must actually occur inside the batch", fixture.world.onRead);
            QuantumConnectionSecurity.restoreNativeChecks();
            assertEquals("Earlier accepted edges cannot consume a later invalidation", 12, fixture.destroyed);
        }
    }

    @Test
    public void destroyedAndNewlyRecordedEdgesCannotConsumeEachOthersDirtySignal() throws Exception {
        try (MaintenanceFixture fixture = new MaintenanceFixture(true, 8, 3)) {
            QuantumConnectionSecurity.restoreNativeChecks();
            QuantumConnectionSecurity.restoreNativeChecks();
            fixture.edges.get(0)
                .destroy();
            fixture.addEdge();
            fixture.resetCounts();
            QuantumConnectionSecurity.restoreNativeChecks();
            assertTrue("A new constructor admission must dirty the production sweep", fixture.world.tileReads > 0);
            assertEquals(1, fixture.destroyed);
            fixture.own.gtswn$setNetworkSafetyEnabled(true);
            QuantumConnectionSecurity.restoreNativeChecks();
            assertEquals(4, fixture.destroyed);
        }
    }

    @Test
    public void pauseResumeKeepsTheOldNodeGenerationAndRechecksItsRebuiltEdge() throws Exception {
        try (MaintenanceFixture fixture = new MaintenanceFixture(true, 8, 2)) {
            QuantumControllerRegistry registry = QuantumControllerRegistry.get(fixture.world);
            long origin = QuantumControllerRegistry.pack(0, 64, 0);
            String generation = registry.canonicalAnchor(origin).generation;
            IGridNode oldEndpoint = fixture.edges.get(0)
                .a();
            TileEntityNetworkQuantumNode oldNode = (TileEntityNetworkQuantumNode) oldEndpoint.getMachine();
            QuantumConnectionSecurity.restoreNativeChecks();
            QuantumConnectionSecurity.restoreNativeChecks();
            assertTrue(registry.suspendStructure(origin));
            QuantumConnectionSecurity.restoreNativeChecks();
            assertEquals(2, fixture.destroyed);
            assertEquals(
                "Pausing must not retire the node's durable generation",
                generation,
                oldNode.getAnchorGeneration());
            assertTrue(registry.quantizeAll(component(fixture.world, 0), origin));
            assertEquals(generation, registry.canonicalAnchor(origin).generation);
            assertTrue(QuantumConnectionSecurity.bypassesNativeCheck(oldEndpoint, fixture.own.node));
            fixture.addEdge(oldEndpoint);
            fixture.resetCounts();
            QuantumConnectionSecurity.restoreNativeChecks();
            assertTrue(
                "Resume and a rebuilt admission must not be skipped by the old stamp",
                fixture.world.tileReads > 0);
            assertEquals("The same old node may reconnect after resume", 2, fixture.destroyed);
        }
    }

    /** Production entry point fixture: no modeled maintenance loop, TTL or precomputed verdict. */
    static final class MaintenanceFixture implements AutoCloseable {

        final FixtureWorld world = world();
        final Grid grid;
        final List<Controller> controllers = new ArrayList<>();
        final List<IGridConnection> edges = new ArrayList<>();
        final Map<Integer, WorldServer> worlds;
        final WorldServer previous;
        final Controller own;
        int destroyed;

        MaintenanceFixture(boolean tracked, int controllerCount, int edgeCount) throws Exception {
            QuantumConnectionSecurity.clearConnections();
            world.provider.dimensionId = 37175;
            worlds = (Map<Integer, WorldServer>) field(DimensionManager.class, "worlds").get(null);
            previous = worlds.put(37175, world);
            grid = new Grid(tracked);
            for (int i = 0; i < controllerCount; i++) controllers.add(controller(world, i, grid));
            own = controllers.get(0);
            QuantumNetworkSecurityRegistry.get(world)
                .setSafety(world, component(world, 0), false);
            for (int i = 0; i < edgeCount; i++) addEdge();
        }

        void resetCounts() {
            world.tileReads = 0;
            grid.machineReads = 0;
        }

        void addEdge() throws Exception {
            MaintenanceNode quantum = (MaintenanceNode) allocate(MaintenanceNode.class);
            quantum.setWorldObj(world);
            field(TileEntityNetworkQuantumNode.class, "anchorDim").setInt(quantum, 37175);
            field(TileEntityNetworkQuantumNode.class, "anchorY").setInt(quantum, 64);
            field(TileEntityNetworkQuantumNode.class, "anchorGeneration").set(
                quantum,
                QuantumControllerRegistry.get(world)
                    .canonicalAnchor(QuantumControllerRegistry.pack(0, 64, 0)).generation);
            quantum.nativeNode = grid.add(quantum);
            addEdge(quantum.nativeNode);
        }

        void addEdge(IGridNode a) {
            IGridNode b = own.node;
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
                        destroyed++;
                        grid.connections.get(a)
                            .removeIf(value -> value == proxy);
                        grid.connections.get(b)
                            .removeIf(value -> value == proxy);
                        if (grid.tracked) QuantumSecurityTopology.membershipChanged(a);
                    }
                    return null;
                });
            grid.connections.get(a)
                .add(edge);
            grid.connections.get(b)
                .add(edge);
            edges.add(edge);
            QuantumConnectionSecurity.recordActual(edge, true);
        }

        @Override
        public void close() {
            QuantumConnectionSecurity.clearConnections();
            if (previous == null) worlds.remove(37175);
            else worlds.put(37175, previous);
        }
    }

    private static final class MaintenanceNode extends TileEntityNetworkQuantumNode {

        IGridNode nativeNode;

        @Override
        public int getNativeOwnerID() {
            return 7;
        }

        @Override
        public IGridNode getCoreGridNode() {
            return nativeNode;
        }
    }

    private static java.util.Set<Long> component(FixtureWorld world, int x) {
        return QuantumControllerRegistry.get(world)
            .registeredStructure(QuantumControllerRegistry.pack(x, 64, 0));
    }

    static FixtureWorld world() throws Exception {
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        world.tiles = new HashMap<>();
        WorldProvider provider = new WorldProviderSurface();
        provider.dimensionId = 0;
        field(World.class, "provider").set(world, provider);
        field(World.class, "perWorldStorage").set(world, new MapStorage(null));
        return world;
    }

    static Controller controller(FixtureWorld world, int x, Grid grid) throws Exception {
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

    static final class Controller extends TileController
        implements QuantumControllerIdentity, QuantumPolicyEpoch.TrackedController {

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
            if (safety == enabled) return;
            safety = enabled;
            QuantumPolicyEpoch.changed();
        }

        @Override
        public String gtswn$getControllerIdentity() {
            return identity;
        }

        @Override
        public void gtswn$setControllerIdentity(String value) {
            if (value.equals(identity)) return;
            identity = value;
            QuantumPolicyEpoch.changed();
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

    static final class Grid {

        final Map<Class<? extends IGridHost>, List<IGridNode>> machines = new LinkedHashMap<>();
        final Map<IGridNode, List<IGridConnection>> connections = new IdentityHashMap<>();
        final IGrid grid;
        final boolean tracked;
        int machineReads;

        Grid() {
            this(false);
        }

        Grid(boolean tracked) {
            this.tracked = tracked;
            grid = (IGrid) Proxy.newProxyInstance(
                IGrid.class.getClassLoader(),
                tracked ? new Class<?>[] { IGrid.class, QuantumSecurityTopology.TrackedGrid.class }
                    : new Class<?>[] { IGrid.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("getMachinesClasses")) {
                        machineReads++;
                        return new ReadOnlyCollection<>(machines.keySet());
                    }
                    if (method.getName()
                        .equals("getMachines")) {
                        machineReads++;
                        return new Machines((Class<? extends IGridHost>) args[0], machines.get(args[0]));
                    }
                    if (method.getName()
                        .equals("postEvent") && args[0] instanceof appeng.api.networking.events.MENetworkSecurityChange
                        && tracked) QuantumSecurityTopology.changed();
                    return null;
                });
        }

        IGridNode add(IGridHost host) {
            List<IGridConnection> edges = new ArrayList<>();
            IGridNode node = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(),
                new Class<?>[] { IGridNode.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("getGrid")) return grid;
                    if (method.getName()
                        .equals("getMachine")) return host;
                    if (method.getName()
                        .equals("getConnections")) return new ReadOnlyCollection<>(edges);
                    if (method.getName()
                        .equals("getPlayerID")) return 7;
                    if (method.getName()
                        .equals("equals")) return proxy == args[0];
                    if (method.getName()
                        .equals("hashCode")) return System.identityHashCode(proxy);
                    return null;
                });
            if (tracked) QuantumSecurityTopology.membershipChanged(node);
            connections.put(node, edges);
            machines.computeIfAbsent(host.getClass(), ignored -> new ArrayList<>())
                .add(node);
            return node;
        }

        void remove(IGridNode node) {
            if (tracked) QuantumSecurityTopology.membershipChanged(node);
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

    static final class FixtureWorld extends WorldServer {

        Map<Long, TileEntity> tiles;
        int tileReads;
        Runnable onRead;

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
            tileReads++;
            if (onRead != null) onRead.run();
            return tiles.get(QuantumControllerRegistry.pack(x, y, z));
        }
    }
}
