package com.miaokatze.gtswn.common.monitoring.ae;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridStorage;
import appeng.api.networking.pathing.IPathingGrid;

public class MonitorIdentityGridCacheTest {

    private static final class Storage implements IGridStorage {

        final NBTTagCompound data;

        Storage() {
            this(new NBTTagCompound());
        }

        Storage(NBTTagCompound data) {
            this.data = data;
        }

        @Override
        public NBTTagCompound dataObject() {
            return data;
        }

        @Override
        public long getID() {
            return 1L;
        } // IDs alone must never establish source identity.

        Storage diskCopy() {
            return new Storage((NBTTagCompound) data.copy());
        }
    }

    private static final class NativeFixture {

        boolean booting = true;
        final IPathingGrid pathing = (IPathingGrid) Proxy.newProxyInstance(
            IPathingGrid.class.getClassLoader(),
            new Class<?>[] { IPathingGrid.class },
            (proxy, method, args) -> "isNetworkBooting".equals(method.getName()) ? booting : null);
        final IGrid grid = (IGrid) Proxy.newProxyInstance(
            IGrid.class.getClassLoader(),
            new Class<?>[] { IGrid.class },
            (proxy, method, args) -> "getCache".equals(method.getName()) ? pathing : null);

        MonitorIdentityGridCache cache(boolean server) throws Exception {
            MonitorIdentityGridCache cache = new MonitorIdentityGridCache(grid);
            // Model server/client node registration without instantiating a Minecraft server in unit tests.
            Field field = MonitorIdentityGridCache.class.getDeclaredField("server");
            field.setAccessible(true);
            field.setBoolean(cache, server);
            cache.addNode(null, null);
            return cache;
        }

        void settle(MonitorIdentityGridCache cache) {
            booting = false;
            cache.onUpdateTick();
            cache.onUpdateTick();
        }
    }

    private static Storage saved(UUID id) {
        Storage storage = new Storage();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("version", 1);
        tag.setString("id", id.toString());
        storage.data.setTag(MonitorIdentityGridCache.TAG, tag);
        return storage;
    }

    @Test
    public void nativeStorageSaveReloadResumesSameIdentity() throws Exception {
        NativeFixture nativeGrid = new NativeFixture();
        MonitorIdentityGridCache first = nativeGrid.cache(true);
        nativeGrid.settle(first);
        UUID id = first.monitoringIdentity();
        Storage storage = new Storage();
        first.populateGridStorage(storage);
        assertEquals(id, first.monitoringIdentity());
        NativeFixture restartedGrid = new NativeFixture();
        MonitorIdentityGridCache restarted = restartedGrid.cache(true);
        restarted.onJoin(storage.diskCopy());
        assertNull(restarted.monitoringIdentity());
        restartedGrid.settle(restarted);
        assertEquals(id, restarted.monitoringIdentity());
    }

    @Test
    public void nativeSplitJoinBootReunionUsesSharedOrigin() throws Exception {
        UUID id = UUID.randomUUID();
        NativeFixture a = new NativeFixture(), b = new NativeFixture();
        MonitorIdentityGridCache first = a.cache(true), second = b.cache(true);
        first.onJoin(saved(id));
        Storage split = new Storage();
        first.onSplit(split);
        second.onJoin(split);
        assertNull(first.monitoringIdentity());
        assertNull(second.monitoringIdentity());
        second.removeNode(null, null);
        Storage reunion = new Storage();
        second.populateGridStorage(reunion);
        first.onJoin(reunion);
        a.settle(first);
        assertEquals(id, first.monitoringIdentity());
    }

    @Test
    public void nativeSplitSurvivorsHaveDifferentNewIdentities() throws Exception {
        UUID id = UUID.randomUUID();
        NativeFixture a = new NativeFixture(), b = new NativeFixture();
        MonitorIdentityGridCache first = a.cache(true), second = b.cache(true);
        first.onJoin(saved(id));
        Storage split = new Storage();
        first.onSplit(split);
        second.onJoin(split);
        a.settle(first);
        b.settle(second);
        first.onUpdateTick();
        assertNotEquals(id, first.monitoringIdentity());
        assertNotEquals(id, second.monitoringIdentity());
        assertNotEquals(first.monitoringIdentity(), second.monitoringIdentity());
    }

