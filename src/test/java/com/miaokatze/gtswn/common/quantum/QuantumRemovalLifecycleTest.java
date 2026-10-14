package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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

import org.junit.Test;

import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.tile.networking.TileController;
import appeng.util.ReadOnlyCollection;

public class QuantumRemovalLifecycleTest {

    @Test
    public void missingMiddleMemberStillFindsBothOriginalFragmentsAndLeavesOtherNetwork() {
        QuantumControllerRegistry registry = new QuantumControllerRegistry("test");
        for (int x : new int[] { -1, 0, 1, 20 }) registry.quantize(x, 64, 0);
        Set<Long> original = registry.registeredStructure(pos(0));
        assertEquals(3, original.size());
        assertTrue(original.contains(pos(-1)));
        assertTrue(original.contains(pos(1)));
        for (long member : original) registry.dequantize(member);
        assertEquals(java.util.Collections.singleton(pos(20)), registry.getAll());
    }

    @Test
    public void legacyCoordinatesPreserveOriginalGraphAcrossSaveReload() {
        NBTTagCompound oldSave = new NBTTagCompound();
        oldSave.setIntArray("controllers", new int[] { 0, 15, 64, 0, 0, 16, 64, 0, 0, 17, 64, 0 });
        QuantumControllerRegistry registry = new QuantumControllerRegistry("test");
        registry.readFromNBT(oldSave);
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        QuantumControllerRegistry reloaded = new QuantumControllerRegistry("test");
        reloaded.readFromNBT(saved);
        assertEquals(registry.getAll(), reloaded.registeredStructure(pos(16)));
    }

    @Test
    public void controllerIdentitySurvivesUnloadAndReloadWithoutChunkReads() throws Exception {
        FixtureWorld world = world();
        Controller first = controller(world, 0, "");
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        String identity = first.identity;
        assertFalse(identity.isEmpty());
        registry.unloadTile(first);
        world.loaded.clear();
        assertTrue(registry.isCurrentController(world, pos(0)));
        assertEquals(
            1,
            registry.getAll()
                .size());
        world.loaded.add(0);
        Controller reloaded = controller(world, 0, identity);
        assertTrue(registry.isCurrentController(world, pos(0)));
        assertEquals(identity, reloaded.identity);
    }

    @Test
    public void sameClassReplacementCannotInheritLiveIdentityEvenIfNbtWasCopied() throws Exception {
        FixtureWorld world = world();
        Controller old = controller(world, 0, "");
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        controller(world, 0, old.identity);
        assertFalse(registry.isCurrentController(world, pos(0)));
        assertTrue(registry.isQuantized(pos(0)));
    }

    @Test
    public void persistedIdentityRejectsSameClassReplacementAfterRestart() throws Exception {
        FixtureWorld world = world();
        controller(world, 0, "original");
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        registry.readFromNBT(saved);
        controller(world, 0, "replacement");
        assertFalse(registry.isCurrentController(world, pos(0)));
    }

    @Test
    public void delayedInvalidationCannotOwnNewControllerRegistration() throws Exception {
        FixtureWorld world = world();
        Controller old = controller(world, 0, "old");
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        registry.dequantize(pos(0));
        Controller replacement = controller(world, 0, "new");
        registry.quantize(0, 64, 0);
        assertFalse(registry.ownsController(old));
        assertTrue(registry.ownsController(replacement));
    }

    @Test
    public void loadedMissingMemberIsRemovalWhileUnavailableMemberIsPreserved() throws Exception {
        FixtureWorld world = world();
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        assertFalse(registry.isCurrentController(world, pos(0)));
        world.loaded.clear();
        assertTrue(registry.isCurrentController(world, pos(0)));
    }

    @Test
    public void legacyRegistrationAdoptsIdentityOnceAndThenRejectsReplacement() throws Exception {
        FixtureWorld world = world();
        Controller first = controller(world, 0, "");
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        NBTTagCompound oldSave = new NBTTagCompound();
        oldSave.setIntArray("controllers", new int[] { 0, 0, 64, 0 });
        registry.readFromNBT(oldSave);
        assertTrue(registry.isCurrentController(world, pos(0)));
        assertFalse(first.identity.isEmpty());
        registry.unloadTile(first);
        controller(world, 0, "");
        assertFalse(registry.isCurrentController(world, pos(0)));
    }

    @Test
    public void removalCancelsOnlyMatchingIncorporationAndIsIdempotent() throws Exception {
        FixtureWorld world = world();
        Incorporated old = incorporated(world, "old");
        QuantumIncorporationRegistry registry = QuantumIncorporationRegistry.get(world);
        registry.readFromNBT(incorporationSave("old", 0));
        Incorporated replacement = incorporated(world, "new");
        registry.removed(world, replacement);
        assertEquals(1, count(registry.snapshot()));
        registry.removed(world, old);
        registry.removed(world, old);
        assertEquals(0, count(registry.snapshot()));
        assertEquals("", old.identity);
        assertEquals("new", replacement.identity);
    }

