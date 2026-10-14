package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

public class DeviceTerminalLandingTest {

    @Test
    public void nearestSafeFrontOrTopWinsWithFrontBreakingExactDistanceTie() throws Exception {
        FixtureWorld world = world();
        world.floor.add("9:63:8");
        world.floor.add("8:64:8");
        EntityPlayerMP player = player(8.5, 65, 8.5);
        assertArrayEquals(new double[] { 8.5, 65, 8.5 }, landing(world, player), 0);
        player.posX = 9.5;
        player.posY = 64;
        assertArrayEquals(new double[] { 9.5, 64, 8.5 }, landing(world, player), 0);
        player.posX = 9;
        player.posY = 64.5;
        assertArrayEquals(new double[] { 9.5, 64, 8.5 }, landing(world, player), 0);
    }

    @Test
    public void blockedAdjacentFrontSearchesFurtherFrontAndAvoidsBodyCollision() throws Exception {
        FixtureWorld world = world();
        world.floor.add("9:63:8");
        world.floor.add("10:63:8");
        world.floor.add("8:64:8");
        // An entity can block the adjacent front cell without creating a new solid support.
        world.obstacles.add(AxisAlignedBB.getBoundingBox(9, 64, 8, 10, 66, 9));
        EntityPlayerMP player = player(9.5, 64, 8.5);
        assertArrayEquals(new double[] { 10.5, 64, 8.5 }, landing(world, player), 0);
        assertTrue(world.collisionChecks > 0);
    }

    @Test
    public void nonCreativeActualFlyingAllowsAirButFlightPermissionAloneDoesNot() throws Exception {
        FixtureWorld world = world();
        EntityPlayerMP player = player(9.5, 64, 8.5);
        player.capabilities.isCreativeMode = false;
        player.capabilities.allowFlying = true;
        assertNull(landing(world, player));
        player.capabilities.isFlying = true;
        assertArrayEquals(new double[] { 9.5, 64, 8.5 }, landing(world, player), 0);
        player.capabilities.isFlying = false;
        player.capabilities.isCreativeMode = true;
        assertNull(landing(world, player));
    }

    @Test
    public void flyingStillRejectsCollisionsAndLiquid() throws Exception {
        FixtureWorld world = world();
        EntityPlayerMP player = player(9.5, 64, 8.5);
        player.capabilities.isFlying = true;
        world.obstacles.add(AxisAlignedBB.getBoundingBox(9, 64, 8, 10, 66, 9));
        double[] result = landing(world, player);
        assertNotNull(result);
        assertFalse(result[0] == 9.5 && result[1] == 64 && result[2] == 8.5);
        world.liquids.add(AxisAlignedBB.getBoundingBox(4, 60, 4, 14, 72, 14));
        assertNull(landing(world, player));
    }

    @Test
    public void unavailableChunksAreNeverLoadedOrReadAndWideBodiesCheckEveryCoveredChunk() throws Exception {
        FixtureWorld world = world();
        world.loadedChunks.clear();
        EntityPlayerMP player = player(9.5, 64, 8.5);
        player.capabilities.isFlying = true;
        assertNull(landing(world, player));
        assertEquals(0, world.blockReads);
        assertEquals(0, world.collisionChecks);
        world.loadedChunks.add("0:0");
        player.width = 2;
        player.posX = 15.5;
        player.posY = 65;
        DeviceTerminalDataStore.MachineRecord record = record();
        record.x = 15;
        assertArrayEquals(
            new double[] { 14.5, 65, 8.5 },
            DeviceTerminalTeleport.safeLanding(world, ForgeDirection.EAST, record, player),
            0);
    }

    @Test
    public void groundedSearchDoesNotFallBackToUnrelatedBacksideAndHeightIsAlwaysRespected() throws Exception {
        FixtureWorld world = world();
        world.floor.add("7:63:8");
        EntityPlayerMP player = player(7.5, 64, 8.5);
        assertNull(landing(world, player));
        player.capabilities.isFlying = true;
        world.actualHeight = 62;
        assertNull(landing(world, player));
    }

    @Test
    public void wideTallFlyingPlayerAvoidsBurningCellsAcrossEntireBody() throws Exception {
        FixtureWorld world = world();
        EntityPlayerMP player = player(9.5, 64, 8.5);
        player.width = 2;
        player.height = 3.8F;
        player.capabilities.isFlying = true;
        world.burning.add("10:67:9");
        double[] result = landing(world, player);
        assertNotNull(result);
        assertTrue(world.burningReads > 0);
        AxisAlignedBB body = AxisAlignedBB.getBoundingBox(
            result[0] - 1,
            result[1],
            result[2] - 1,
            result[0] + 1,
            result[1] + player.height,
            result[2] + 1);
        assertFalse(body.intersectsWith(AxisAlignedBB.getBoundingBox(10, 67, 9, 11, 68, 10)));
    }

