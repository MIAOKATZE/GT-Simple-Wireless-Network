package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.BlockEvent;

import org.junit.Test;

import appeng.api.networking.IGrid;
import appeng.api.networking.IMachineSet;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;

/** Real Forge placement cancellation state and AE proxy sides; no fake passage of server ticks. */
public class QuantumControllerPlacementTest {

    @Test
    public void successfulEndCommitEnrollsWholeUnregisteredCompanionGroupAndKeepsFirstAnchor() throws Exception {
        FixtureWorld world = world();
        Controller first = controller(world, 0);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        String generation = registry.canonicalAnchor(pos(0)).generation;
        Controller placed = controller(world, 1), companion = controller(world, 2);
        QuantumControllerEventHandler handler = new QuantumControllerEventHandler();
        handler.onBlockPlace(event(world, 1));
        assertFalse(registry.isQuantized(pos(1)));
        assertTrue(
            placed.proxy.getConnectableSides()
                .isEmpty());
        assertTrue(
            companion.proxy.getConnectableSides()
                .isEmpty());
        assertFalse(
            first.proxy.getConnectableSides()
                .contains(ForgeDirection.EAST));
        handler.commitPendingPlacements();
        assertEquals(
            3,
            registry.getAll()
                .size());
        assertEquals(pos(0), registry.canonicalAnchor(pos(2)).position);
        assertEquals(generation, registry.canonicalAnchor(pos(2)).generation);
        assertEquals(EnumSet.of(ForgeDirection.WEST, ForgeDirection.EAST), placed.proxy.getConnectableSides());
        assertTrue(
            first.proxy.getConnectableSides()
                .contains(ForgeDirection.EAST));
    }

    @Test
    public void laterListenerCancellationNeverEnrollsOrRetiresOriginalGeneration() throws Exception {
        FixtureWorld world = world();
        Controller first = controller(world, 0), placed = controller(world, 1);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        String generation = registry.canonicalAnchor(pos(0)).generation;
        QuantumControllerEventHandler handler = new QuantumControllerEventHandler();
        BlockEvent.PlaceEvent event = event(world, 1);
        handler.onBlockPlace(event);
        // A protection listener runs after this handler and vanilla then rolls the placement back.
        event.setCanceled(true);
        world.tiles.remove(pos(1));
        assertFalse(registry.ownsController(placed));
        QuantumTileLifecycle.removed(placed);
        handler.commitPendingPlacements();
        assertTrue(registry.ownsController(first));
        assertEquals(Collections.singleton(pos(0)), registry.getAll());
        assertEquals(generation, registry.canonicalAnchor(pos(0)).generation);
    }

