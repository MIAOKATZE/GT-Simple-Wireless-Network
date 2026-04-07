package com.miaokatze.gtswn.common.api.enums;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.register.IItemContainer;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/**
 * 模组物品统一索引枚举
 * 实现了 IItemContainer 接口，用于在代码中安全、统一地引用模组内的物品和方块。
 * 这种设计模式可以避免因游戏加载顺序导致的空指针问题，并提供便捷的物品堆栈操作方法。
 */
public enum GTSWNItemList implements IItemContainer {

    // 测试机器：EV, IV, LuV 等级
    Test_Machine_EV,
    Test_Machine_IV,
    Test_Machine_LuV;

    // 存储对应的物品堆栈实例
    private ItemStack mStack;
    // 标记该条目是否已经被初始化赋值
    private boolean mHasNotBeenSet = true;

    /**
     * 通过 Item 对象设置当前枚举对应的物品
     */
    @Override
    public IItemContainer set(Item aItem) {
        mHasNotBeenSet = false;
        if (aItem == null) return this;
        ItemStack aStack = new ItemStack(aItem, 1, 0);
        mStack = gregtech.api.util.GTUtility.copyAmount(1, aStack);
        return this;
    }

    /**
     * 通过 ItemStack 对象设置当前枚举对应的物品
     */
    @Override
    public IItemContainer set(ItemStack aStack) {
        mHasNotBeenSet = false;
        mStack = gregtech.api.util.GTUtility.copyAmount(1, aStack);
        return this;
    }

    /**
     * 通过元机器实体 (MTE) 设置当前枚举对应的物品
     * 这是 GregTech 机器注册时最常用的方式
     */
    @Override
    public IItemContainer set(IMetaTileEntity aMetaTileEntity) {
        mHasNotBeenSet = false;
        if (aMetaTileEntity != null) {
            mStack = aMetaTileEntity.getStackForm(1);
        }
        return this;
    }

    /**
     * 获取底层的 Item 对象
     * 
     * @throws IllegalAccessError 如果该枚举项尚未被初始化
     */
    @Override
    public Item getItem() {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        if (gregtech.api.util.GTUtility.isStackInvalid(mStack)) return null;
        return mStack.getItem();
    }

    /**
     * 获取底层的 Block 对象
     */
    @Override
    public Block getBlock() {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        return gregtech.api.util.GTUtility.getBlockFromItem(getItem());
    }

    /**
     * 获取物品的元数据 (Damage Value)
     */
    @Override
    public int getMeta() {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        return mStack.getItemDamage();
    }

    /**
     * 检查该枚举项是否已经完成初始化
     */
    @Override
    public final boolean hasBeenSet() {
        return !mHasNotBeenSet;
    }

    /**
     * 判断给定的物品堆栈是否与此枚举项代表的物品相等
     */
    @Override
    public boolean isStackEqual(Object aStack) {
        return isStackEqual(aStack, false, false);
    }

    /**
     * 判断给定的物品堆栈是否与此枚举项代表的物品相等（支持通配符和忽略 NBT）
     */
    @Override
    public boolean isStackEqual(Object aStack, boolean aWildcard, boolean aIgnoreNBT) {
        if (gregtech.api.util.GTUtility.isStackInvalid(aStack)) return false;
        return gregtech.api.util.GTUtility
            .areUnificationsEqual((ItemStack) aStack, aWildcard ? getWildcard(1) : get(1), aIgnoreNBT);
    }

    /**
     * 获取指定数量的物品堆栈
     */
    @Override
    public ItemStack get(long aAmount, Object... aReplacements) {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        if (gregtech.api.util.GTUtility.isStackInvalid(mStack))
            return gregtech.api.util.GTUtility.copyAmount(aAmount, aReplacements);
        return gregtech.api.util.GTUtility.copyAmount(aAmount, gregtech.api.util.GTUtility.updateItemStack(mStack));
    }

    /**
     * 获取指定数量的物品堆栈 (int 重载)
     */
    @Override
    public ItemStack get(int aAmount) {
        return get((long) aAmount);
    }

    /**
     * 获取通配符元数据的物品堆栈（常用于配方输入，匹配任意耐久度）
     */
    public ItemStack getWildcard(long aAmount, Object... aReplacements) {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        if (gregtech.api.util.GTUtility.isStackInvalid(mStack))
            return gregtech.api.util.GTUtility.copyAmount(aAmount, aReplacements);
        return gregtech.api.util.GTUtility.copyMetaData(
            gregtech.api.enums.GTValues.W,
            gregtech.api.util.GTUtility.copyAmount(aAmount, gregtech.api.util.GTUtility.updateItemStack(mStack)));
    }

    /**
     * 获取耐久度为满的物品堆栈
     */
    public ItemStack getUndamaged(long aAmount, Object... aReplacements) {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        if (gregtech.api.util.GTUtility.isStackInvalid(mStack))
            return gregtech.api.util.GTUtility.copyAmount(aAmount, aReplacements);
        return gregtech.api.util.GTUtility.copyMetaData(
            0,
            gregtech.api.util.GTUtility.copyAmount(aAmount, gregtech.api.util.GTUtility.updateItemStack(mStack)));
    }

    /**
     * 获取耐久度即将耗尽的物品堆栈
     */
    public ItemStack getAlmostBroken(long aAmount, Object... aReplacements) {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        if (gregtech.api.util.GTUtility.isStackInvalid(mStack))
            return gregtech.api.util.GTUtility.copyAmount(aAmount, aReplacements);
        return gregtech.api.util.GTUtility.copyMetaData(
            mStack.getMaxDamage() - 1,
            gregtech.api.util.GTUtility.copyAmount(aAmount, gregtech.api.util.GTUtility.updateItemStack(mStack)));
    }

    /**
     * 获取带有自定义显示名称的物品堆栈
     */
    public ItemStack getWithName(long aAmount, String aDisplayName, Object... aReplacements) {
        ItemStack rStack = get(1, aReplacements);
        if (gregtech.api.util.GTUtility.isStackInvalid(rStack)) return null;
        rStack.setStackDisplayName(aDisplayName);
        return gregtech.api.util.GTUtility.copyAmount(aAmount, rStack);
    }

    /**
     * 获取充能后的物品堆栈（目前未实现逻辑）
     */
    public ItemStack getWithCharge(long aAmount, int aEnergy, Object... aReplacements) {
        ItemStack rStack = get(1, aReplacements);
        return null;
    }

    /**
     * 获取指定元数据值的物品堆栈
     */
    public ItemStack getWithDamage(long aAmount, long aMetaValue, Object... aReplacements) {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        if (gregtech.api.util.GTUtility.isStackInvalid(mStack))
            return gregtech.api.util.GTUtility.copyAmount(aAmount, aReplacements);
        return gregtech.api.util.GTUtility.copyMetaData(
            (int) aMetaValue,
            gregtech.api.util.GTUtility.copyAmount(aAmount, gregtech.api.util.GTUtility.updateItemStack(mStack)));
    }

    /**
     * 将该物品注册到矿物词典 (OreDictionary)
     */
    @Override
    public IItemContainer registerOre(Object... aOreNames) {
        return this;
    }

    /**
     * 将该物品的通配符版本注册到矿物词典
     */
    public IItemContainer registerWildcardAsOre(Object... aOreNames) {
        return this;
    }
}