    @Test
    public void equalNativeStorageNumbersDoNotJoinDifferentOrigins() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        NativeFixture nativeGrid = new NativeFixture();
        MonitorIdentityGridCache cache = nativeGrid.cache(true);
        cache.onJoin(saved(a));
        cache.onJoin(saved(b));
        nativeGrid.settle(cache);
        assertNotEquals(a, cache.monitoringIdentity());
        assertNotEquals(b, cache.monitoringIdentity());
    }

    @Test
    public void malformedPendingAndFutureVersionDoNotRestoreConsent() throws Exception {
        for (String bad : new String[] { "", "1-1-1-1-1", "not-a-uuid" }) {
            Storage storage = saved(UUID.randomUUID());
            storage.data.getCompoundTag(MonitorIdentityGridCache.TAG)
                .setString("id", bad);
            assertTrue(MonitorIdentityGridCache.read(storage.data).retired);
            NativeFixture nativeGrid = new NativeFixture();
            MonitorIdentityGridCache cache = nativeGrid.cache(true);
            cache.onJoin(storage);
            assertNull(cache.monitoringIdentity());
            nativeGrid.settle(cache);
            assertNotNull(cache.monitoringIdentity());
        }
        Storage future = saved(UUID.randomUUID());
        future.data.getCompoundTag(MonitorIdentityGridCache.TAG)
            .setInteger("version", 99);
        assertTrue(MonitorIdentityGridCache.read(future.data).retired);
    }

    @Test
    public void clientNeverClaimsNativeIdentityAndMissingPathingFailsClosed() throws Exception {
        NativeFixture nativeGrid = new NativeFixture();
        MonitorIdentityGridCache cache = nativeGrid.cache(false);
        cache.onJoin(saved(UUID.randomUUID()));
        nativeGrid.settle(cache);
        assertNull(cache.monitoringIdentity());
        MonitorIdentityGridCache absent = new MonitorIdentityGridCache(null);
        absent.addNode(null, null);
        absent.onJoin(saved(UUID.randomUUID()));
        absent.onUpdateTick();
        assertNull(absent.monitoringIdentity());
    }

    private static IGridNode node() {
        return (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> {
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                return null;
            });
    }

    @Test
    public void ordinaryNodeRemovalKeepsSurvivingSource() throws Exception {
        NativeFixture a = new NativeFixture();
        MonitorIdentityGridCache first = a.cache(true);
        first.addNode(node(), null);
        a.settle(first);
        UUID id = first.monitoringIdentity();
        first.removeNode(node(), null);
        a.settle(first);
        assertEquals(id, first.monitoringIdentity());
    }

    @Test
    public void liveNativeMigrationWithoutOnSplitRotatesBothSurvivingBranches() throws Exception {
        NativeFixture a = new NativeFixture(), b = new NativeFixture();
        MonitorIdentityGridCache first = a.cache(true), second = b.cache(true);
        IGridNode migrating = node();
        first.addNode(migrating, null);
        a.settle(first);
        UUID original = first.monitoringIdentity();
        first.removeNode(migrating, null);
        second.addNode(migrating, null);
        a.settle(first);
        b.settle(second);
        first.onUpdateTick();
        assertNotEquals(original, first.monitoringIdentity());
        assertNotEquals(original, second.monitoringIdentity());
        assertNotEquals(first.monitoringIdentity(), second.monitoringIdentity());
    }

    @Test
    public void wholeGridLiveMoveKeepsOriginButForeignMergeDoesNot() throws Exception {
        NativeFixture a = new NativeFixture(), b = new NativeFixture(), c = new NativeFixture();
        MonitorIdentityGridCache first = a.cache(true), second = b.cache(true), foreign = c.cache(true);
        a.settle(first);
        c.settle(foreign);
        UUID original = first.monitoringIdentity(), foreignId = foreign.monitoringIdentity();
        IGridNode migrating = node();
        first.removeNode(migrating, null);
        second.addNode(migrating, null);
        b.settle(second);
        assertEquals(original, second.monitoringIdentity());
        IGridNode other = node();
        foreign.removeNode(other, null);
        second.addNode(other, null);
        assertNull(second.monitoringIdentity());
        b.settle(second);
        assertNotEquals(original, second.monitoringIdentity());
        assertNotEquals(foreignId, second.monitoringIdentity());
    }

    @Test
    public void freshCableNativeMergeDoesNotCreateAnotherIdentity() throws Exception {
        NativeFixture a = new NativeFixture(), b = new NativeFixture();
        MonitorIdentityGridCache first = a.cache(true), cable = b.cache(true);
        a.settle(first);
        UUID original = first.monitoringIdentity();
        IGridNode moving = node();
        cable.removeNode(moving, null);
        first.addNode(moving, null);
        a.settle(first);
        assertEquals(original, first.monitoringIdentity());
    }

    @Test
    public void nestedRemovalCallbacksKeepExactTransferProvenance() throws Exception {
        NativeFixture a = new NativeFixture(), b = new NativeFixture(), c = new NativeFixture();
        MonitorIdentityGridCache donor = a.cache(true), nested = b.cache(true), target = c.cache(true);
        a.settle(donor);
        b.settle(nested);
        c.settle(target);
        UUID original = target.monitoringIdentity();
        IGridNode outerNode = node(), innerNode = node();
        donor.removeNode(outerNode, null);
        nested.removeNode(innerNode, null);
        target.addNode(outerNode, null);
        assertNull(target.monitoringIdentity());
        c.settle(target);
        assertNotEquals(original, target.monitoringIdentity());
    }

    @Test
    public void boundedTransferOverflowCannotSilentlyKeepOldConsent() throws Exception {
        NativeFixture a = new NativeFixture(), b = new NativeFixture();
        MonitorIdentityGridCache donor = a.cache(true), target = b.cache(true);
        java.util.List<IGridNode> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < 4097; i++) {
            IGridNode node = node();
            nodes.add(node);
            donor.addNode(node, null);
        }
        a.settle(donor);
        b.settle(target);
        UUID original = target.monitoringIdentity();
        for (IGridNode node : nodes) donor.removeNode(node, null);
        assertNull(target.monitoringIdentity());
        target.addNode(nodes.get(0), null); // Oldest provenance was discarded only with global revocation.
        b.settle(target);
        assertNotEquals(original, target.monitoringIdentity());
    }

}
