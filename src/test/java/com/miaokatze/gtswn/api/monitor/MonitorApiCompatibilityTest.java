package com.miaokatze.gtswn.api.monitor;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.CompressedStreamTools;
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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;
import com.miaokatze.gtswn.common.monitoring.SourceKey;
import com.miaokatze.gtswn.common.monitoring.ae.IMonitorIdentityCache;
import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;
import com.miaokatze.gtswn.common.panel.AEMonitorDataStore;
import com.miaokatze.gtswn.common.panel.NetworkInfoDataStore;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

import appeng.api.networking.IGrid;
import gregtech.common.misc.spaceprojects.SpaceProjectManager;

public class MonitorApiCompatibilityTest {

    private static final String COORDINATE = "0:1:64:2", CURRENT = COORDINATE + "#panel#current-source";

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void coordinateQueriesFollowCurrentSamplesInsteadOfShadowingLegacyArchive() throws Exception {
        FixtureWorld world = world();
        Panel panel = new Panel();
        panel.key = CURRENT;
        world.panel = panel;
        AEMonitorDataStore store = AEMonitorDataStore.get(world);
        store.getOrCreate(COORDINATE)
            .addSample("legacy", 99, 100, 1000);
        AEMonitorDataSet current = store.getOrCreate(CURRENT);
        current.addSample("item", 10, 200, 2000);
        store.setDirty(false);
        IGtswnMonitorApi api = new GtswnMonitorApiImpl(() -> world);
        assertEquals(
            BigInteger.TEN,
            api.getAEMonitorSnapshot(world, COORDINATE)
                .get().currentAmounts.get("item"));
        current.addSample("item", 25, 300, 3000);
        assertEquals(
            BigInteger.valueOf(25),
            api.getAEMonitorSnapshot(world, COORDINATE)
                .get().currentAmounts.get("item"));
        assertEquals(
            2,
            api.getAEMonitorHistory(world, COORDINATE, "item", 0)
                .get().samples.size());
        assertEquals(
            99,
            store.getIfPresent(COORDINATE)
                .newest("legacy").amount);
        assertFalse(store.isDirty());
    }

    @Test
    public void provenNewSourceWithoutSamplesCannotFallBackToOldCoordinateData() throws Exception {
        FixtureWorld world = world();
        Panel panel = new Panel();
        panel.key = CURRENT;
        world.panel = panel;
        AEMonitorDataStore.get(world)
            .getOrCreate(COORDINATE)
            .addSample("v2:item:previous", 99, 100, 1000);
        IGtswnMonitorApi api = new GtswnMonitorApiImpl(() -> world);
        assertFalse(
            api.getAEMonitorSnapshot(world, COORDINATE)
                .isPresent());
        assertFalse(
            api.getAEMonitorHistory(world, COORDINATE, "v2:item:previous", 0)
                .isPresent());
    }

    @Test
    public void liveSourceDoesNotHideExplicitLegacySubkeysOrCoordinateArchive() throws Exception {
        FixtureWorld world = world();
        Panel panel = new Panel();
        panel.key = CURRENT;
        world.panel = panel;
        AEMonitorDataStore store = AEMonitorDataStore.get(world);
        store.getOrCreate(COORDINATE)
            .addSample("old-item", 99, 100, 1000);
        store.getOrCreate(CURRENT)
            .addSample("v2:item:current", 10, 200, 2000);
        IGtswnMonitorApi api = new GtswnMonitorApiImpl(() -> world);
        assertFalse(
            api.getAEMonitorSnapshot(world, COORDINATE)
                .get().currentAmounts.containsKey("old-item"));
        assertEquals(
            BigInteger.valueOf(99),
            api.getAEMonitorHistory(world, COORDINATE, "old-item", 0)
                .get().samples.get(0).value);
        assertEquals(
            BigInteger.valueOf(99),
            api.getAEMonitorSnapshot(world, "legacy:" + COORDINATE)
                .get().currentAmounts.get("old-item"));
        assertEquals(
            1,
            api.getAEMonitorHistory(world, "legacy:" + COORDINATE, "old-item", 0)
                .get().samples.size());
    }

    @Test
    public void unloadedAndUnprovenSourcesRetainExplicitLegacyAndQualifiedHistoryReads() throws Exception {
        FixtureWorld world = world();
        AEMonitorDataStore store = AEMonitorDataStore.get(world);
        store.getOrCreate(COORDINATE)
            .addSample("old", 99, 100, 1000);
        store.getOrCreate(CURRENT)
            .addSample("item", 10, 200, 2000);
        IGtswnMonitorApi api = new GtswnMonitorApiImpl(() -> world);
        world.loaded = false;
        assertEquals(
            BigInteger.valueOf(99),
            api.getAEMonitorSnapshot(world, COORDINATE)
                .get().currentAmounts.get("old"));
        assertEquals(
            BigInteger.TEN,
            api.getAEMonitorSnapshot(world, CURRENT)
                .get().currentAmounts.get("item"));
        world.loaded = true;
        world.panel = new Panel(); // No currently provable source: preserve the original coordinate archive API.
        assertEquals(
            1,
            api.getAEMonitorHistory(world, COORDINATE, "old", 0)
                .get().samples.size());
    }

