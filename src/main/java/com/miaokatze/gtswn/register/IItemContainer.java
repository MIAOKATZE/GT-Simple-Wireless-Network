package com.miaokatze.gtswn.register;

import java.util.function.Supplier;

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

    /**
     * 设置并注册物品（基础版本，使用类名作为注册名）
     */
    default IItemContainer setAndRegister(Supplier<Item> itemSupplier) {
        return setAndRegister(itemSupplier.get(), null, true);
    }

    /**
     * 设置并注册物品（带条件注册）
     */
    default IItemContainer setAndRegister(Supplier<Item> itemSupplier, boolean shouldRegister) {
        return setAndRegister(itemSupplier.get(), null, shouldRegister);
    }

    /**
     * 设置并注册物品（完整版本）
     */
    default IItemContainer setAndRegister(Item item, String registerName, boolean shouldRegister) {
        return this;
    }

    /**
     * 安全检查
     */
    default void sanityCheck() {}
}
