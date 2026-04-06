package com.miaokatze.gtswn.register;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/**
 * 物品/方块容器接口 - 用于统一的物品管理
 */
public interface IItemContainer {

    IItemContainer set(Item aItem);

    IItemContainer set(ItemStack aStack);

    IItemContainer set(IMetaTileEntity aMetaTileEntity);

    Item getItem();

    Block getBlock();

    int getMeta();

    ItemStack get(long aAmount, Object... aReplacements);

    ItemStack get(int aAmount);

    boolean hasBeenSet();

    boolean isStackEqual(Object aStack);

    boolean isStackEqual(Object aStack, boolean aWildcard, boolean aIgnoreNBT);

    ItemStack getWithName(long aAmount, String aDisplayName, Object... aReplacements);

    IItemContainer registerOre(Object... aOreNames);
}
