package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.common.covers.GTswn_Cover_DynamoWireless;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;

import gregtech.api.covers.CoverContext;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchDynamo;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEHatchInput;

/** Real GT hatch lists and cover types in a point-only fixture world; no game startup. */
public class DeviceLinkTargetsTest {

    @Test
    public void twelveHatchesDeduplicateNormalAndExoticListsWithoutCountingController() throws Exception {
        FixtureWorld world = world();
        DeviceMachineIdentityTest.IdentityMulti multi = new DeviceMachineIdentityTest.IdentityMulti();
        Tile controller = tile(world, multi, 0);
        controller.coversAt(ForgeDirection.NORTH, false);
        multi.mMachine = true;
        for (int i = 1; i <= 9; i++) {
            MTEHatchEnergy hatch = energy();
            Tile tile = tile(world, hatch, i);
            if (i <= 6) tile.coversAt(ForgeDirection.NORTH, false);
            multi.mEnergyHatches.add(hatch);
            multi.getExoticEnergyHatches()
                .add(hatch); // same live instance must count once
        }
        for (int i = 10; i <= 12; i++) {
            MTEHatchDynamo hatch = dynamo();
            Tile tile = tile(world, hatch, i);
            if (i <= 11) tile.coversAt(ForgeDirection.NORTH, true);
            multi.mDynamoHatches.add(hatch);
        }
        DeviceLinkSummary summary = DeviceLinkTargets.discover(multi).summary;
        assertEquals(DeviceLinkSummary.KNOWN, summary.state);
        assertEquals(6, summary.energyConnected);
        assertEquals(9, summary.energyTotal);
        assertEquals(2, summary.dynamoConnected);
        assertEquals(3, summary.dynamoTotal);
        assertEquals(8, summary.connected());
        assertEquals(12, summary.total());
        assertEquals(12, DeviceLinkTargets.discover(multi).targets.size());
        multi.mMachine = false;
        assertEquals(DeviceLinkSummary.UNVERIFIED, DeviceLinkTargets.discover(multi).summary);
    }

    @Test
    public void replacementUnloadAndWrongTypeCannotBecomeAnAttachedTarget() throws Exception {
        FixtureWorld world = world();
        DeviceMachineIdentityTest.IdentityMulti multi = new DeviceMachineIdentityTest.IdentityMulti();
        tile(world, multi, 0);
        multi.mMachine = true;
        MTEHatchEnergy energy = energy();
        Tile hatch = tile(world, energy, 1);
        multi.mEnergyHatches.add(energy);
        hatch.coversAt(ForgeDirection.NORTH, true); // wrong corresponding type
        assertEquals(0, DeviceLinkTargets.discover(multi).summary.connected());
        assertEquals(1, DeviceLinkTargets.discover(multi).summary.total());
        hatch.coversAt(ForgeDirection.NORTH, false);
        hatch.coversAt(ForgeDirection.SOUTH, false); // abnormal multiple faces
        assertEquals(0, DeviceLinkTargets.discover(multi).summary.connected());
        world.tiles.remove(1);
        assertEquals(DeviceLinkSummary.UNVERIFIED, DeviceLinkTargets.discover(multi).summary);
        tile(world, energy(), 1); // another same-type tile is not the registered instance
        assertEquals(DeviceLinkSummary.UNVERIFIED, DeviceLinkTargets.discover(multi).summary);
    }

    @Test
    public void automaticEnergyHatchDoesNotWaitForStructureAndExcludesDynamoControllerAndInput() throws Exception {
        FixtureWorld world = world();
        MTEHatchEnergy energy = energy();
        tile(world, energy, 1);
        assertNotNull(DeviceLinkTargets.placed(energy));
        assertEquals(ForgeDirection.NORTH, DeviceLinkTargets.placed(energy).side);
        MTEHatchDynamo dynamo = dynamo();
        tile(world, dynamo, 2);
        assertNull(DeviceLinkTargets.placed(dynamo));
        DeviceMachineIdentityTest.IdentityMulti controller = new DeviceMachineIdentityTest.IdentityMulti();
        tile(world, controller, 3);
        assertNull(DeviceLinkTargets.placed(controller));
        MTEHatchInput input = new MTEHatchInput("input", 1, new String[0], null);
        tile(world, input, 4);
        assertNull(DeviceLinkTargets.placed(input));
    }

    @Test
    public void desiredStateSkipsCompletedWorkAndRejectsWrongTypeWithoutTogglingOrChangingMode() throws Exception {
        FixtureWorld world = world();
        Tile tile = tile(world, energy(), 1);
        WirelessEnergyTap tap = new WirelessEnergyTap();
        ItemStack stack = new ItemStack(tap);
        stack.stackTagCompound = new NBTTagCompound();
        stack.stackTagCompound.setBoolean("OutputMode", true);
        assertEquals(
            WirelessEnergyTap.LinkResult.SKIPPED,
            tap.setLinkState(stack, null, world, tile, ForgeDirection.NORTH, false, false));
        tile.coversAt(ForgeDirection.NORTH, false);
        Object cover = tile.getCoverAtSide(ForgeDirection.NORTH);
        assertEquals(
            WirelessEnergyTap.LinkResult.SKIPPED,
            tap.setLinkState(stack, null, world, tile, ForgeDirection.NORTH, false, true));
        assertSame(cover, tile.getCoverAtSide(ForgeDirection.NORTH));
        assertEquals(
            WirelessEnergyTap.LinkResult.FAILED,
            tap.setLinkState(stack, null, world, tile, ForgeDirection.NORTH, true, true));
        tile.coversAt(ForgeDirection.SOUTH, false);
        assertEquals(
            WirelessEnergyTap.LinkResult.FAILED,
            tap.setLinkState(stack, null, world, tile, ForgeDirection.NORTH, false, false));
        assertTrue(stack.stackTagCompound.getBoolean("OutputMode"));
        assertFalse(stack.stackTagCompound.hasKey("LastUseTime"));
    }

    private static MTEHatchEnergy energy() {
        return new MTEHatchEnergy("energy", 3, new String[0], null);
    }

    private static MTEHatchDynamo dynamo() {
        return new MTEHatchDynamo("dynamo", 3, new String[0], null);
    }

    private static Tile tile(FixtureWorld world, IMetaTileEntity machine, int x) {
        Tile tile = new Tile();
        tile.setWorldObj(world);
        tile.xCoord = x;
        tile.yCoord = 64;
        tile.setMetaTileEntity(machine);
        machine.setBaseMetaTileEntity(tile);
        world.tiles.put(x, tile);
        return tile;
    }

    private static FixtureWorld world() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        FixtureWorld world = (FixtureWorld) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), FixtureWorld.class);
        world.tiles = new HashMap<>();
        return world;
    }

    private static final class Tile extends BaseMetaTileEntity {

        @Override
        public ForgeDirection getFrontFacing() {
            return ForgeDirection.NORTH;
        }

        void coversAt(ForgeDirection face, boolean dynamo) {
            covers[face.ordinal()] = dynamo ? new GTswn_Cover_DynamoWireless(new CoverContext(null, face, this))
                : new GTswn_Cover_EnergyWireless(new CoverContext(null, face, this));
        }
    }

    private static final class FixtureWorld extends World {

        Map<Integer, TileEntity> tiles;

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            throw new AssertionError("Chunk load");
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
            return tiles.containsKey(x);
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return tiles.get(x);
        }
    }
}
