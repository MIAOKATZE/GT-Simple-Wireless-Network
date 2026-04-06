package com.miaokatze.gtswn.common.api.enums;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.register.IItemContainer;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

public enum GTSWNItemList implements IItemContainer {

    Test_Machine_EV,
    Test_Machine_IV,
    Test_Machine_LuV;

    private ItemStack mStack;
    private boolean mHasNotBeenSet = true;

    @Override
    public IItemContainer set(Item aItem) {
        mHasNotBeenSet = false;
        if (aItem == null) return this;
        ItemStack aStack = new ItemStack(aItem, 1, 0);
        mStack = gregtech.api.util.GTUtility.copyAmount(1, aStack);
        return this;
    }

    @Override
    public IItemContainer set(ItemStack aStack) {
        mHasNotBeenSet = false;
        mStack = gregtech.api.util.GTUtility.copyAmount(1, aStack);
        return this;
    }

    @Override
    public IItemContainer set(IMetaTileEntity aMetaTileEntity) {
        mHasNotBeenSet = false;
        if (aMetaTileEntity != null) {
            mStack = aMetaTileEntity.getStackForm(1);
        }
        return this;
    }

    @Override
    public Item getItem() {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        if (gregtech.api.util.GTUtility.isStackInvalid(mStack)) return null;
        return mStack.getItem();
    }

    @Override
    public Block getBlock() {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        return gregtech.api.util.GTUtility.getBlockFromItem(getItem());
    }

    @Override
    public int getMeta() {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        return mStack.getItemDamage();
    }

    @Override
    public final boolean hasBeenSet() {
        return !mHasNotBeenSet;
    }

    @Override
    public boolean isStackEqual(Object aStack) {
        return isStackEqual(aStack, false, false);
    }

    @Override
    public boolean isStackEqual(Object aStack, boolean aWildcard, boolean aIgnoreNBT) {
        if (gregtech.api.util.GTUtility.isStackInvalid(aStack)) return false;
        return gregtech.api.util.GTUtility
            .areUnificationsEqual((ItemStack) aStack, aWildcard ? getWildcard(1) : get(1), aIgnoreNBT);
    }

    @Override
    public ItemStack get(long aAmount, Object... aReplacements) {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        if (gregtech.api.util.GTUtility.isStackInvalid(mStack))
            return gregtech.api.util.GTUtility.copyAmount(aAmount, aReplacements);
        return gregtech.api.util.GTUtility.copyAmount(aAmount, gregtech.api.util.GTUtility.updateItemStack(mStack));
    }

    @Override
    public ItemStack get(int aAmount) {
        return get((long) aAmount);
    }

    public ItemStack getWildcard(long aAmount, Object... aReplacements) {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        if (gregtech.api.util.GTUtility.isStackInvalid(mStack))
            return gregtech.api.util.GTUtility.copyAmount(aAmount, aReplacements);
        return gregtech.api.util.GTUtility.copyMetaData(
            gregtech.api.enums.GTValues.W,
            gregtech.api.util.GTUtility.copyAmount(aAmount, gregtech.api.util.GTUtility.updateItemStack(mStack)));
    }

    public ItemStack getUndamaged(long aAmount, Object... aReplacements) {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        if (gregtech.api.util.GTUtility.isStackInvalid(mStack))
            return gregtech.api.util.GTUtility.copyAmount(aAmount, aReplacements);
        return gregtech.api.util.GTUtility.copyMetaData(
            0,
            gregtech.api.util.GTUtility.copyAmount(aAmount, gregtech.api.util.GTUtility.updateItemStack(mStack)));
    }

    public ItemStack getAlmostBroken(long aAmount, Object... aReplacements) {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        if (gregtech.api.util.GTUtility.isStackInvalid(mStack))
            return gregtech.api.util.GTUtility.copyAmount(aAmount, aReplacements);
        return gregtech.api.util.GTUtility.copyMetaData(
            mStack.getMaxDamage() - 1,
            gregtech.api.util.GTUtility.copyAmount(aAmount, gregtech.api.util.GTUtility.updateItemStack(mStack)));
    }

    public ItemStack getWithName(long aAmount, String aDisplayName, Object... aReplacements) {
        ItemStack rStack = get(1, aReplacements);
        if (gregtech.api.util.GTUtility.isStackInvalid(rStack)) return null;
        rStack.setStackDisplayName(aDisplayName);
        return gregtech.api.util.GTUtility.copyAmount(aAmount, rStack);
    }

    public ItemStack getWithCharge(long aAmount, int aEnergy, Object... aReplacements) {
        ItemStack rStack = get(1, aReplacements);
        return null;
    }

    public ItemStack getWithDamage(long aAmount, long aMetaValue, Object... aReplacements) {
        if (mHasNotBeenSet)
            throw new IllegalAccessError("The Enum '" + name() + "' has not been set to an Item at this time!");
        if (gregtech.api.util.GTUtility.isStackInvalid(mStack))
            return gregtech.api.util.GTUtility.copyAmount(aAmount, aReplacements);
        return gregtech.api.util.GTUtility.copyMetaData(
            (int) aMetaValue,
            gregtech.api.util.GTUtility.copyAmount(aAmount, gregtech.api.util.GTUtility.updateItemStack(mStack)));
    }

    @Override
    public IItemContainer registerOre(Object... aOreNames) {
        return this;
    }

    public IItemContainer registerWildcardAsOre(Object... aOreNames) {
        return this;
    }
}
