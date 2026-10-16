package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;

import org.junit.Test;

import appeng.tile.networking.TileController;

/** Cache behavior only: these stubs explicitly model notifications; native tests must exercise the actual mixin. */
public class QuantumPolicyStateCacheTest {

    @Test
    public void steadyOffAndOnReadNoControllerTilesAfterTheFirstAggregate() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        controller(world, 1);
        Set<Long> members = component(world);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        assertFalse(policy.isSafetyEnabled(world, members));
        world.resetReads();
        for (int i = 0; i < 20; i++) assertFalse(policy.isSafetyEnabled(world, members));
        assertEquals(0, world.tileReads);
        assertEquals(0, world.blockReads);
        policy.setSafety(world, members, true);
        assertTrue(policy.isSafetyEnabled(world, members));
        world.resetReads();
        for (int i = 0; i < 20; i++) assertTrue(policy.isSafetyEnabled(world, members));
        assertEquals(0, world.tileReads);
        assertEquals(0, world.blockReads);
    }

    @Test
    public void controllerSetterImmediatelyRevokesWarmOff() throws Exception {
        FixtureWorld world = world();
        Controller tile = controller(world, 0);
        Set<Long> members = component(world);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        assertFalse(policy.isSafetyEnabled(world, members));
        tile.gtswn$setNetworkSafetyEnabled(true);
        assertTrue(policy.isSafetyEnabled(world, members));
    }

    @Test
    public void plainUnitStubsWithoutNotificationHooksAlwaysUseTheScan() throws Exception {
        FixtureWorld world = world();
        UntrackedController tile = (UntrackedController) allocate(UntrackedController.class);
        place(world, tile, 0);
        Set<Long> members = component(world);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        assertFalse(policy.isSafetyEnabled(world, members));
        tile.safety = true; // Deliberately no mixin and no explicit invalidation.
        world.resetReads();
        assertTrue(policy.isSafetyEnabled(world, members));
        assertTrue(world.tileReads > 0);
    }

    @Test
    public void equalCallerOwnedSetsCannotPopulateTheSharedComponentCache() throws Exception {
        FixtureWorld world = world();
        Controller tile = controller(world, 0);
        Set<Long> members = new HashSet<>(component(world));
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        assertFalse(policy.isSafetyEnabled(world, members));
        tile.safety = true; // A caller-owned set must still scan even without a notification.
        world.resetReads();
        assertTrue(policy.isSafetyEnabled(world, members));
        assertTrue(world.tileReads > 0);
    }

    @Test
    public void loadedPolicyReloadAndWorldlessLoadInvalidateWarmOff() throws Exception {
        FixtureWorld world = world();
        Controller tile = controller(world, 0);
        Set<Long> members = component(world);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        assertFalse(policy.isSafetyEnabled(world, members));
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("GTSWN_NetworkSafetyEnabled", true);
        tile.readPolicy(tag);
        assertTrue(policy.isSafetyEnabled(world, members));
        Controller worldless = (Controller) allocate(Controller.class);
        long epoch = QuantumPolicyEpoch.current();
        worldless.readPolicy(tag);
        assertTrue(QuantumPolicyEpoch.current() > epoch);
        // The lifecycle hook must work before the tile has a world as well.
        epoch = QuantumPolicyEpoch.current();
        QuantumTileLifecycle.validated(worldless);
        assertTrue(QuantumPolicyEpoch.current() > epoch);
    }

    @Test
    public void missingMemberNeverCachesAndLateOnLoadIsObserved() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        Controller late = controller(world, 1);
        Set<Long> members = component(world);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        assertFalse(policy.isSafetyEnabled(world, members));
        QuantumTileLifecycle.unloaded(late);
        world.tiles.remove(position(1));
        assertFalse(policy.isSafetyEnabled(world, members));
        world.resetReads();
        assertFalse(policy.isSafetyEnabled(world, members));
        assertTrue(world.blockReads > 0);
        late.gtswn$setNetworkSafetyEnabled(true);
        world.tiles.put(position(1), late);
        QuantumTileLifecycle.validated(late);
        assertTrue(policy.isSafetyEnabled(world, members));
    }

    @Test
    public void sameCoordinateReplacementCannotReuseWarmOnOrTheOldGenerationPolicy() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        Set<Long> members = component(world);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        policy.setSafety(world, members, true);
        assertTrue(policy.isSafetyEnabled(world, members));
        Controller replacement = controller(world, 0);
        QuantumTileLifecycle.validated(replacement);
        assertFalse(policy.isSafetyEnabled(world, members));
        assertFalse(replacement.safety);
    }

    @Test
    public void unloadedPendingOffDoesNotReboundWhenTheOldOnTileReturns() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        Controller returning = controller(world, 1);
        Set<Long> members = component(world);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        policy.setSafety(world, members, true);
        assertTrue(policy.isSafetyEnabled(world, members));
        QuantumTileLifecycle.unloaded(returning);
        world.tiles.remove(position(1));
        policy.setSafety(world, members, false);
        assertTrue(returning.safety);
        world.tiles.put(position(1), returning);
        QuantumTileLifecycle.validated(returning);
        assertFalse(policy.isSafetyEnabled(world, members));
        assertFalse(returning.safety);
        world.resetReads();
        assertFalse(policy.isSafetyEnabled(world, members));
        assertEquals(0, world.tileReads);
    }

    @Test
    public void structuralExtensionInheritsOnAndDiscardsTheOldComponentEntry() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        Set<Long> old = component(world);
        policy.setSafety(world, old, true);
        assertTrue(policy.isSafetyEnabled(world, old));
        Controller added = controller(world, 1);
        Set<Long> expanded = component(world);
        assertNotSame(old, expanded);
        assertTrue(policy.isSafetyEnabled(world, expanded));
        assertTrue(added.safety);
        assertEquals(1, cachedComponents(policy));
    }

    @Test
    public void savedPolicyReloadAndWorldUnloadReleaseCachedSnapshots() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        Set<Long> members = component(world);
        assertFalse(policy.isSafetyEnabled(world, members));
        assertEquals(1, cachedComponents(policy));
        NBTTagCompound tag = new NBTTagCompound();
        policy.writeToNBT(tag);
        policy.readFromNBT(tag);
        assertEquals(0, cachedComponents(policy));
        assertFalse(policy.isSafetyEnabled(world, members));
        QuantumTileLifecycle.worldUnloading(world);
        assertEquals(0, cachedComponents(policy));
    }

    @Test
    public void policyEpochChangesDoNotRetainEntriesFromOlderEpochs() throws Exception {
        FixtureWorld world = world();
        Controller first = controller(world, 0);
        controller(world, 32);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        Set<Long> firstMembers = registry.registeredStructure(position(0));
        Set<Long> secondMembers = registry.registeredStructure(position(32));
        // Initial identity adoption can invalidate earlier entries. Warm both after adoption has finished.
        policy.isSafetyEnabled(world, firstMembers);
        policy.isSafetyEnabled(world, secondMembers);
        policy.isSafetyEnabled(world, firstMembers);
        assertEquals(2, cachedComponents(policy));
        for (int i = 0; i < 20; i++) {
            first.gtswn$setNetworkSafetyEnabled((i & 1) == 0);
            assertEquals((i & 1) == 0, policy.isSafetyEnabled(world, firstMembers));
            assertEquals(1, cachedComponents(policy));
        }
    }

    @Test
    public void unrelatedTileChangesDoNotInvalidateControllerPolicy() throws Exception {
        TileEntity ordinary = (TileEntity) allocate(TileEntity.class);
        long epoch = QuantumPolicyEpoch.current();
        QuantumPolicyEpoch.controllerChanged(ordinary);
        QuantumTileLifecycle.validated(ordinary);
        assertEquals(epoch, QuantumPolicyEpoch.current());
    }

    private static int cachedComponents(QuantumNetworkSecurityRegistry policy) throws Exception {
        return ((Map<?, ?>) field(QuantumNetworkSecurityRegistry.class, "safetyByComponent").get(policy)).size();
    }

    private static long position(int x) {
        return QuantumControllerRegistry.pack(x, 64, 0);
    }

    private static Set<Long> component(FixtureWorld world) {
        return QuantumControllerRegistry.get(world)
            .registeredStructure(position(0));
    }

    private static Controller controller(FixtureWorld world, int x) throws Exception {
        Controller tile = (Controller) allocate(Controller.class);
        place(world, tile, x);
        return tile;
    }

    private static void place(FixtureWorld world, UntrackedController tile, int x) {
        tile.identity = "";
        tile.xCoord = x;
        tile.yCoord = 64;
        tile.setWorldObj(world);
        world.tiles.put(position(x), tile);
        QuantumTileLifecycle.validated(tile);
        QuantumControllerRegistry.get(world)
            .quantize(x, 64, 0);
    }

    private static FixtureWorld world() throws Exception {
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        world.tiles = new HashMap<>();
        WorldProvider provider = new WorldProviderSurface();
        field(World.class, "provider").set(world, provider);
        field(World.class, "perWorldStorage").set(world, new MapStorage(null));
        return world;
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

    private static class UntrackedController extends TileController implements QuantumControllerIdentity {

        String identity;
        boolean safety;

        @Override
        public boolean gtswn$isNetworkSafetyEnabled() {
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
    }

    private static class Controller extends UntrackedController implements QuantumPolicyEpoch.TrackedController {

        @Override
        public void gtswn$setNetworkSafetyEnabled(boolean enabled) {
            if (safety == enabled) return;
            safety = enabled;
            QuantumPolicyEpoch.controllerChanged(this);
        }

        @Override
        public void gtswn$setControllerIdentity(String value) {
            if (value.equals(identity)) return;
            identity = value;
            QuantumPolicyEpoch.controllerChanged(this);
        }

        void readPolicy(NBTTagCompound tag) {
            safety = tag.getBoolean("GTSWN_NetworkSafetyEnabled");
            QuantumPolicyEpoch.controllerChanged(this);
        }
    }

    private static class FixtureWorld extends World {

        Map<Long, TileEntity> tiles;
        int tileReads;
        int blockReads;

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        void resetReads() {
            tileReads = 0;
            blockReads = 0;
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
            blockReads++;
            return tiles.containsKey(QuantumControllerRegistry.pack(x, y, z));
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            tileReads++;
            return tiles.get(QuantumControllerRegistry.pack(x, y, z));
        }
    }
}
