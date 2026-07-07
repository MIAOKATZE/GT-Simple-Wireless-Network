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
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.register.CreativeTabManager;

public class BlockNetworkInfoPanel extends BlockContainer {

    private IIcon screenIcon;
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