    @Test
    public void negativeChunkEdgeDoesNotReadAnExtraUnavailableLiquidMargin() throws Exception {
        FixtureWorld world = world();
        world.loadedChunks.clear();
        world.loadedChunks.add("-1:0");
        EntityPlayerMP player = player(-15.5, 65, 8.5);
        player.capabilities.isFlying = true;
        DeviceTerminalDataStore.MachineRecord record = record();
        record.x = -16;
        assertArrayEquals(
            new double[] { -15.5, 65, 8.5 },
            DeviceTerminalTeleport.safeLanding(world, ForgeDirection.EAST, record, player),
            0);
    }

    @Test
    public void abnormallyLargeBodyFailsBeforeUnboundedBlockOrCollisionWork() throws Exception {
        FixtureWorld world = world();
        EntityPlayerMP player = player(9.5, 64, 8.5);
        player.width = 4;
        player.height = 150;
        player.capabilities.isFlying = true;
        assertNull(landing(world, player));
        assertTrue(world.blockReads <= 120);
        assertEquals(0, world.collisionChecks);
    }

    private static double[] landing(FixtureWorld world, EntityPlayerMP player) {
        return DeviceTerminalTeleport.safeLanding(world, ForgeDirection.EAST, record(), player);
    }

    private static DeviceTerminalDataStore.MachineRecord record() {
        DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
        record.x = 8;
        record.y = 64;
        record.z = 8;
        return record;
    }

    private static EntityPlayerMP player(double x, double y, double z) throws Exception {
        EntityPlayerMP player = (EntityPlayerMP) allocate(EntityPlayerMP.class);
        player.posX = x;
        player.posY = y;
        player.posZ = z;
        player.width = 0.6F;
        player.height = 1.8F;
        player.capabilities = new PlayerCapabilities();
        return player;
    }

    private static FixtureWorld world() throws Exception {
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        world.floor = new HashSet<>();
        world.burning = new HashSet<>();
        world.loadedChunks = new HashSet<>();
        world.loadedChunks.add("0:0");
        world.obstacles = new ArrayList<>();
        world.liquids = new ArrayList<>();
        world.actualHeight = 256;
        world.providerStub = (IChunkProvider) Proxy.newProxyInstance(
            IChunkProvider.class.getClassLoader(),
            new Class<?>[] { IChunkProvider.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("chunkExists")) return world.loadedChunks.contains(args[0] + ":" + args[1]);
                throw new AssertionError("Search attempted chunk loading: " + method.getName());
            });
        return world;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), type);
    }

    private static class FixtureBlock extends Block {

        FixtureBlock(Material material) {
            super(material);
        }
    }

    private static class FixtureWorld extends World {

        private static final Block AIR = new FixtureBlock(Material.air);
        private static final Block SOLID = new FixtureBlock(Material.rock);
        private static final Block LIQUID = new FixtureBlock(Material.water);
        private static final Block BURNING = new FixtureBlock(Material.air) {

            @Override
            public boolean isBurning(IBlockAccess world, int x, int y, int z) {
                return true;
            }
        };
        Set<String> floor, loadedChunks, burning;
        List<AxisAlignedBB> obstacles, liquids;
        IChunkProvider providerStub;
        int blockReads, collisionChecks, burningReads, actualHeight;

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return providerStub;
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
        public IChunkProvider getChunkProvider() {
            return providerStub;
        }

        @Override
        public int getActualHeight() {
            return actualHeight;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            assertTrue("Read from unavailable chunk", loadedChunks.contains((x >> 4) + ":" + (z >> 4)));
            blockReads++;
            if (burning.contains(x + ":" + y + ":" + z)) {
                burningReads++;
                return BURNING;
            }
            AxisAlignedBB blockBox = AxisAlignedBB.getBoundingBox(x, y, z, x + 1, y + 1, z + 1);
            if (liquids.stream()
                .anyMatch(blockBox::intersectsWith)) return LIQUID;
            return floor.contains(x + ":" + y + ":" + z) ? SOLID : AIR;
        }

        @Override
        public boolean isAnyLiquid(AxisAlignedBB box) {
            verifyLoadedBox(box);
            return liquids.stream()
                .anyMatch(box::intersectsWith);
        }

        @Override
        public List<AxisAlignedBB> getCollidingBoundingBoxes(Entity entity, AxisAlignedBB box) {
            verifyLoadedBox(box);
            collisionChecks++;
            List<AxisAlignedBB> result = new ArrayList<>();
            for (AxisAlignedBB obstacle : obstacles) {
                if (box.intersectsWith(obstacle)) result.add(obstacle);
            }
            return result;
        }

        private void verifyLoadedBox(AxisAlignedBB box) {
            for (int x = ((int) Math.floor(box.minX)) >> 4; x <= ((int) Math.floor(box.maxX)) >> 4; x++) {
                for (int z = ((int) Math.floor(box.minZ)) >> 4; z <= ((int) Math.floor(box.maxZ)) >> 4; z++) {
                    assertTrue("Body query reached unavailable chunk", loadedChunks.contains(x + ":" + z));
                }
            }
        }
    }
}
