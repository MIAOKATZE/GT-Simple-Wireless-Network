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
                // 来自掉落物或复制：恢复放置数据（OwnerUUID、图表配置等）
                // 旧版 PanelUUID 已不再使用，读取时会被忽略
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
        // v1.5.15：修复方向性各异性——左右边缘需按朝向镜像。
        // bit2 (mask4)=左侧无邻居，bit3 (mask8)=右侧无邻居。
        // 观察者面对屏幕时左手边方向：
        // facing=3 (S) → X-，facing=2 (N) → X+（镜像）
        // facing=4 (W) → Z-，facing=5 (E) → Z+（镜像）
        if (facing == 2) {
            // N: 180°镜像，左右互换
            if (!isCompatibleScreenPart(world, x + 1, y, z, facing)) {
                mask |= 4;
            }
            if (!isCompatibleScreenPart(world, x - 1, y, z, facing)) {
                mask |= 8;
            }
        } else if (facing == 3) {
            // S: 不变
            if (!isCompatibleScreenPart(world, x - 1, y, z, facing)) {
                mask |= 4;
            }
            if (!isCompatibleScreenPart(world, x + 1, y, z, facing)) {
                mask |= 8;
            }
        } else if (facing == 4) {
            // W: 不变
            if (!isCompatibleScreenPart(world, x, y, z - 1, facing)) {
                mask |= 4;
            }
            if (!isCompatibleScreenPart(world, x, y, z + 1, facing)) {
                mask |= 8;
            }
        } else {
            // E (facing==5): +90°镜像，左右互换
            if (!isCompatibleScreenPart(world, x, y, z + 1, facing)) {
                mask |= 4;
            }
            if (!isCompatibleScreenPart(world, x, y, z - 1, facing)) {
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
