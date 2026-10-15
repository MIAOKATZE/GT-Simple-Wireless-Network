package com.miaokatze.gtswn.common.testworld;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.quantum.QuantumTileLifecycle;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.register.BlockRegistrar;
import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import gregtech.api.GregTechAPI;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.covers.Cover;

/** Fixed, deliberately small fixtures. Every mutation is confined to the dedicated test dimension. */
public final class TestWorldFixtures {

    public static final int Y = 65;
    public static final int ENERGY_X = -20, DYNAMO_X = -16, MONITOR_X = -12, EU_Z = -16;
    public static final int PANEL_X = -20, PANEL_Z = -4;
    public static final int CONTROLLER_X = 8, TARGET_X = 20, CONTROLLER_Z = -16;
    public static final int NODE_X = 8, NODE_Z = -8;
    public static final int DEVICE_X = -8, DEVICE_Z = -16;
    public static final int INCORPORATION_X = 20, INCORPORATION_Z = -8;
    public static final ForgeDirection COVER_SIDE = ForgeDirection.UP;

    private TestWorldFixtures() {}

    public static void build(WorldServer world) {
        requireTestWorld(world);
        UUID displayOwner = owner("display");
        machine(world, ENERGY_X, EU_Z, ItemList.Hatch_Energy_LV.get(1), displayOwner);
        machine(world, DYNAMO_X, EU_Z, ItemList.Hatch_Dynamo_LV.get(1), displayOwner);
        machine(world, MONITOR_X, EU_Z, GTSWNItemList.Wireless_Energy_Monitor.get(1), null);
        machine(world, DEVICE_X, DEVICE_Z, ItemList.Machine_LV_Macerator.get(1), displayOwner);
        panel(world, null);
        aeBlock(
            world,
            CONTROLLER_X,
            Y,
            CONTROLLER_Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeBlock()
                .get());
        aeBlock(
            world,
            TARGET_X,
            Y,
            CONTROLLER_Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeBlock()
                .get());
        aeBlock(
            world,
            INCORPORATION_X,
            Y,
            INCORPORATION_Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .energyCell()
                .maybeBlock()
                .get());
        place(world, NODE_X, Y, NODE_Z, BlockRegistrar.networkQuantumNode, 0);
        place(world, -24, Y, 8, Blocks.chest, 2);
        TileEntityChest chest = (TileEntityChest) world.getTileEntity(-24, Y, 8);
        int slot = 0;
        for (GTSWNItemList item : GTSWNItemList.values()) {
            ItemStack stack = item.get(1);
            if (stack != null) chest.setInventorySlotContents(slot++, stack);
        }
        chest.setInventorySlotContents(
            slot++,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get());
        chest.setInventorySlotContents(
            slot,
            AEApi.instance()
                .definitions()
                .blocks()
                .energyCell()
                .maybeStack(1)
                .get());
        chest.markDirty();
    }

