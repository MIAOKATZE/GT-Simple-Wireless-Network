package com.miaokatze.gtswn.common.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanelExtender;
import com.miaokatze.gtswn.register.CreativeTabManager;

public class BlockNetworkInfoPanelExtender extends BlockContainer {

    private IIcon screenIcon;
    private final IIcon[] connectedScreenIcons = new IIcon[16];
    private IIcon backIcon;
    private IIcon sideIcon;

    public BlockNetworkInfoPanelExtender() {
        super(Material.iron);
        setBlockName("NetworkInfoPanelExtender_GTswn");
        setBlockTextureName("gtswn:network_info_panel/extenderScreen");
        setHardness(2.0F);
        setResistance(10.0F);
        setCreativeTab(CreativeTabManager.CREATIVE_TAB);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityNetworkInfoPanelExtender();
    }

    @Override
    public void registerBlockIcons(IIconRegister register) {
        screenIcon = register.registerIcon("gtswn:network_info_panel/extenderScreen");
        for (int i = 0; i < connectedScreenIcons.length; i++) {
            connectedScreenIcons[i] = register.registerIcon("gtswn:network_info_panel/screen_connected_" + i);
        }
        backIcon = register.registerIcon("gtswn:network_info_panel/extenderBack");
        sideIcon = register.registerIcon("gtswn:network_info_panel/extenderSide");
    }

    @Override
    public IIcon getIcon(int side, int meta) {
        int facing = normalizeFacing(meta);
        if (side == facing) {
            return screenIcon;
        }
        if (side == opposite(facing)) {
            return backIcon;
        }
        return sideIcon;
    }

    @Override
    public IIcon getIcon(IBlockAccess world, int x, int y, int z, int side) {
        int facing = normalizeFacing(world.getBlockMetadata(x, y, z));
        if (side == facing) {
            return connectedScreenIcons[getEdgeMask(world, x, y, z, facing)];
        }
        return getIcon(side, facing);
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        int facing = findNeighborFacing(world, x, y, z, getHorizontalFacingFromEntity(placer));
        world.setBlockMetadataWithNotify(x, y, z, facing, 2);
        if (!world.isRemote) {
            TileEntityNetworkInfoPanel.rebuildNearbyScreens(world, x, y, z);
        }
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, net.minecraft.block.Block block, int meta) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityNetworkInfoPanelExtender) {
            ((TileEntityNetworkInfoPanelExtender) tile).detachFromCore();
        }
        super.breakBlock(world, x, y, z, block, meta);
        if (!world.isRemote) {
            TileEntityNetworkInfoPanel.rebuildNearbyScreens(world, x, y, z);
        }
    }

    private static int getHorizontalFacingFromEntity(EntityLivingBase entity) {
        int direction = MathHelper.floor_double(entity.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        switch (direction) {
            case 0:
                return 2;
            case 1:
                return 5;
            case 2:
                return 3;
            case 3:
                return 4;
            default:
                return 3;
        }
    }

    private static int normalizeFacing(int meta) {
        if (meta >= 2 && meta <= 5) {
            return meta;
        }
        return 3;
    }

    private static int findNeighborFacing(World world, int x, int y, int z, int fallback) {
        int[][] offsets = new int[][] { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 },
            { 0, 0, -1 } };
        for (int[] offset : offsets) {
            TileEntity tile = world.getTileEntity(x + offset[0], y + offset[1], z + offset[2]);
            if (tile instanceof TileEntityNetworkInfoPanel || tile instanceof TileEntityNetworkInfoPanelExtender) {
                int facing = normalizeFacing(tile.getBlockMetadata());
                if (facing >= 2 && facing <= 5) {
                    return facing;
                }
            }
        }
        return fallback;
    }

    private static int getEdgeMask(IBlockAccess world, int x, int y, int z, int facing) {
        int mask = 0;
        if (!isCompatibleScreenPart(world, x, y + 1, z, facing)) {
            mask |= 1;
        }
        if (!isCompatibleScreenPart(world, x, y - 1, z, facing)) {
            mask |= 2;
        }
        if (facing == 2 || facing == 3) {
            if (!isCompatibleScreenPart(world, x - 1, y, z, facing)) {
                mask |= 4;
            }
            if (!isCompatibleScreenPart(world, x + 1, y, z, facing)) {
                mask |= 8;
            }
        } else {
            if (!isCompatibleScreenPart(world, x, y, z - 1, facing)) {
                mask |= 4;
            }
            if (!isCompatibleScreenPart(world, x, y, z + 1, facing)) {
                mask |= 8;
            }
        }
        return mask;
    }

    private static boolean isCompatibleScreenPart(IBlockAccess world, int x, int y, int z, int facing) {
        TileEntity tile = world.getTileEntity(x, y, z);
        return (tile instanceof TileEntityNetworkInfoPanel || tile instanceof TileEntityNetworkInfoPanelExtender)
            && normalizeFacing(tile.getBlockMetadata()) == facing;
    }

    private static int opposite(int side) {
        switch (side) {
            case 2:
                return 3;
            case 3:
                return 2;
            case 4:
                return 5;
            case 5:
                return 4;
            case 0:
                return 1;
            case 1:
                return 0;
            default:
                return side;
        }
    }
}
