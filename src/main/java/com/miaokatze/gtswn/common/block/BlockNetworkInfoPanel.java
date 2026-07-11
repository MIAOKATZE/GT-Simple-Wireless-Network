package com.miaokatze.gtswn.common.block;

import java.util.ArrayList;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;

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
        int facing = getHorizontalFacingFromEntity(placer);
        world.setBlockMetadataWithNotify(x, y, z, facing, 2);

        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityNetworkInfoPanel) {
            TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
            if (placer instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) placer;
                panel.bindOwner(player.getUniqueID(), player.getCommandSenderName());
            }
            if (stack != null && stack.hasTagCompound()) {
                panel.readPlacementData(stack.getTagCompound());
            }
            panel.rebuildScreen();
        }
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityNetworkInfoPanel)) {
            return false;
        }
        TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
        ItemStack heldItem = player.getHeldItem();
        int currentTab = panel.getCurrentTab();

        // 仅当 AE 标签页激活且手持物品非空时，执行右键配置逻辑
        if (heldItem != null && (currentTab == 1 || currentTab == 2)) {
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

        // 其他情况（EU 标签页或空手）打开 GUI（原逻辑）
        world.markBlockForUpdate(x, y, z);
        player
            .openGui(GTSimpleWirelessNetwork.instance, GTSimpleWirelessNetwork.GUI_NETWORK_INFO_PANEL, world, x, y, z);
        return true;
    }

    @Override
    public ArrayList<ItemStack> getDrops(World world, int x, int y, int z, int metadata, int fortune) {
        ArrayList<ItemStack> drops = new ArrayList<>();
        ItemStack stack = new ItemStack(this, 1, 0);
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityNetworkInfoPanel) {
            NBTTagCompound tag = new NBTTagCompound();
            ((TileEntityNetworkInfoPanel) tile).writePlacementData(tag);
            stack.setTagCompound(tag);
        }
        drops.add(stack);
        return drops;
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, net.minecraft.block.Block block, int meta) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityNetworkInfoPanel) {
            ((TileEntityNetworkInfoPanel) tile).detachScreen();
        }
        super.breakBlock(world, x, y, z, block, meta);
        // v1.4.6：主屏破坏后通知附近主屏重建，避免旧 screen 状态残留（与 Extender.breakBlock 行为一致）
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
        return (tile instanceof TileEntityNetworkInfoPanel
            || tile instanceof com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanelExtender)
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