    @Test
    public void replacementBeforeCommitIsNotEnrolledAndLateOldInvalidationCannotClaimNewTile() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        Controller old = controller(world, 1);
        QuantumControllerEventHandler handler = new QuantumControllerEventHandler();
        handler.onBlockPlace(event(world, 1));
        Controller replacement = controller(world, 1);
        handler.commitPendingPlacements();
        assertFalse(registry.isQuantized(pos(1)));
        assertFalse(registry.ownsController(old));
        handler.onBlockPlace(event(world, 1));
        handler.commitPendingPlacements();
        assertTrue(registry.ownsController(replacement));
        assertFalse(registry.ownsController(old));
        QuantumTileLifecycle.removed(old);
        assertEquals(
            2,
            registry.getAll()
                .size());
    }

    @Test
    public void realPlacementCoalescesPhysicallyJoinedGenerationsAndControllerFaces() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        controller(world, 2);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        registry.quantize(2, 64, 0);
        controller(world, 1);
        BlockEvent.PlaceEvent event = event(world, 1);
        QuantumControllerEventHandler handler = new QuantumControllerEventHandler();
        handler.onBlockPlace(event);
        assertFalse(event.isCanceled());
        handler.commitPendingPlacements();
        assertTrue(registry.isQuantized(pos(1)));
        assertEquals(pos(0), registry.canonicalAnchor(pos(0)).position);
        assertSame(registry.canonicalAnchor(pos(0)), registry.canonicalAnchor(pos(2)));
        assertTrue(
            ((Controller) world.tiles.get(pos(1))).proxy.getConnectableSides()
                .contains(ForgeDirection.EAST));
        assertTrue(
            ((Controller) world.tiles.get(pos(1))).proxy.getConnectableSides()
                .contains(ForgeDirection.WEST));
    }

    @Test
    public void replacingRemovedMiddleRejoinsAlreadyNormalizedComponentsThroughTheRealPlacementHandler()
        throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        Controller middle = controller(world, 1);
        controller(world, 2);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantizeAll(new HashSet<>(Arrays.asList(pos(0), pos(1), pos(2))), pos(1));
        world.tiles.remove(pos(1));
        world.loadedAir = new HashSet<>(Collections.singleton(pos(1)));
        QuantumTileLifecycle.removed(middle);
        assertEquals(pos(0), registry.canonicalAnchor(pos(0)).position);
        assertEquals(pos(2), registry.canonicalAnchor(pos(2)).position);
        assertNotEquals(registry.canonicalAnchor(pos(0)).generation, registry.canonicalAnchor(pos(2)).generation);
        Controller replacement = controller(world, 1);
        QuantumControllerEventHandler handler = new QuantumControllerEventHandler();
        BlockEvent.PlaceEvent placement = event(world, 1);
        handler.onBlockPlace(placement);
        assertFalse(placement.isCanceled());
        handler.commitPendingPlacements();
        assertTrue(registry.isQuantized(pos(0)) && registry.isQuantized(pos(1)) && registry.isQuantized(pos(2)));
        assertSame(registry.canonicalAnchor(pos(0)), registry.canonicalAnchor(pos(2)));
        assertEquals(EnumSet.of(ForgeDirection.WEST, ForgeDirection.EAST), replacement.proxy.getConnectableSides());
    }

    @Test
    public void activeAndPausedNeighborsAllowPhysicalPlacementWithoutAutomaticResumption() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        controller(world, 2);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        registry.quantize(2, 64, 0);
        String paused = registry.canonicalAnchor(pos(2)).generation;
        assertTrue(registry.suspendStructure(pos(2)));
        Controller placed = controller(world, 1);
        QuantumControllerEventHandler handler = new QuantumControllerEventHandler();
        BlockEvent.PlaceEvent placement = event(world, 1);
        handler.onBlockPlace(placement);
        assertFalse(placement.isCanceled());
        handler.commitPendingPlacements();
        assertSame(placed, world.tiles.get(pos(1)));
        assertFalse(placed.isInvalid());
        assertTrue(registry.isQuantized(pos(0)));
        assertFalse(registry.isQuantized(pos(1)));
        assertTrue(registry.isSuspendedAnchor(pos(2), paused));
    }

    @Test
    public void statsAliasesShareSnapshotButDifferentActualGridAndUnloadedFirstAnchorDoNot() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        controller(world, 1);
        QuantumControllerRegistry.get(world)
            .quantizeAll(new HashSet<>(Arrays.asList(pos(0), pos(1))), pos(1));
        IGrid grid = grid(), otherGrid = grid();
        try {
            QuantumNetworkStatsCache.clear();
            QuantumNetworkStatsCache.Snapshot first = QuantumNetworkStatsCache
                .getOrCompute(new AnchorKey(0, 0, 64, 0), world, grid);
            assertNotNull(first);
            assertSame(first, QuantumNetworkStatsCache.getOrCompute(new AnchorKey(0, 1, 64, 0), world, grid));
            assertNotSame(first, QuantumNetworkStatsCache.getOrCompute(new AnchorKey(0, 0, 64, 0), world, otherGrid));
            world.tiles.remove(pos(1));
            QuantumNetworkStatsCache.clear();
            assertNull(QuantumNetworkStatsCache.getOrCompute(new AnchorKey(0, 0, 64, 0), world, grid));
        } finally {
            QuantumNetworkStatsCache.clear();
        }
    }

    @Test
    public void expansionRejectsInPlaceIdentityMutationWithoutRefreshingOldEvidence() throws Exception {
        FixtureWorld world = world();
        Controller first = controller(world, 0);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        String expected = first.identity;
        first.gtswn$setControllerIdentity("changed-by-nbt-reload");
        controller(world, 1);
        QuantumControllerEventHandler handler = new QuantumControllerEventHandler();
        handler.onBlockPlace(event(world, 1));
        handler.commitPendingPlacements();
        assertFalse(registry.isQuantized(pos(1)));
        assertFalse(registry.isCurrentController(world, pos(0)));
        assertEquals(expected, evidence(registry, "identities").get(pos(0)));
        assertSame(first, evidence(registry, "loadedTiles").get(pos(0)));
    }

    @Test
    public void expansionRejectsReplacementAndLateOldRemovalStillRetiresItsOriginalGeneration() throws Exception {
        FixtureWorld world = world();
        Controller first = controller(world, 0);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        String expected = first.identity;
        controller(world, 0);
        controller(world, 1);
        QuantumControllerEventHandler handler = new QuantumControllerEventHandler();
        handler.onBlockPlace(event(world, 1));
        handler.commitPendingPlacements();
        assertFalse(registry.isQuantized(pos(1)));
        assertFalse(registry.isCurrentController(world, pos(0)));
        assertEquals(expected, evidence(registry, "identities").get(pos(0)));
        assertSame(first, evidence(registry, "loadedTiles").get(pos(0)));
        QuantumTileLifecycle.removed(first);
        assertFalse(registry.isQuantized(pos(0)));
    }

    private static Map<?, ?> evidence(QuantumControllerRegistry registry, String name) throws Exception {
        Field field = QuantumControllerRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(registry);
    }

    private static IGrid grid() {
        IMachineSet empty = (IMachineSet) Proxy.newProxyInstance(
            IMachineSet.class.getClassLoader(),
            new Class<?>[] { IMachineSet.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("size")) return 0;
                if (method.getName()
                    .equals("iterator"))
                    return Collections.emptyList()
                        .iterator();
                return null;
            });
        return (IGrid) Proxy.newProxyInstance(
            IGrid.class.getClassLoader(),
            new Class<?>[] { IGrid.class },
            (proxy, method, args) -> method.getName()
                .equals("getMachines") ? empty : null);
    }

    private static BlockEvent.PlaceEvent event(FixtureWorld world, int x) throws Exception {
        BlockSnapshot snapshot = new BlockSnapshot(0, x, 64, 0, "minecraft", "air", 0, 3, null);
        snapshot.world = world;
        return new CancellablePlaceEvent(snapshot, (Player) allocate(Player.class));
    }

    private static class CancellablePlaceEvent extends BlockEvent.PlaceEvent {

        CancellablePlaceEvent(BlockSnapshot snapshot, Player player) {
            super(snapshot, null, player);
        }

        @Override
        public boolean isCancelable() {
            return true;
        }
    }

    private static class Player extends EntityPlayerMP {

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public ItemStack getCurrentEquippedItem() {
            return null;
        }
    }

    static Controller controller(FixtureWorld world, int x) throws Exception {
        Controller controller = (Controller) allocate(Controller.class);
        TileEntity tile = controller;
        tile.xCoord = x;
        tile.yCoord = 64;
        tile.setWorldObj(world);
        controller.identity = "";
        controller.proxy = new AENetworkProxy((IGridProxyable) (TileEntity) controller, "proxy", null, true);
        world.tiles.put(pos(x), controller);
        return controller;
    }

    static long pos(int x) {
        return QuantumControllerRegistry.pack(x, 64, 0);
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), type);
    }

    static FixtureWorld world() throws Exception {
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        world.tiles = new HashMap<>();
        Field provider = World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new WorldProviderSurface());
        Field storage = World.class.getDeclaredField("perWorldStorage");
        storage.setAccessible(true);
        storage.set(world, new MapStorage(null));
        world.playerEntities = Collections.emptyList();
        return world;
    }

    static class Controller extends TileController implements QuantumControllerIdentity {

        AENetworkProxy proxy;
        String identity;
        boolean safety;
        appeng.api.networking.IGridNode nativeNode;

        @Override
        public appeng.api.networking.IGridNode getGridNode(ForgeDirection direction) {
            return nativeNode;
        }

        @Override
        public AENetworkProxy getProxy() {
            return proxy;
        }

        @Override
        public void markDirty() {}

        @Override
        public String gtswn$getControllerIdentity() {
            return identity;
        }

        @Override
        public void gtswn$setControllerIdentity(String identity) {
            this.identity = identity;
        }

        @Override
        public boolean gtswn$isNetworkSafetyEnabled() {
            return safety;
        }

        @Override
        public void gtswn$setNetworkSafetyEnabled(boolean enabled) {
            safety = enabled;
        }
    }

    static class FixtureWorld extends World {

        Map<Long, TileEntity> tiles;
        java.util.Set<Long> unloaded = new HashSet<>();
        java.util.Set<Long> loadedAir = new HashSet<>();

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
            long position = QuantumControllerRegistry.pack(x, y, z);
            return (unloaded == null || !unloaded.contains(position))
                && (tiles.containsKey(position) || loadedAir != null && loadedAir.contains(position));
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return tiles.get(QuantumControllerRegistry.pack(x, y, z));
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return null;
        }

        @Override
        public boolean canMineBlock(EntityPlayer player, int x, int y, int z) {
            return true;
        }

        @Override
        public long getTotalWorldTime() {
            return 0;
        }
    }
}
