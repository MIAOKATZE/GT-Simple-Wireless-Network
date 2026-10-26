package com.miaokatze.gtswn.common.monitoring.ae;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;

import org.junit.Test;

import com.miaokatze.gtswn.mixins.ae2.MonitorGridStorageAccessor;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.me.GridStorage;

public class AEGridIdentityPersistenceTest {

    @Test
    public void finalNodeUnloadAndDeletedNativePayloadStillRestoreCommittedSourceAfterRestart() throws Exception {
        FixtureWorld firstWorld = world(null);
        NativeFixture first = new NativeFixture(firstWorld, 12, null);
        first.settle();
        UUID committed = first.cache.monitoringIdentity();
        AEGridIdentityData data = AEGridIdentityData.get(firstWorld);
        data.setDirty(false);
        for (int i = 0; i < 20; i++) assertEquals(committed, first.cache.monitoringIdentity());
        assertFalse(data.isDirty());
        first.cache.removeNode(first.node, null); // Native Grid.remove does not call populateGridStorage here.
        assertNull(first.cache.monitoringIdentity());
        NBTTagCompound disk = save(data);
        MonitorIdentityGridCache.clearSession();
        FixtureWorld secondWorld = world(disk);
        NativeFixture restarted = new NativeFixture(secondWorld, 12, null); // Native payload has been deleted.
        assertNull(restarted.cache.monitoringIdentity());
        restarted.settle();
        assertEquals(committed, restarted.cache.monitoringIdentity());
        assertFalse(
            AEGridIdentityData.get(secondWorld)
                .isDirty());
    }

    @Test
    public void realStorageZeroRestoresButTemporarySplitZeroNeverClaimsItsBackup() throws Exception {
        FixtureWorld firstWorld = world(null);
        NativeFixture first = new NativeFixture(firstWorld, 0, null);
        first.settle();
        UUID committed = first.cache.monitoringIdentity();
        NBTTagCompound disk = save(AEGridIdentityData.get(firstWorld));
        MonitorIdentityGridCache.clearSession();
        FixtureWorld restartedWorld = world(disk);
        NativeFixture actualZero = new NativeFixture(restartedWorld, 0, null);
        actualZero.settle();
        assertEquals(committed, actualZero.cache.monitoringIdentity());
        NativeFixture unrelated = new NativeFixture(restartedWorld, 99, null);
        unrelated.cache.onJoin(new GridStorage()); // Same numeric 0, no actual grid association.
        unrelated.settle();
        assertNotEquals(committed, unrelated.cache.monitoringIdentity());
        assertFalse(
            AEGridIdentityData.get(restartedWorld)
                .restore(0, new NBTTagCompound(), null).retired);
    }

    @Test
    public void emptyPublishedGridStorageTransferAfterUnloadPreservesTheOnlySurvivingSource() throws Exception {
        FixtureWorld world = world(null);
        NativeFixture first = new NativeFixture(world, 12, null);
        first.settle();
        UUID committed = first.cache.monitoringIdentity();
        first.cache.removeNode(first.node, null);
        GridStorage transfer = new GridStorage();
        first.cache.onSplit(transfer); // Native Grid.add can encounter a weakly retained empty donor grid.
        NativeFixture reloaded = new NativeFixture(world, 15, null);
        reloaded.cache.onJoin(transfer);
        reloaded.settle();
        assertEquals(committed, reloaded.cache.monitoringIdentity());
        assertFalse(roundTrip(AEGridIdentityData.get(world)).restore(12, new NBTTagCompound(), null).retired);
    }

