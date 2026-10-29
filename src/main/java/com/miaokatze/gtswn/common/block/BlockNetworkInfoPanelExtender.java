package com.miaokatze.gtswn.common.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanelExtender;
import com.miaokatze.gtswn.common.tile.screen.ScreenStructure;
import com.miaokatze.gtswn.common.tile.screen.ScreenStructureUtil;
import com.miaokatze.gtswn.register.CreativeTabManager;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

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
    @SideOnly(Side.CLIENT)
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
        // E1（O2-01a）：五工具收敛至 ScreenStructureUtil，本类私有副本已删
        int facing = ScreenStructureUtil.normalizeFacing(meta);
        if (side == facing) {
            return screenIcon;
        }
        if (side == ScreenStructureUtil.opposite(facing)) {
            return backIcon;
        }
        return sideIcon;
    }

    @Override
    public IIcon getIcon(IBlockAccess world, int x, int y, int z, int side) {
        int facing = ScreenStructureUtil.normalizeFacing(world.getBlockMetadata(x, y, z));
        if (side == facing) {
            return connectedScreenIcons[ScreenStructureUtil.getEdgeMask(world, x, y, z, facing)];
        }
        return getIcon(side, facing);
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        int facing = findNeighborFacing(world, x, y, z, ScreenStructureUtil.getHorizontalFacingFromEntity(placer));
        world.setBlockMetadataWithNotify(x, y, z, facing, 2);
        if (!world.isRemote) {
            ScreenStructure.rebuildConnected(world, x, y, z, facing);
        }
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, net.minecraft.block.Block block, int meta) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityNetworkInfoPanelExtender) {
            TileEntityNetworkInfoPanelExtender extender = (TileEntityNetworkInfoPanelExtender) tile;
            extender.detachFromCore();
        }
        super.breakBlock(world, x, y, z, block, meta);
        if (!world.isRemote) {
            ScreenStructure.rebuildConnected(world, x, y, z, meta);
        }
    }

    /**
     * 拓展屏独占的放置朝向继承（E1 首轮不迁 ScreenStructureUtil）：优先沿用相邻屏幕部件朝向，
     * 无邻居时回落放置者朝向。
     */
    private static int findNeighborFacing(World world, int x, int y, int z, int fallback) {
        int[][] offsets = new int[][] { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 },
            { 0, 0, -1 } };
        for (int[] offset : offsets) {
            if (!world.blockExists(x + offset[0], y + offset[1], z + offset[2])) continue;
            TileEntity tile = world.getTileEntity(x + offset[0], y + offset[1], z + offset[2]);
            if (tile instanceof TileEntityNetworkInfoPanel || tile instanceof TileEntityNetworkInfoPanelExtender) {
                int facing = ScreenStructureUtil.normalizeFacing(tile.getBlockMetadata());
                if (facing >= 2 && facing <= 5) {
                    return facing;
                }
            }
        }
        return fallback;
    }
}
