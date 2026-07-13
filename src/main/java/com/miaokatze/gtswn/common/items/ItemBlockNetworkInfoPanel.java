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
        // v1.5.15：允许堆叠到 64，修复无法常规堆叠的问题。
        // - 创造栏/合成产物（无 NBT）可正常堆叠
        // - 拓展屏破坏掉落物（无 NBT）可与合成/创造产物堆叠
        // - 主屏破坏掉落物（有 NBT：OwnerUUID+图表配置+采样历史）仅与 NBT 完全相同的掉落物堆叠（MC 原版机制）
        setMaxStackSize(64);
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
