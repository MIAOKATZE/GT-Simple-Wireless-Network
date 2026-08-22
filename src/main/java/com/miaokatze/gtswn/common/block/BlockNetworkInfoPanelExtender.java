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
            // v1.5.15：放置时仅需小范围扫描（±2 足以覆盖邻居 Panel）
            TileEntityNetworkInfoPanel.rebuildNearbyScreens(world, x, y, z, 2);
        }
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, net.minecraft.block.Block block, int meta) {
        // v1.5.15：捕获 core 坐标（detachFromCore 不清 coreX/Y/Z，但 super.breakBlock 后 TE 移除）
        boolean wasPartOfScreen = false;
        int coreX = 0, coreY = 0, coreZ = 0;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityNetworkInfoPanelExtender) {
            TileEntityNetworkInfoPanelExtender extender = (TileEntityNetworkInfoPanelExtender) tile;
            wasPartOfScreen = extender.isPartOfScreen();
            if (wasPartOfScreen) {
                coreX = extender.getCoreX();
                coreY = extender.getCoreY();
                coreZ = extender.getCoreZ();
            }
            extender.detachFromCore();
        }
        super.breakBlock(world, x, y, z, block, meta);
        if (!world.isRemote) {
            if (wasPartOfScreen) {
                // v1.5.15：定向重建——直接查找 core Panel
                TileEntity coreTile = world.getTileEntity(coreX, coreY, coreZ);
                if (coreTile instanceof TileEntityNetworkInfoPanel) {
                    ((TileEntityNetworkInfoPanel) coreTile).rebuildScreen();
                } else {
                    // core 已不存在，回退到 ±2 范围扫描（5³=125 块，远小于 33³）
                    TileEntityNetworkInfoPanel.rebuildNearbyScreens(world, x, y, z, 2);
                }
            }
            // wasPartOfScreen==false 时无需重建（孤立 Extender）
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
