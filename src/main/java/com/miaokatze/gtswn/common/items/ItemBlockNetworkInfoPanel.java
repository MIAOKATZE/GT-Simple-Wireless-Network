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
                // 多屏共享：同一玩家的所有信息屏共享同一份数据集（key=ownerUUID）
                // 掉落物 tooltip 显示归属玩家，提示数据将共享
                String name = tag.getString("OwnerName");
                if (name != null && !name.isEmpty()) {
                    lines.add(EnumChatFormatting.AQUA + "Owner: " + EnumChatFormatting.WHITE + name);
                    lines.add(EnumChatFormatting.GRAY + "Shared network: " + name);
                }
            }
        }
    }
}
