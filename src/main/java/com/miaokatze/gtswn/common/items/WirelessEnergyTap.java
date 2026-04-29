package com.miaokatze.gtswn.common.items;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

/**
 * 无线能量分接器
 * <p>
 * 一个便携式的无线电网分接设备，允许玩家将任意能量容器连接到GT无线电网。
 * 功能特性：
 * - 右击能量容器：赋予或取消无线连接状态
 * - Shift + 右击能量容器：切换输入/输出模式
 * - 自动读取目标能量容器的电压等级
 */
public class WirelessEnergyTap extends Item {

    /**
     * 构造函数：初始化无线能量分接器的基础属性
     */
    public WirelessEnergyTap() {
        super();
        // 设置未本地化名称 (Unlocalized Name)
        setUnlocalizedName("wireless_energy_tap");
        // 设置材质路径：默认使用输入模式材质
        setTextureName("gtswn:wireless_energy_tap_input");
        // 设置创造模式标签页
        setCreativeTab(CreativeTabs.tabMisc);
        // 设置最大堆叠数量为 1
        setMaxStackSize(1);
        // 允许显示 tooltip
        setHasSubtypes(true);
    }

    /**
     * 处理右击方块事件
     */
    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        // 目前阶段0：什么都不做，空壳物品
        return false;
    }

    /**
     * 处理右击空气事件
     */
    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        // 目前阶段0：什么都不做，空壳物品
        return stack;
    }

    /**
     * 添加物品的额外信息（Tooltip）
     */
    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List<String> list, boolean f3_h) {
        // 目前阶段0：简单的描述
        list.add(StatCollector.translateToLocal("item.wireless_energy_tap.desc"));
    }
}
