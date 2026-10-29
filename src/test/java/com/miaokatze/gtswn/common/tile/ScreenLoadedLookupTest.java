package com.miaokatze.gtswn.common.tile;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;

import org.junit.Test;

import com.miaokatze.gtswn.common.panel.NetworkScreen;
import com.miaokatze.gtswn.common.tile.screen.ScreenStructure;

public class ScreenLoadedLookupTest {

    @Test
    public void nearbyDiscoverySkipsUnloadedChunksButRebuildsLoadedCores() throws Exception {
        FixtureWorld world = world();
        CountingPanel core = new CountingPanel();
        world.tiles.put("15:64:0", core);
        ScreenStructure.rebuildNearby(world, 15, 64, 0, 2);
        assertEquals(1, core.rebuilds);
        assertEquals(75, world.reads); // Three loaded x columns, five y rows and five z columns.
    }

    @Test
    public void unloadedCoreLookupKeepsMembershipAndRecoversWhenChunkReturns() throws Exception {
        FixtureWorld world = world();
        CountingPanel core = new CountingPanel();
        core.xCoord = 16;
        core.yCoord = 64;
        TileEntityNetworkInfoPanelExtender extender = new TileEntityNetworkInfoPanelExtender();
        NetworkScreen screen = new NetworkScreen();
        extender.attachToCore(core, screen);
        extender.setWorldObj(world);
        assertNull(extender.getCore());
        assertEquals(0, world.reads);
        assertTrue(extender.isPartOfScreen());
        assertSame(screen, extender.getScreen());

        world.loadedMaxX = 31;
        world.tiles.put("16:64:0", core);
        assertSame(core, extender.getCore());
        assertEquals(1, world.reads);
    }

    private static final class CountingPanel extends TileEntityNetworkInfoPanel {

        int rebuilds;

        @Override
        public void rebuildScreen() {
            rebuilds++;
        }
    }

    private static FixtureWorld world() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        FixtureWorld world = (FixtureWorld) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), FixtureWorld.class);
        world.tiles = new HashMap<>();
        world.loadedMaxX = 15;
        return world;
    }

    private static final class FixtureWorld extends World {

        Map<String, TileEntity> tiles;
        int loadedMaxX, reads;

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return x >= 0 && x <= loadedMaxX && y >= 0 && y < 256;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            assertTrue("Tile lookup must not load an unavailable chunk", blockExists(x, y, z));
            reads++;
            return tiles.get(x + ":" + y + ":" + z);
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
