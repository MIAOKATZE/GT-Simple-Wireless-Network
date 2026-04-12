package com.miaokatze.gtswn.common.api.enums;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.register.CreativeTabManager;
import com.miaokatze.gtswn.register.IItemContainer;

import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.util.GTLog;

/**
 * 模组物品统一索引枚举
 * 实现了 IItemContainer 接口，用于在代码中安全、统一地引用模组内的物品和方块。
 * 这种设计模式可以避免因游戏加载顺序导致的空指针问题，并提供便捷的物品堆栈操作方法。
 */
public enum GTSWNItemList implements IItemContainer {

    // 测试机器：EV, IV, LuV 等级
    Test_Machine_EV,
    Test_Machine_IV,
    Test_Machine_LuV,

    // 测试用多方块机器 (HV)
    Test_Multiblock_HV,

    // 测试物品
    TestCoin,
    // 电子测试硬币 (现在由 GTSWNMetaItem01 管理)
    TestCoinE,
    // 便携式无线网络监测终端
    Portable_Wireless_Network_Monitor,
    // 无线能量监视器
    Wireless_Energy_Monitor;

    // 存储对应的物品堆栈实例
    private ItemStack mStack;
    // 标记该条目是否已经被初始化赋值
    private boolean mHasNotBeenSet = true;
    // 警告标志，防止重复输出警告
    private boolean mWarned = false;

    /**
     * 通过 Item 对象设置当前枚举对应的物品
     * <p>
     * 该方法会创建一个数量为 1、元数据为 0 的标准物品堆栈，并标记该枚举项已初始化。
     *
     * @param aItem 要绑定的 Minecraft Item 对象
     * @return 当前枚举实例，支持链式调用
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
     * <p>
     * 直接引用给定的物品堆栈，通常用于需要保留特定 NBT 或元数据的场景。
     *
     * @param aStack 要绑定的物品堆栈
     * @return 当前枚举实例，支持链式调用
     */
    @Override
    public IItemContainer set(ItemStack aStack) {
        mHasNotBeenSet = false;
        mStack = gregtech.api.util.GTUtility.copyAmount(1, aStack);
        return this;
    }

    /**
     * 通过元机器实体 (MTE) 设置当前枚举对应的物品
     * <p>
     * 这是 GregTech 机器注册时最常用的方式。它会调用 MTE 的 `getStackForm` 方法获取带有正确显示名称和 Lore 的物品堆栈。
     *
     * @param aMetaTileEntity 要绑定的机器实体实例
     * @return 当前枚举实例，支持链式调用
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

    @Override
    public IItemContainer setAndRegister(Item item, String registerName, boolean shouldRegister) {
        if (!shouldRegister || item == null) return this;
        // 注册物品
        if (registerName == null) {
            registerName = item.getUnlocalizedName()
                .replace("item.", "");
        }
        GameRegistry.registerItem(item, registerName);
        // 设置物品索引
        set(item);
        // 添加到创造模式标签页
        CreativeTabManager.addItemToTab(get(1));
        return this;
    }

    @Override
    public void sanityCheck() {
        if (mHasNotBeenSet && !mWarned) {
            GTLog.err.println("Warning: Item '" + name() + "' has not been set!");
            mWarned = true;
        }
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