    @Test
    public void publishedSplitRetiresEveryOldStorageAliasAcrossRestart() throws Exception {
        FixtureWorld firstWorld = world(null);
        NativeFixture first = new NativeFixture(firstWorld, 12, null);
        first.settle();
        UUID committed = first.cache.monitoringIdentity();
        AEGridIdentityData data = AEGridIdentityData.get(firstWorld);
        data.commit(14, data.restore(12, new NBTTagCompound(), null));
        GridStorage split = new GridStorage();
        first.cache.onSplit(split);
        NativeFixture second = new NativeFixture(firstWorld, 13, null);
        second.cache.onJoin(split);
        first.settle();
        second.settle();
        assertNotEquals(committed, first.cache.monitoringIdentity());
        assertNotEquals(committed, second.cache.monitoringIdentity());
        assertNotEquals(first.cache.monitoringIdentity(), second.cache.monitoringIdentity());
        FixtureWorld restartedWorld = world(save(data));
        NativeFixture staleAlias = new NativeFixture(restartedWorld, 14, tagged(committed));
        staleAlias.settle();
        assertNotEquals(committed, staleAlias.cache.monitoringIdentity());
    }

    @Test
    public void nativeTagConflictingWithBackupCannotRestoreEitherOldConsent() throws Exception {
        FixtureWorld firstWorld = world(null);
        NativeFixture first = new NativeFixture(firstWorld, 12, null);
        first.settle();
        UUID backup = first.cache.monitoringIdentity(), foreign = UUID.randomUUID();
        FixtureWorld restartedWorld = world(save(AEGridIdentityData.get(firstWorld)));
        NativeFixture restarted = new NativeFixture(restartedWorld, 12, tagged(foreign));
        restarted.settle();
        assertNotEquals(backup, restarted.cache.monitoringIdentity());
        assertNotEquals(foreign, restarted.cache.monitoringIdentity());
        AEGridIdentityData restored = roundTrip(AEGridIdentityData.get(restartedWorld));
        assertTrue(restored.restore(12, tagged(backup), null).retired);
        assertTrue(restored.restore(12, tagged(foreign), null).retired);
    }

    @Test
    public void invalidNativeTagDoesNotFallBackToOtherwiseValidBackup() throws Exception {
        FixtureWorld firstWorld = world(null);
        NativeFixture first = new NativeFixture(firstWorld, 12, null);
        first.settle();
        UUID committed = first.cache.monitoringIdentity();
        NBTTagCompound invalid = tagged(committed);
        invalid.getCompoundTag(MonitorIdentityGridCache.TAG)
            .setInteger("version", 99);
        FixtureWorld restartedWorld = world(save(AEGridIdentityData.get(firstWorld)));
        NativeFixture restarted = new NativeFixture(restartedWorld, 12, invalid);
        restarted.settle();
        assertNotEquals(committed, restarted.cache.monitoringIdentity());
        assertTrue(roundTrip(AEGridIdentityData.get(restartedWorld)).restore(12, tagged(committed), null).retired);
    }

    @Test
    public void foreignLiveMergePersistsBothRetirementsEvenForTheRemovedGrid() throws Exception {
        FixtureWorld firstWorld = world(null);
        NativeFixture first = new NativeFixture(firstWorld, 12, null), second = new NativeFixture(firstWorld, 13, null);
        first.settle();
        second.settle();
        UUID a = first.cache.monitoringIdentity(), b = second.cache.monitoringIdentity();
        second.cache.removeNode(second.node, null);
        first.cache.addNode(second.node, null);
        first.settle();
        assertNotEquals(a, first.cache.monitoringIdentity());
        assertNotEquals(b, first.cache.monitoringIdentity());
        AEGridIdentityData restored = roundTrip(AEGridIdentityData.get(firstWorld));
        assertTrue(restored.restore(13, new NBTTagCompound(), null).retired);
        assertTrue(restored.restore(12, tagged(a), null).retired);
    }

    @Test
    public void bootFragmentsShareBackupOriginAndReunionBeforePublicationKeepsIt() throws Exception {
        AEGridIdentityData data = new AEGridIdentityData(AEGridIdentityData.DATA_NAME);
        UUID committed = UUID.randomUUID();
        data.commit(12, new GridIdentityState.Origin(committed));
        FixtureWorld restartedWorld = world(save(data));
        NativeFixture first = new NativeFixture(restartedWorld, 12, null),
            second = new NativeFixture(restartedWorld, 12, null);
        first.settle();
        assertNull(first.cache.monitoringIdentity()); // Still waits for the other native boot fragment.
        second.cache.removeNode(second.node, null);
        first.cache.addNode(second.node, null);
        first.settle();
        assertEquals(committed, first.cache.monitoringIdentity());
    }

