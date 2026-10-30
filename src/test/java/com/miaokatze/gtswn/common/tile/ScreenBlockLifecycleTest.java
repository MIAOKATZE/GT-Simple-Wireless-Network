package com.miaokatze.gtswn.common.tile;

import static com.miaokatze.gtswn.common.tile.ScreenLoadedLookupTest.*;
import static org.junit.Assert.*;

import java.lang.reflect.Field;

import net.minecraft.entity.passive.EntityPig;

import org.junit.Test;

import com.miaokatze.gtswn.common.block.BlockNetworkInfoPanel;
import com.miaokatze.gtswn.common.block.BlockNetworkInfoPanelExtender;
import com.miaokatze.gtswn.common.tile.screen.ScreenStructure;

/** Exercise the actual placement/removal entry points, not only their loaded-lookup helpers. */
public class ScreenBlockLifecycleTest {

    @Test
    public void placingAndBreakingAtTheFarEdgeReachesTheCoreBeyondTheOldTwoBlockRadius() throws Exception {
        FixtureWorld world = world();
        CountingPanel core = panel(world, 8, 64);
        core.structure = new ScreenStructure(core);
        extender(world, 9, 64);
        extender(world, 10, 64);
        core.rebuildScreen();
        assertEquals(10, core.getScreen().maxX);
        TileEntityNetworkInfoPanelExtender added = extender(world, 11, 64);
        BlockNetworkInfoPanelExtender block = allocate(BlockNetworkInfoPanelExtender.class);
        block.onBlockPlacedBy(world, 11, 64, 0, allocate(EntityPig.class), null);
        assertEquals(11, core.getScreen().maxX);
        assertSame(core, added.getCore());
        block.breakBlock(world, 11, 64, 0, block, 3);
        assertEquals(10, core.getScreen().maxX);
        assertNull(world.tiles.get("11:64:0"));
    }

    @Test
    public void breakingAnUnattachedConnectorStillRebuildsBothAffectedComponents() throws Exception {
        FixtureWorld world = world();
        CountingPanel left = panel(world, 5, 64);
        CountingPanel right = panel(world, 13, 64);
        for (int x = 6; x < 13; x++) extender(world, x, 64);
        // The connector may be excluded from either maximal filled rectangle; membership is not topology.
        BlockNetworkInfoPanelExtender block = allocate(BlockNetworkInfoPanelExtender.class);
        block.breakBlock(world, 9, 64, 0, block, 3);
        assertEquals(1, left.rebuilds);
        assertEquals(1, right.rebuilds);
    }

    @Test
    public void mainPanelBreakNeverLoadsChunksWhileScanningSavedBounds() throws Exception {
        FixtureWorld world = world();
        CountingPanel core = panel(world, 15, 64);
        core.screen = screen(15, 16, 64);
        BlockNetworkInfoPanel block = allocate(BlockNetworkInfoPanel.class);
        block.breakBlock(world, 15, 64, 0, block, 3);
        assertNull(world.tiles.get("15:64:0"));
    }

    @Test
    public void farEdgePlacementWithAnUnavailableFrontierRetainsBoundsUntilRetryCompletes() throws Exception {
        FixtureWorld world = world();
        CountingPanel core = panel(world, 11, 64);
        core.structure = new ScreenStructure(core);
        for (int x = 12; x <= 14; x++) extender(world, x, 64);
        core.rebuildScreen();
        assertEquals(14, core.getScreen().maxX);
        extender(world, 15, 64);
        BlockNetworkInfoPanelExtender block = allocate(BlockNetworkInfoPanelExtender.class);
        block.onBlockPlacedBy(world, 15, 64, 0, allocate(EntityPig.class), null);
        assertEquals(14, core.getScreen().maxX);
        assertFalse(core.structure.isInitialized());
        world.loadedMaxX = 31;
        for (int tick = 0; tick < 20; tick++) core.structure.tick();
        assertTrue(core.structure.isInitialized());
        assertEquals(15, core.getScreen().maxX);
    }

    @Test
    public void savedOwnershipSchedulesRetryAcrossAnUnloadedMiddleChunk() throws Exception {
        FixtureWorld world = world();
        world.loadedMaxX = 47;
        CountingPanel core = panel(world, 8, 64);
        core.structure = new ScreenStructure(core);
        for (int x = 9; x <= 34; x++) extender(world, x, 64);
        core.rebuildScreen();
        assertEquals(34, core.getScreen().maxX);
        for (int x = 16; x < 32; x++) world.unloadedX.add(x);
        extender(world, 35, 64);
        BlockNetworkInfoPanelExtender block = allocate(BlockNetworkInfoPanelExtender.class);
        block.onBlockPlacedBy(world, 35, 64, 0, allocate(EntityPig.class), null);
        assertFalse(core.structure.isInitialized());
        assertEquals(34, core.getScreen().maxX);
        for (int tick = 0; tick < 40; tick++) core.structure.tick();
        assertFalse(core.structure.isInitialized());
        assertEquals(34, core.getScreen().maxX);
        world.unloadedX.clear();
        for (int tick = 0; tick < 20; tick++) core.structure.tick();
        assertTrue(core.structure.isInitialized());
        assertEquals(35, core.getScreen().maxX);
    }

    @Test
    public void rawReplacementLeavesTheRemovalRectangleUntilThePlacementCallbackRuns() throws Exception {
        FixtureWorld world = world();
        CountingPanel core = panel(world, 8, 64);
        core.structure = new ScreenStructure(core);
        extender(world, 9, 64);
        extender(world, 8, 65);
        extender(world, 9, 65);
        core.rebuildScreen();
        assertEquals(
            2,
            core.getScreen()
                .getWidth());
        assertEquals(
            2,
            core.getScreen()
                .getHeight());
        BlockNetworkInfoPanelExtender block = allocate(BlockNetworkInfoPanelExtender.class);
        int[][] positions = { { 9, 64 }, { 8, 65 }, { 9, 65 } };
        for (int[] pos : positions) {
            block.breakBlock(world, pos[0], pos[1], 0, block, 3);
            extender(world, pos[0], pos[1]); // The old fixture's raw setBlock omitted onBlockPlacedBy.
        }
        assertEquals(
            2,
            core.getScreen()
                .getWidth());
        assertEquals(
            1,
            core.getScreen()
                .getHeight());
        for (int tick = 0; tick < 40; tick++) core.structure.tick();
        assertEquals(
            "Waiting cannot repair a completed intermediate rebuild",
            1,
            core.getScreen()
                .getHeight());
        block.onBlockPlacedBy(world, 9, 65, 0, allocate(EntityPig.class), null);
        assertEquals(
            2,
            core.getScreen()
                .getWidth());
        assertEquals(
            2,
            core.getScreen()
                .getHeight());
        for (int[] pos : positions) {
            TileEntityNetworkInfoPanelExtender part = (TileEntityNetworkInfoPanelExtender) world
                .getTileEntity(pos[0], pos[1], 0);
            assertSame(core, part.getCore());
            assertSame(core.getScreen(), part.getScreen());
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(
            unsafe.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), type));
    }
}
