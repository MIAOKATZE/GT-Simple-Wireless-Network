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

    @Test
    public void partialRebuildKeepsSavedMembershipThenRetriesWhenChunkReturns() throws Exception {
        FixtureWorld world = world();
        CountingPanel core = panel(world, 15, 64);
        TileEntityNetworkInfoPanelExtender part = extender(world, 16, 64);
        ScreenStructure structure = new ScreenStructure(core);
        NetworkScreen saved = screen(15, 16, 64);
        structure.setScreen(saved);
        part.attachToCore(core, saved);
        structure.rebuild();
        assertSame(saved, structure.getScreen());
        assertTrue(part.isPartOfScreen());
        assertFalse(structure.isInitialized());
        int reads = world.reads;
        for (int tick = 0; tick < 19; tick++) structure.tick();
        assertEquals("No repeated BFS every tick", reads, world.reads);
        world.loadedMaxX = 31;
        structure.tick();
        assertTrue(structure.isInitialized());
        assertEquals(16, structure.getScreen().maxX);
        assertSame(structure.getScreen(), part.getScreen());
    }

    @Test
    public void buildHeightBoundaryIsKnownAirNotAnUnloadedChunk() throws Exception {
        FixtureWorld world = world();
        CountingPanel core = panel(world, 8, 0);
        ScreenStructure structure = new ScreenStructure(core);
        structure.tick();
        assertTrue(structure.isInitialized());
        assertEquals(0, structure.getScreen().minY);
    }

    @Test
    public void detachSkipsUnloadedMembersWhichRetireAfterLoadedCoreRemoval() throws Exception {
        FixtureWorld world = world();
        CountingPanel core = panel(world, 15, 64);
        TileEntityNetworkInfoPanelExtender part = extender(world, 16, 64);
        ScreenStructure structure = new ScreenStructure(core);
        NetworkScreen saved = screen(15, 16, 64);
        structure.setScreen(saved);
        core.screen = saved;
        part.attachToCore(core, saved);
        structure.detach();
        assertTrue(part.isPartOfScreen());
        world.tiles.remove("15:64:0");
        world.loadedMaxX = 31;
        part.updateEntity();
        assertFalse(part.isPartOfScreen());
        assertNull(part.getScreen());
    }

    @Test
    public void unavailableCoreDoesNotRetireMembershipDuringPeriodicReconciliation() throws Exception {
        FixtureWorld world = world();
        CountingPanel core = panel(world, 16, 64);
        TileEntityNetworkInfoPanelExtender part = extender(world, 15, 64);
        NetworkScreen saved = screen(15, 16, 64);
        core.screen = saved;
        part.attachToCore(core, saved);
        part.updateEntity();
        assertTrue(part.isPartOfScreen());
        assertEquals(0, world.reads);
        world.loadedMaxX = 31;
        core.screen = screen(16, 16, 64);
        for (int tick = 0; tick < 20; tick++) part.updateEntity();
        assertFalse(part.isPartOfScreen());
    }

    @Test
    public void missingScreenTagClearsPreviouslySyncedExtenderBounds() {
        TileEntityNetworkInfoPanelExtender part = new TileEntityNetworkInfoPanelExtender();
        part.attachToCore(new CountingPanel(), screen(1, 2, 64));
        part.readFromNBT(new net.minecraft.nbt.NBTTagCompound());
        assertNull(part.getScreen());
        assertFalse(part.isPartOfScreen());
    }

    @Test
    public void detachNeverReleasesAnExtenderNowOwnedByAnotherCore() throws Exception {
        FixtureWorld world = world();
        CountingPanel oldCore = panel(world, 8, 64);
        CountingPanel currentCore = panel(world, 10, 64);
        TileEntityNetworkInfoPanelExtender part = extender(world, 9, 64);
        ScreenStructure oldStructure = new ScreenStructure(oldCore);
        oldStructure.setScreen(screen(8, 9, 64));
        NetworkScreen current = screen(9, 10, 64);
        part.attachToCore(currentCore, current);
        oldStructure.detach();
        oldStructure.detach();
        assertTrue(part.isPartOfScreen());
        assertSame(currentCore, part.getCore());
        assertSame(current, part.getScreen());
    }

    @Test
    public void rebuildingAnExcludedBranchDoesNotDetachAnotherCoresMember() throws Exception {
        FixtureWorld world = world();
        CountingPanel core = panel(world, 8, 64);
        CountingPanel other = panel(world, 8, 66);
        extender(world, 9, 64);
        TileEntityNetworkInfoPanelExtender branch = extender(world, 8, 65);
        NetworkScreen otherScreen = screen(8, 8, 65);
        otherScreen.maxY = 66;
        branch.attachToCore(other, otherScreen);
        new ScreenStructure(core).rebuild();
        assertTrue(branch.isPartOfScreen());
        assertSame(other, branch.getCore());
        assertSame(otherScreen, branch.getScreen());
    }

    @Test
    public void clientRebuildAndDetachCannotRewriteAuthoritativeSyncedMembership() throws Exception {
        FixtureWorld world = world();
        world.isRemote = true;
        CountingPanel core = panel(world, 8, 64);
        TileEntityNetworkInfoPanelExtender part = extender(world, 9, 64);
        ScreenStructure structure = new ScreenStructure(core);
        NetworkScreen synced = screen(8, 9, 64);
        structure.setScreen(synced);
        part.attachToCore(core, synced);
        structure.rebuild();
        structure.detach();
        assertSame(synced, structure.getScreen());
        assertSame(synced, part.getScreen());
        assertTrue(part.isPartOfScreen());
        assertEquals(0, world.reads);
    }

    @Test
    public void successfulDeferredRebuildStopsRetryingAndSurvivesAnotherInterruption() throws Exception {
        FixtureWorld world = world();
        CountingPanel core = panel(world, 15, 64);
        extender(world, 16, 64);
        ScreenStructure structure = new ScreenStructure(core);
        structure.tick();
        assertFalse(structure.isInitialized());
        world.loadedMaxX = 31;
        for (int tick = 0; tick < 20; tick++) structure.tick();
        NetworkScreen first = structure.getScreen();
        int reads = world.reads;
        for (int tick = 0; tick < 100; tick++) structure.tick();
        assertEquals(reads, world.reads);
        world.loadedMaxX = 15;
        structure.rebuild();
        assertSame(first, structure.getScreen());
        assertFalse(structure.isInitialized());
        world.loadedMaxX = 31;
        world.tiles.remove("16:64:0");
        for (int tick = 0; tick < 20; tick++) structure.tick();
        assertTrue(structure.isInitialized());
        assertEquals(15, structure.getScreen().maxX);
    }

    static NetworkScreen screen(int left, int right, int y) {
        NetworkScreen screen = new NetworkScreen();
        screen.minX = left;
        screen.maxX = right;
        screen.minY = screen.maxY = y;
        screen.facing = 3;
        return screen;
    }

    static CountingPanel panel(FixtureWorld world, int x, int y) {
        CountingPanel core = new CountingPanel();
        core.xCoord = x;
        core.yCoord = y;
        core.setWorldObj(world);
        world.tiles.put(x + ":" + y + ":0", core);
        return core;
    }

    static TileEntityNetworkInfoPanelExtender extender(FixtureWorld world, int x, int y) {
        TileEntityNetworkInfoPanelExtender part = new TileEntityNetworkInfoPanelExtender() {

            @Override
            public int getBlockMetadata() {
                return 3;
            }

            @Override
            public void markDirty() {}
        };
        part.xCoord = x;
        part.yCoord = y;
        part.setWorldObj(world);
        world.tiles.put(x + ":" + y + ":0", part);
        return part;
    }

    static final class CountingPanel extends TileEntityNetworkInfoPanel {

        int rebuilds;
        NetworkScreen screen;
        ScreenStructure structure;

        @Override
        public NetworkScreen getScreen() {
            return structure == null ? screen : structure.getScreen();
        }

        @Override
        public int getBlockMetadata() {
            return 3;
        }

        @Override
        public void markDirty() {}

        @Override
        public void detachScreen() {
            if (structure != null) structure.detach();
        }

        @Override
        public void rebuildScreen() {
            rebuilds++;
            if (structure != null) structure.rebuild();
        }
    }

    static FixtureWorld world() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        FixtureWorld world = (FixtureWorld) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), FixtureWorld.class);
        world.tiles = new HashMap<>();
        world.unloadedX = new java.util.HashSet<>();
        world.loadedMaxX = 15;
        return world;
    }

    static final class FixtureWorld extends World {

        Map<String, TileEntity> tiles;
        java.util.Set<Integer> unloadedX;
        int loadedMaxX, reads;

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return x >= 0 && x <= loadedMaxX && !unloadedX.contains(x) && y >= 0 && y < 256;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            assertTrue("Tile lookup must not load an unavailable chunk", blockExists(x, y, z));
            reads++;
            return tiles.get(x + ":" + y + ":" + z);
        }

        @Override
        public void removeTileEntity(int x, int y, int z) {
            tiles.remove(x + ":" + y + ":" + z);
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
            return true;
        }

        @Override
        public int getHeight() {
            return 256;
        }

        @Override
        public void markBlockForUpdate(int x, int y, int z) {}

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