    @Test
    public void incorporationUnloadPreservesSavedIdentityAndBinding() throws Exception {
        FixtureWorld world = world();
        Incorporated tile = incorporated(world, "saved");
        QuantumIncorporationRegistry registry = QuantumIncorporationRegistry.get(world);
        registry.readFromNBT(incorporationSave("saved", 0));
        registry.unloadTile(tile);
        registry.unload();
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        assertEquals(
            "saved",
            saved.getTagList("entries", 10)
                .getCompoundTagAt(0)
                .getString("identity"));
        assertEquals(
            "owner",
            saved.getTagList("entries", 10)
                .getCompoundTagAt(0)
                .getString("owner"));
        assertEquals("saved", tile.identity);
    }

    @Test
    public void controllerCancellationForgetsBindingEvenWhenLocalChunkIsUnloaded() throws Exception {
        FixtureWorld world = world();
        QuantumIncorporationRegistry registry = QuantumIncorporationRegistry.get(world);
        registry.readFromNBT(incorporationSave("saved", 0));
        world.loaded.clear();
        registry.controllerRemoved(world, 7, java.util.Collections.singleton(pos(0)));
        assertEquals(0, count(registry.snapshot()));
    }

    @Test
    public void alreadyDestroyedHostEdgeIsNotDestroyedAgainButBillingStillGetsReclaimed() throws Exception {
        QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("test");
        registry.readFromNBT(incorporationSave("saved", 0));
        QuantumIncorporationRegistry.Entry entry = registry.snapshot()
            .iterator()
            .next();
        AtomicInteger hostDestroyed = new AtomicInteger(), billingDestroyed = new AtomicInteger(),
            nodeDestroyed = new AtomicInteger();
        IGridConnection host = edge(hostDestroyed), billing = edge(billingDestroyed);
        IGridNode emptyHost = node(new ArrayList<>(), new AtomicInteger());
        ArrayList<IGridConnection> targetEdges = new ArrayList<>();
        targetEdges.add(billing);
        IGridNode target = node(targetEdges, new AtomicInteger());
        IGridNode billingNode = node(targetEdges, nodeDestroyed);
        set(entry, "connection", host);
        set(entry, "local", emptyHost);
        set(entry, "target", target);
        set(entry, "billingConnection", billing);
        set(entry, "billingNode", billingNode);
        registry.unload();
        registry.unload();
        assertEquals(0, hostDestroyed.get());
        assertEquals(1, billingDestroyed.get());
        assertEquals(1, nodeDestroyed.get());
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static IGridConnection edge(AtomicInteger destroyed) {
        return (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("equals")) return proxy == args[0];
                if (method.getName()
                    .equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName()
                    .equals("destroy")) destroyed.incrementAndGet();
                return null;
            });
    }

    private static IGridNode node(ArrayList<IGridConnection> edges, AtomicInteger destroyed) {
        return (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getConnections")) return new ReadOnlyCollection<>(edges);
                if (method.getName()
                    .equals("destroy")) destroyed.incrementAndGet();
                return null;
            });
    }

    private static int count(Iterable<?> entries) {
        int count = 0;
        for (Object ignored : entries) count++;
        return count;
    }

    private static NBTTagCompound incorporationSave(String identity, int anchorX) {
        NBTTagCompound saved = new NBTTagCompound(), entry = new NBTTagCompound();
        NBTTagList entries = new NBTTagList();
        entry.setLong("pos", pos(0));
        entry.setString("identity", identity);
        entry.setString("owner", "owner");
        entry.setInteger("dim", 7);
        entry.setInteger("x", anchorX);
        entry.setInteger("y", 64);
        entries.appendTag(entry);
        saved.setTag("entries", entries);
        return saved;
    }

    private static long pos(int x) {
        return QuantumControllerRegistry.pack(x, 64, 0);
    }

    private static FixtureWorld world() throws Exception {
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        world.loaded = new HashSet<>();
        world.loaded.add(0);
        world.tiles = new HashMap<>();
        Field provider = World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new WorldProviderSurface());
        Field storage = World.class.getDeclaredField("perWorldStorage");
        storage.setAccessible(true);
        storage.set(world, new MapStorage(null));
        return world;
    }

    private static Controller controller(FixtureWorld world, int x, String identity) throws Exception {
        Controller tile = (Controller) allocate(Controller.class);
        tile.identity = identity;
        tile.xCoord = x;
        tile.yCoord = 64;
        tile.setWorldObj(world);
        world.tiles.put(pos(x), tile);
        return tile;
    }

    private static Incorporated incorporated(FixtureWorld world, String identity) {
        Incorporated tile = new Incorporated();
        tile.identity = identity;
        tile.yCoord = 64;
        tile.setWorldObj(world);
        world.tiles.put(pos(0), tile);
        return tile;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), type);
    }

    private static class Controller extends TileController implements QuantumControllerIdentity {

        String identity;

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

    private static class Incorporated extends TileEntity implements QuantumIncorporationIdentity {

        String identity;

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

    private static class FixtureWorld extends World {

        Set<Integer> loaded;
        Map<Long, TileEntity> tiles;

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
            return loaded.contains(x >> 4);
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            assertTrue("Attempted to read an unloaded chunk", blockExists(x, y, z));
            return tiles.get(QuantumControllerRegistry.pack(x, y, z));
        }
    }
}