    public static UUID owner(String seed) {
        return UUID.nameUUIDFromBytes(("GTSWN isolated test network:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    public static FakePlayer player(WorldServer world, UUID uuid) {
        requireTestWorld(world);
        FakePlayer player = FakePlayerFactory.get(world, new GameProfile(uuid, actorName(uuid)));
        player.setPosition(0.5, Y, 0.5);
        player.inventory.clearInventory(null, -1);
        player.inventory.currentItem = 0;
        return player;
    }

    public static String actorName(UUID uuid) {
        return "GS_" + uuid.toString()
            .replace("-", "")
            .substring(0, 13);
    }

    public static BaseMetaTileEntity machine(WorldServer world, int x, int z, ItemStack stack, UUID owner) {
        place(world, x, Y, z, GregTechAPI.sBlockMachines, 0);
        BaseMetaTileEntity tile = (BaseMetaTileEntity) world.getTileEntity(x, Y, z);
        tile.setInitialValuesAsNBT(null, (short) stack.getItemDamage());
        tile.setOwnerUuid(owner);
        tile.setOwnerName("GTSWN isolated test");
        tile.setFrontFacing(ForgeDirection.NORTH);
        tile.markDirty();
        return tile;
    }

    public static Cover cover(BaseMetaTileEntity tile, GTSWNItemList item) {
        if (tile.hasCoverAtSide(COVER_SIDE)) tile.detachCover(COVER_SIDE);
        Cover cover = CoverRegistry.buildCover(item.get(1), COVER_SIDE, tile);
        tile.attachCover(cover);
        return cover;
    }

    public static TileEntityNetworkInfoPanel panel(WorldServer world, UUID owner) {
        place(world, PANEL_X, Y, PANEL_Z, BlockRegistrar.networkInfoPanel, 3);
        place(world, PANEL_X + 1, Y, PANEL_Z, BlockRegistrar.networkInfoPanelExtender, 3);
        place(world, PANEL_X, Y + 1, PANEL_Z, BlockRegistrar.networkInfoPanelExtender, 3);
        place(world, PANEL_X + 1, Y + 1, PANEL_Z, BlockRegistrar.networkInfoPanelExtender, 3);
        TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) world.getTileEntity(PANEL_X, Y, PANEL_Z);
        panel.bindOwner(owner, "GTSWN isolated test");
        return panel;
    }

    public static TileEntity aeBlock(WorldServer world, int x, int y, int z, Block block) {
        place(world, x, y, z, block, 0);
        return world.getTileEntity(x, y, z);
    }

    /** Uses AE's real item placement, including orientation, proxy owner and native onPlacement callbacks. */
    public static TileEntity aeOwnedBlock(WorldServer world, int x, int y, int z, ItemStack stack,
        EntityPlayer player) {
        requireTestWorld(world);
        if (x < -32 || x > 31 || z < -32 || z > 31 || y < 64 || y > 127)
            throw new IllegalArgumentException("AE fixture outside test platform");
        if (!(stack.getItem() instanceof ItemBlock))
            throw new IllegalArgumentException("AE fixture is not an item block");
        TestWorldProbes.addPosition(x, y, z);
        world.setBlockToAir(x, y, z);
        ItemBlock item = (ItemBlock) stack.getItem();
        if (!item
            .placeBlockAt(stack, player, world, x, y, z, 1, .5f, .5f, .5f, item.getMetadata(stack.getItemDamage())))
            throw new IllegalStateException("Native AE item placement failed");
        return world.getTileEntity(x, y, z);
    }

    public static void place(WorldServer world, int x, int y, int z, Block block, int metadata) {
        requireTestWorld(world);
        if (x < -32 || x > 31 || z < -32 || z > 31 || y < 64 || y > 127)
            throw new IllegalArgumentException("Fixture outside test platform");
        TestWorldProbes.addPosition(x, y, z);
        world.setBlockToAir(x, y, z);
        if (!world.setBlock(x, y, z, block, metadata, 3))
            throw new IllegalStateException("Could not place fixture at " + x + "," + y + "," + z);
    }

    /** Replays the actual tile serialization and invalidation/validation lifecycle, with no second live copy. */
    public static TileEntity reload(WorldServer world, TileEntity original) {
        requireTestWorld(world);
        NBTTagCompound saved = new NBTTagCompound();
        original.writeToNBT(saved);
        // Mirrors Chunk.onChunkUnload's production hook before AE overrides call invalidate().
        QuantumTileLifecycle.unloaded(original);
        original.onChunkUnload();
        world.removeTileEntity(original.xCoord, original.yCoord, original.zCoord);
        TileEntity restored = TileEntity.createAndLoadEntity(saved);
        if (restored == null) throw new IllegalStateException("Tile NBT did not recreate tile");
        world.setTileEntity(original.xCoord, original.yCoord, original.zCoord, restored);
        return restored;
    }

    public static void requireTestWorld(WorldServer world) {
        if (world == null || !(world.provider instanceof WorldProviderTest))
            throw new IllegalArgumentException("Fixtures require the GTSWN test dimension");
    }

    static void require(TestContext ctx, boolean condition, String detail) {
        ctx.check(condition, detail);
        if (!condition) throw new IllegalStateException(detail);
    }
}
