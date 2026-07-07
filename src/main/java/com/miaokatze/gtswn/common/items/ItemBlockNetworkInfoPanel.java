package com.miaokatze.gtswn.common.items;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;

public class ItemBlockNetworkInfoPanel extends ItemBlock {

    public ItemBlockNetworkInfoPanel(Block block) {
        super(block);
        setMaxStackSize(1);
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        if (stack != null && stack.hasTagCompound()) {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag.hasKey("OwnerName")) {
                lines.add(EnumChatFormatting.AQUA + "Owner: " + EnumChatFormatting.WHITE + tag.getString("OwnerName"));
            }
            if (tag.hasKey("DatasetId")) {
                lines.add(EnumChatFormatting.GRAY + "History retained");
            }
        }
    }
}
