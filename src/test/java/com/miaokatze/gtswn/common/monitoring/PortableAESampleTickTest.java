package com.miaokatze.gtswn.common.monitoring;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;

import org.junit.Test;

import com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot;
import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;
import com.miaokatze.gtswn.common.panel.AEMonitorDataStore;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

/** Uses the real per-world server datastore; no packet-fed client mirror or game server is needed. */
public class PortableAESampleTickTest {

    @Test
    public void chartOnlyServerHistoryEstablishesFreshnessWithoutClientMirror() throws Exception {
        FixtureWorld world = world();
        Panel panel = new Panel();
        panel.setWorldObj(world);
        AEMonitorDataStore store = AEMonitorDataStore.get(world);
        assertTrue(
            panel.getAEMonitorLatest()
                .isEmpty());
        assertTrue(
            panel.getMonitoredItems()
                .isEmpty());
        assertEquals(0, PortableProfileAdapter.aeSampleTick(panel));

        // The collector stores chart bindings in this dataset even with no real-time monitor rows.
        store.getOrCreate(panel.historyKey)
            .addSample("chart-only-item", 42, 1200, 60000);
        assertEquals(1200, PortableProfileAdapter.aeSampleTick(panel));
        assertTrue(
            panel.getAEMonitorLatest()
                .isEmpty());
        store.getOrCreate(panel.historyKey)
            .addSample("chart-only-item", 43, 1300, 65000);
        assertEquals(1300, PortableProfileAdapter.aeSampleTick(panel));
    }

    @Test
    public void sourceReplacementAndUnavailableIdentityNeverBorrowArchivedFreshness() throws Exception {
        FixtureWorld world = world();
        Panel panel = new Panel();
        panel.setWorldObj(world);
        AEMonitorDataStore store = AEMonitorDataStore.get(world);
        store.getOrCreate(panel.historyKey)
            .addSample("item", 42, 9000, 60000);
        panel.historyKey = "0:1:64:2#panel#new-source";
        assertEquals(0, PortableProfileAdapter.aeSampleTick(panel));
        store.getOrCreate(panel.historyKey)
            .addSample("item", 1, 1200, 60000);
        assertEquals(1200, PortableProfileAdapter.aeSampleTick(panel));
        panel.historyKey = null;
        assertEquals(0, PortableProfileAdapter.aeSampleTick(panel));
        assertEquals(0, PortableProfileAdapter.aeSampleTick(null));
    }

    @Test
    public void staleOrFutureSeriesCannotBorrowFreshnessFromAnotherKey() throws Exception {
        FixtureWorld world = world();
        Panel panel = new Panel();
        panel.setWorldObj(world);
        AEMonitorDataSet data = AEMonitorDataStore.get(world)
            .getOrCreate(panel.historyKey);
        data.addSample("archived", 42, 10, 100);
        data.addSample("live", 43, 10000, 100000);
        assertEquals(10, PortableProfileAdapter.aeSampleTick(panel));
        data.addSample("future", 44, 10001, 100001);
        assertEquals(0, PortableProfileAdapter.aeSampleTick(panel));
        data.clear("future");
        data.clear("archived");
        assertEquals(10000, PortableProfileAdapter.aeSampleTick(panel));
    }

    @Test
    public void boundedExportNeverBuildsFullSnapshotOrCopiesOmittedHistories() {
        BoundedData data = new BoundedData();
        for (int i = 0; i < 4096; i++) data.addSample("key-" + i, i, 100, 1000);
        assertEquals(4096, data.sampledKeyCount());
        assertTrue(
            PortableProfileAdapter.exportAE(data, 0)
                .isEmpty());
        assertEquals(0, data.queries);
        List<WebDashboardSnapshot.Card> cards = PortableProfileAdapter.exportAE(data, 2);
        assertEquals(2, cards.size());
        assertEquals(2, data.queries);
        assertEquals("key-0", cards.get(0).label);
        assertArrayEquals(
            new double[] { 0 },
            cards.get(0)
                .history(),
            0);
        assertEquals("key-1", cards.get(1).label);
        assertEquals(4096, data.sampledKeyCount()); // All omitted histories remain intact.
        assertEquals(4095, data.newest("key-4095").amount);
    }

    private static final class BoundedData extends AEMonitorDataSet {

        int queries;

        @Override
        public Set<String> getKeys() {
            throw new AssertionError("Web must not enumerate/copy all historical keys");
        }

        @Override
        public double averageRate(String key) {
            throw new AssertionError("Unused rates must not copy history");
        }

        @Override
        public long spanTicks(String key, int window) {
            throw new AssertionError("Unused spans must not copy history");
        }

        @Override
        public List<AEMonitorSample> query(String key, int window) {
            queries++;
            return super.query(key, window);
        }
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
        return world;
    }

    private static final class Panel extends TileEntityNetworkInfoPanel {

        String historyKey = "0:1:64:2#panel#original-source";

        @Override
        public String getAEMonitorDataKey() {
            return historyKey;
        }
    }

    private static final class FixtureWorld extends World {

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public long getTotalWorldTime() {
            return 10000;
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