    @Test
    public void readingUnsampledPanelDoesNotAllocatePanelOrProfileIdentity() throws Exception {
        FixtureWorld world = world();
        TileEntityNetworkInfoPanel panel = new TileEntityNetworkInfoPanel() {

            @Override
            public Object getAEMonitorSourceIdentity() {
                return null;
            }

            @Override
            public void markDirty() {
                fail("A public read must not dirty the panel");
            }
        };
        panel.setWorldObj(world);
        world.panel = panel;
        assertNull(panel.getAEMonitorDataKeyReadOnly());
        assertFalse(
            new GtswnMonitorApiImpl(() -> world).getAEMonitorSnapshot(world, COORDINATE)
                .isPresent());
        assertNull(panel.getExistingPortableMonitorId());
        assertNull(MonitoringProfiles.getIfPresent(world));
        assertFalse(
            AEMonitorDataStore.get(world)
                .isDirty());
    }

    @Test
    public void stableNativeIdentityReadUsesOnlyExistingPanelAndProfileProvenance() throws Exception {
        FixtureWorld world = world();
        UUID nativeId = UUID.randomUUID(), panelId = UUID.randomUUID();
        IMonitorIdentityCache identity = (IMonitorIdentityCache) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { IMonitorIdentityCache.class },
            (proxy, method, args) -> "monitoringIdentity".equals(method.getName()) ? nativeId : null);
        IGrid grid = (IGrid) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { IGrid.class },
            (proxy, method, args) -> "getCache".equals(method.getName()) ? identity : null);
        TileEntityNetworkInfoPanel panel = new TileEntityNetworkInfoPanel() {

            @Override
            public Object getAEMonitorSourceIdentity() {
                return grid;
            }

            @Override
            public void markDirty() {
                fail("A public read must not allocate or dirty provenance");
            }
        };
        panel.setWorldObj(world);
        panel.xCoord = 1;
        panel.yCoord = 64;
        panel.zCoord = 2;
        assertNull(panel.getAEMonitorDataKeyReadOnly());
        assertNull(panel.getExistingPortableMonitorId());
        Field marker = TileEntityNetworkInfoPanel.class.getDeclaredField("portableMonitorId");
        marker.setAccessible(true);
        marker.set(panel, panelId);
        assertNull(panel.getAEMonitorDataKeyReadOnly());
        assertNull(MonitoringProfiles.getIfPresent(world));
        MonitoringProfiles profiles = new MonitoringProfiles(MonitoringProfiles.DATA_NAME);
        profiles.setDirty(false);
        world.perWorldStorage.setData(MonitoringProfiles.DATA_NAME, profiles);
        assertEquals(
            COORDINATE + "#"
                + panelId
                + "#"
                + new SourceKey(SourceKey.Kind.AE, profiles.epoch(), nativeId.toString(), 1),
            panel.getAEMonitorDataKeyReadOnly());
        assertFalse(profiles.isDirty());
    }

    @Test
    public void loadingExistingProfileProvenanceIsCleanWhileNewEpochStillPersists() throws Exception {
        FixtureWorld world = world();
        assertNull(MonitoringProfiles.getIfPresent(world));
        MonitoringProfiles created = MonitoringProfiles.get(world);
        assertTrue(created.isDirty());
        NBTTagCompound contents = new NBTTagCompound(), root = new NBTTagCompound();
        created.writeToNBT(contents);
        root.setTag("data", contents);
        File file = temporary.newFile("profiles.dat");
        try (FileOutputStream output = new FileOutputStream(file)) {
            CompressedStreamTools.writeCompressed(root, output);
        }
        ISaveHandler handler = (ISaveHandler) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { ISaveHandler.class },
            (proxy, method, args) -> "getMapFileFromName".equals(method.getName())
                && MonitoringProfiles.DATA_NAME.equals(args[0]) ? file : null);
        Field storage = World.class.getDeclaredField("perWorldStorage");
        storage.setAccessible(true);
        storage.set(world, new MapStorage(handler));
        MonitoringProfiles loaded = MonitoringProfiles.getIfPresent(world);
        assertEquals(created.epoch(), loaded.epoch());
        assertFalse(loaded.isDirty());
        assertSame(loaded, MonitoringProfiles.get(world));
        assertFalse(loaded.isDirty());
    }

    @Test
    public void publicEuReadsNeverEnrollUnknownPlayersAndStillResolveExistingTeams() throws Exception {
        FixtureWorld world = world();
        IGtswnMonitorApi api = new GtswnMonitorApiImpl(() -> world);
        UUID unknown = UUID.randomUUID(), member = UUID.randomUUID(), leader = UUID.randomUUID();
        Map<UUID, UUID> teams = SpaceProjectManager.spaceTeams;
        int before = teams.size();
        try {
            assertFalse(
                api.getNetworkSnapshot(unknown)
                    .isPresent());
            assertFalse(
                api.getNetworkHistory(unknown, 0)
                    .isPresent());
            assertFalse(
                api.getNetworkAverageEut(unknown, 0)
                    .isPresent());
            assertEquals(before, teams.size());
            assertFalse(SpaceProjectManager.isInTeam(unknown));
            teams.put(member, leader);
            NetworkInfoDataStore store = NetworkInfoDataStore.get(world);
            store.getOrCreateNative(leader)
                .addSample(BigInteger.TEN, 100, 1000);
            store.setDirty(false);
            assertEquals(
                BigInteger.TEN,
                api.getNetworkSnapshot(member)
                    .get().euStored);
            assertEquals(
                1,
                api.getNetworkHistory(member, 0)
                    .get().samples.size());
            assertTrue(
                api.getNetworkAverageEut(member, 0)
                    .isPresent());
            assertFalse(store.isDirty());
            assertFalse(SpaceProjectManager.isInTeam(leader));
        } finally {
            teams.remove(unknown);
            teams.remove(member);
            teams.remove(leader);
        }
    }

    @Test
    public void explicitEuActivationRetainsNativeTeamInitialization() throws Exception {
        FixtureWorld world = world();
        UUID unknown = UUID.randomUUID();
        try {
            assertTrue(new GtswnMonitorApiImpl(() -> world).activateNetworkDataset(unknown));
            assertTrue(SpaceProjectManager.isInTeam(unknown));
            assertNotNull(
                NetworkInfoDataStore.get(world)
                    .getNativeIfPresent(unknown));
        } finally {
            SpaceProjectManager.spaceTeams.remove(unknown);
        }
    }

    @Test
    public void ownerHistoryArchiveRemainsReadableUntilNativeSamplesExistWithoutMergingTimelines() throws Exception {
        FixtureWorld world = world();
        UUID owner = UUID.randomUUID();
        IGtswnMonitorApi api = new GtswnMonitorApiImpl(() -> world);
        NetworkInfoDataStore store = NetworkInfoDataStore.get(world);
        for (int i = 1; i <= 13; i++) {
            store.getOrCreate(owner.toString())
                .addSample(BigInteger.valueOf(i * 100), i * 100, i * 1000);
        }
        store.setDirty(false);
        assertEquals(
            13,
            api.getNetworkHistory(owner, 0)
                .get().samples.size());
        assertTrue(
            api.getNetworkAverageEut(owner, 0)
                .isPresent());
        assertFalse(
            api.getNetworkSnapshot(owner)
                .isPresent()); // Unknown provenance is not a current balance.
        store.getOrCreateNative(owner); // Activation alone does not make the retained owner history disappear.
        assertEquals(
            13,
            api.getNetworkHistory(owner, 0)
                .get().samples.size());
        assertFalse(
            api.getNetworkHistory(owner, 1)
                .get().samples.isEmpty());
        store.getOrCreateNative(owner)
            .addSample(BigInteger.TEN, 2000, 20000);
        store.setDirty(false);
        assertEquals(
            1,
            api.getNetworkHistory(owner, 0)
                .get().samples.size());
        assertEquals(
            BigInteger.TEN,
            api.getNetworkHistory(owner, 0)
                .get().samples.get(0).value);
        assertTrue(
            api.getNetworkHistory(owner, 1)
                .get().samples.isEmpty());
        assertEquals(
            13,
            store.getIfPresent(owner.toString())
                .query(0)
                .size());
        assertFalse(SpaceProjectManager.isInTeam(owner));
        assertFalse(store.isDirty());
    }

    private static FixtureWorld world() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        FixtureWorld world = (FixtureWorld) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), FixtureWorld.class);
        Field storage = World.class.getDeclaredField("perWorldStorage");
        storage.setAccessible(true);
        storage.set(world, new MapStorage(null));
        Field provider = World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new WorldProviderSurface());
        world.loaded = true;
        return world;
    }

    private static final class Panel extends TileEntityNetworkInfoPanel {

        String key;

        @Override
        public String getAEMonitorDataKeyReadOnly() {
            return key;
        }
    }

    private static final class FixtureWorld extends World {

        boolean loaded;
        TileEntity panel;

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return loaded;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            if (!loaded) throw new AssertionError("Must not load a chunk for a public read");
            return panel;
        }

        @Override
        public long getTotalWorldTime() {
            return 300;
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
