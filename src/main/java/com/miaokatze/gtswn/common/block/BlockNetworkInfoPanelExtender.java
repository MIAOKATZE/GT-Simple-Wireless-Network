package com.miaokatze.gtswn.common.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanelExtender;
import com.miaokatze.gtswn.register.CreativeTabManager;

public class BlockNetworkInfoPanelExtender extends BlockContainer {

    private IIcon screenIcon;
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
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        int facing = getHorizontalFacingFromEntity(placer);
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
