package com.miaokatze.gtswn.common.items;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

public class ItemBlockNetworkInfoPanel extends ItemBlock {

    public ItemBlockNetworkInfoPanel(Block block) {
        super(block);
        // v1.5.15：允许堆叠到 64，修复无法常规堆叠的问题。
        // - 创造栏/合成产物（无 NBT）可正常堆叠
        // - 拓展屏破坏掉落物（无 NBT）可与合成/创造产物堆叠
        // - 主屏破坏掉落物（无 NBT，数据保留在 WorldSavedData 中）可与合成/创造产物堆叠
        setMaxStackSize(64);
    }

    /**
     * 物品 tooltip 显示。
     *
     * <p>
     * 本版本简化：物品破坏后不再保留 NBT（数据归属保留在全局 NetworkInfoDataSet 中），
     * 因此无需显示 Owner 信息。
     *
     * @param stack    物品堆
     * @param player   查看 tooltip 的玩家
     * @param lines    tooltip 行列表
     * @param advanced 是否按住 F3+H 显示高级 tooltip
     */
    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        // 物品无 NBT，无 tooltip 显示
        // （原 OwnerName tooltip 已移除，因为破坏后不保留 NBT 数据）
    }
}
