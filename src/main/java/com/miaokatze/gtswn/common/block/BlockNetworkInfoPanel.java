package com.miaokatze.gtswn.common.block;

import java.util.ArrayList;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.panel.AEMonitorDataStore;
import com.miaokatze.gtswn.common.panel.NetworkScreen;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.register.CreativeTabManager;

public class BlockNetworkInfoPanel extends BlockContainer {

    private IIcon screenIcon;
    private final IIcon[] connectedScreenIcons = new IIcon[16];
    private IIcon backIcon;
    private IIcon sideIcon;

    public BlockNetworkInfoPanel() {
        super(Material.iron);
        setBlockName("NetworkInfoPanel_GTswn");
        setBlockTextureName("gtswn:network_info_panel/screen");
        setHardness(2.0F);
        setResistance(10.0F);
        setCreativeTab(CreativeTabManager.CREATIVE_TAB);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityNetworkInfoPanel();
    }

    @Override
    public void registerBlockIcons(IIconRegister register) {
        screenIcon = register.registerIcon("gtswn:network_info_panel/screen");
        for (int i = 0; i < connectedScreenIcons.length; i++) {
            connectedScreenIcons[i] = register.registerIcon("gtswn:network_info_panel/screen_connected_" + i);
        }
        backIcon = register.registerIcon("gtswn:network_info_panel/panelBack");
        sideIcon = register.registerIcon("gtswn:network_info_panel/panelSide");
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
        int facing = ScreenStructureUtil.getHorizontalFacingFromEntity(placer);
        world.setBlockMetadataWithNotify(x, y, z, facing, 2);

        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityNetworkInfoPanel) {
            TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
            // 绑定放置者为 owner（无论物品是否有 NBT，都以放置者为准）
            if (placer instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) placer;
                panel.bindOwner(player.getUniqueID(), player.getCommandSenderName());
            }
            // 不再调用 readPlacementData（物品无 NBT，且数据归属应以放置者为准）
            panel.rebuildScreen();
        }
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityNetworkInfoPanel)) {
            return false;
        }
        TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
        ItemStack heldItem = player.getHeldItem();
        int currentTab = panel.getCurrentTab();

        // 仅当 AE 标签页激活且手持物品非空时，执行右键配置逻辑（仅服务端；客户端放行等待服务端处理）
        if (heldItem != null && (currentTab == 1 || currentTab == 2)) {
            if (world.isRemote) {
                return true;
            }
            // 尝试提取流体（含流体容器 → 流体通道；空容器/普通物品 → 物品通道）
            FluidStack fluid = FluidContainerRegistry.getFluidForFilledItem(heldItem);

            if (currentTab == 1) {
                // AE 走势图标签页
                if (fluid != null) {
                    boolean added = panel.setChartFluid(fluid);
                    player.addChatComponentMessage(
                        new ChatComponentTranslation(
                            added ? "gtswn.network_info.chat.bind_fluid" : "gtswn.network_info.chat.clear",
                            fluid.getLocalizedName()));
                } else {
                    boolean added = panel.setChartItem(heldItem);
                    player.addChatComponentMessage(
                        new ChatComponentTranslation(
                            added ? "gtswn.network_info.chat.bind_item" : "gtswn.network_info.chat.clear",
                            heldItem.getDisplayName()));
                }
            } else {
                // AE 实时监控标签页
                if (fluid != null) {
                    boolean added = panel.toggleFluidMonitor(fluid);
                    player.addChatComponentMessage(
                        new ChatComponentTranslation(
                            added ? "gtswn.network_info.chat.add_fluid" : "gtswn.network_info.chat.remove_fluid",
                            fluid.getLocalizedName()));
                } else {
                    boolean added = panel.toggleItemMonitor(heldItem);
                    player.addChatComponentMessage(
                        new ChatComponentTranslation(
                            added ? "gtswn.network_info.chat.add_item" : "gtswn.network_info.chat.remove_item",
                            heldItem.getDisplayName()));
                }
            }
            world.markBlockForUpdate(x, y, z);
            return true;
        }

        // 其他情况（EU 标签页或空手）：客户端本地打开 GUI（v1.6.26：不再走服务端 openGui——FML OpenGuiHandler
        // 会把服务端 windowId 无条件盖写进客户端 openContainer，纯 GuiScreen 下即背包容器，导致会话级 windowId
        // 污染；改纯客户端本地打开彻底绕开该路径）
        if (world.isRemote) {
            GTSimpleWirelessNetwork.proxy.openNetworkInfoPanelGui(panel);
            return true;
        }
        world.markBlockForUpdate(x, y, z);
        return true;
    }

    @Override
    public boolean removedByPlayer(World world, EntityPlayer player, int x, int y, int z, boolean willHarvest) {
        // v1.5.15：延迟方块删除到 getDrops 之后，确保 getDrops 能访问 TileEntity 写入 NBT
        // Forge 1.7.10 默认流程：removedByPlayer → setBlockToAir → removeTileEntity → getDrops（TE 已 null）
        // 修复后：removedByPlayer(返回true不删) → getDrops(TE存活) → harvestBlock → setBlockToAir
        return willHarvest || super.removedByPlayer(world, player, x, y, z, false);
    }

    @Override
    public void harvestBlock(World world, EntityPlayer player, int x, int y, int z, int meta) {
        // v1.5.15：配合 removedByPlayer，在 getDrops 完成后才真正删除方块
        super.harvestBlock(world, player, x, y, z, meta);
        world.setBlockToAir(x, y, z);
    }

    @Override
    public ArrayList<ItemStack> getDrops(World world, int x, int y, int z, int metadata, int fortune) {
        ArrayList<ItemStack> drops = new ArrayList<>();
        // 破坏后掉落物无 NBT（数据保留在 WorldSavedData 中，方块仅作为"调用器"）
        drops.add(new ItemStack(this, 1, metadata));
        return drops;
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, net.minecraft.block.Block block, int meta) {
        // v1.5.15：捕获 screen 边界（super.breakBlock 后 TE 被移除，无法再读）
        int minX = x, minY = y, minZ = z, maxX = x, maxY = y, maxZ = z;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityNetworkInfoPanel) {
            TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
            panel.detachScreen();
            // 服务端清理该坐标在 AEMonitorDataStore 中的数据，防止破坏后残留 AE 监控数据
            if (!world.isRemote) {
                String dataKey = panel.getAEMonitorDataKey();
                if (dataKey != null) {
                    AEMonitorDataStore.get(world)
                        .remove(dataKey);
                }
                // v1.5.15：捕获 screen 边界用于后续定向重建
                NetworkScreen screen = panel.getScreen();
                if (screen != null) {
                    minX = screen.minX;
                    minY = screen.minY;
                    minZ = screen.minZ;
                    maxX = screen.maxX;
                    maxY = screen.maxY;
                    maxZ = screen.maxZ;
                }
            }
        }
        super.breakBlock(world, x, y, z, block, meta);
        if (!world.isRemote) {
            // v1.5.15：扫描 screen 边界 ±1（而非 ±16），重建受影响的 Panel
            // 性能提升：从 35937 次 getTileEntity 降到 (w+2)*(h+2)*(d+2) 次
            for (int bx = minX - 1; bx <= maxX + 1; bx++) {
                for (int by = minY - 1; by <= maxY + 1; by++) {
                    for (int bz = minZ - 1; bz <= maxZ + 1; bz++) {
                        if (bx == x && by == y && bz == z) {
                            continue; // 跳过自身
                        }
                        TileEntity t = world.getTileEntity(bx, by, bz);
                        if (t instanceof TileEntityNetworkInfoPanel) {
                            ((TileEntityNetworkInfoPanel) t).rebuildScreen();
                        }
                    }
                }
            }
        }
    }
}