    @Test
    public void freshNativeStorageNumberReuseRetiresOldMappingRatherThanInheritingIt() throws Exception {
        FixtureWorld world = world(null);
        AEGridIdentityData data = AEGridIdentityData.get(world);
        UUID previous = UUID.randomUUID();
        data.commit(0, new GridIdentityState.Origin(previous));
        NativeFixture freshGrid = new NativeFixture(world, 0, null, false);
        freshGrid.settle();
        UUID fresh = freshGrid.cache.monitoringIdentity();
        assertNotEquals(previous, fresh);
        AEGridIdentityData restored = roundTrip(data);
        assertEquals(fresh, restored.restore(0, new NBTTagCompound(), null).id);
        assertTrue(restored.restore(0, tagged(previous), null).retired);
        assertNull(new AEGridIdentityData("no-backup").restore(12, new NBTTagCompound(), null).id);
    }

    private static NBTTagCompound tagged(UUID identity) {
        NBTTagCompound nativeTag = new NBTTagCompound(), tag = new NBTTagCompound();
        tag.setInteger("version", 1);
        tag.setString("id", identity.toString());
        nativeTag.setTag(MonitorIdentityGridCache.TAG, tag);
        return nativeTag;
    }

    private static NBTTagCompound save(AEGridIdentityData data) {
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        return tag;
    }

    private static AEGridIdentityData roundTrip(AEGridIdentityData data) {
        AEGridIdentityData restored = new AEGridIdentityData(AEGridIdentityData.DATA_NAME);
        restored.readFromNBT(save(data));
        return restored;
    }

    private static final class NativeFixture {

        boolean booting = true;
        final MonitorIdentityGridCache cache;
        final IGridNode node;

        NativeFixture(World world, long storageId, NBTTagCompound nativeData) throws Exception {
            this(world, storageId, nativeData, true);
        }

        NativeFixture(World world, long storageId, NBTTagCompound nativeData, boolean restoring) throws Exception {
            IPathingGrid pathing = (IPathingGrid) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { IPathingGrid.class },
                (proxy, method, args) -> "isNetworkBooting".equals(method.getName()) ? booting : null);
            GridStorage[] actual = new GridStorage[1];
            IGrid grid = (IGrid) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { IGrid.class, MonitorGridStorageAccessor.class },
                (proxy, method, args) -> "getCache".equals(method.getName()) ? pathing
                    : "gtswn$getMonitorStorage".equals(method.getName()) ? actual[0] : null);
            node = (IGridNode) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { IGridNode.class },
                (proxy, method, args) -> {
                    if ("getWorld".equals(method.getName())) return world;
                    if ("getGrid".equals(method.getName())) return grid;
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("saveToNBT".equals(method.getName()))
                        throw new AssertionError("No node serialization for identity reads");
                    return null;
                });
            GridStorage storage = new GridStorage(storageId, null) {

                @Override
                public IGrid getGrid() {
                    return grid;
                }
            };
            actual[0] = storage;
            if (nativeData != null) storage.dataObject()
                .setTag(MonitorIdentityGridCache.TAG, nativeData.getTag(MonitorIdentityGridCache.TAG));
            cache = new MonitorIdentityGridCache(grid);
            if (restoring) cache.onJoin(storage); // Native callback occurs before addNode supplies the world.
            cache.addNode(node, null);
        }

        void settle() {
            booting = false;
            cache.onUpdateTick();
            cache.onUpdateTick();
        }
    }

    private static FixtureWorld world(NBTTagCompound backup) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        FixtureWorld world = (FixtureWorld) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), FixtureWorld.class);
        Field storage = World.class.getDeclaredField("perWorldStorage");
        storage.setAccessible(true);
        storage.set(world, new MapStorage(null));
        if (backup != null) {
            AEGridIdentityData data = new AEGridIdentityData(AEGridIdentityData.DATA_NAME);
            data.readFromNBT(backup);
            world.perWorldStorage.setData(AEGridIdentityData.DATA_NAME, data);
        }
        return world;
    }

    private static final class FixtureWorld extends World {

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
    }
}
