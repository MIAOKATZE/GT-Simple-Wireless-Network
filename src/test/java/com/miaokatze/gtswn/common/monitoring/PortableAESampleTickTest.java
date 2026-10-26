package com.miaokatze.gtswn.common.monitoring;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;

import org.junit.Test;

import com.miaokatze.gtswn.common.panel.AEMonitorDataStore;
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
