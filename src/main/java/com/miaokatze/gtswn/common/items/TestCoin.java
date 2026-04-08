package com.miaokatze.gtswn.common.items;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

/**
 * 测试硬币物品
 * <p>
 * 这是一个基础的 Minecraft 物品，用于验证模组的物品注册流程、创造模式标签页集成以及配方系统。
 * 它不包含任何特殊的游戏逻辑（如右键功能或耐久度）。
 */
public class TestCoin extends Item {

    /**
     * 构造函数：初始化测试硬币的基础属性
     */
    public TestCoin() {
        super();
        // 设置未本地化名称 (Unlocalized Name)，用于关联语言文件
        setUnlocalizedName("TestCoin_GTswn");
        // 设置材质路径 (Texture Name)，指向 assets/gtswn/textures/items/TestCoin_GTswn.png
        setTextureName("gtswn:TestCoin_GTswn");
        // 设置创造模式标签页，使其能在游戏中被玩家获取
        setCreativeTab(CreativeTabs.tabMisc);
        // 设置最大堆叠数量为 64
        setMaxStackSize(64);
    }
}
