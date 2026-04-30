package com.miaokatze.gtswn.recipe;

import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Portable_Wireless_Network_Monitor;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Wireless_Energy_Monitor;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Wireless_Energy_Tap;

import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.util.GTOreDictUnificator;

/**
 * GTSWN 模组工作台合成配方注册器
 * <p>
 * 负责注册所有使用工作台（Crafting Table）的合成配方
 */
public class CraftingRecipes {

    /**
     * 初始化所有工作台合成配方
     * <p>
     * 建议在 postInit 阶段调用
     */
    public static void init() {
        GTSimpleWirelessNetwork.LOG.info("开始注册工作台合成配方...");
        registerAllRecipes();
        GTSimpleWirelessNetwork.LOG.info("工作台合成配方注册完成。");
    }

    /**
     * 注册所有配方
     */
    private static void registerAllRecipes() {
        // 便携无线监测终端
        addPortableMonitorRecipe();
        // 无线能量监视器（工作台版）
        addWirelessEnergyMonitorRecipe();
        // 无线网络链路终端
        addWirelessEnergyTapRecipe();
    }

    /**
     * 添加便携无线监测终端的合成配方
     * <p>
     * 合成表：
     * LV接收器 | 钢外壳 | LV接收器
     * LV接收器 | 电脑屏幕覆盖板 | LV接收器
     * 钢螺丝 | 钢外壳 | 钢螺丝
     */
    private static void addPortableMonitorRecipe() {
        // 获取原材料
        ItemStack lvReceiver = ItemList.Sensor_LV.get(1); // LV接收器
        ItemStack steelPlate = GTOreDictUnificator.get(OrePrefixes.plate, Materials.Steel, 1); // 钢外壳
        ItemStack steelScrew = GTOreDictUnificator.get(OrePrefixes.screw, Materials.Steel, 1); // 钢螺丝
        ItemStack screen = ItemList.Cover_Screen.get(1); // 电脑屏幕覆盖板（gregtech:gt.metaitem.01:32740）

        // 使用 Forge 的 ShapedOreRecipe 添加合成配方
        net.minecraftforge.oredict.ShapedOreRecipe recipe = new net.minecraftforge.oredict.ShapedOreRecipe(
            Portable_Wireless_Network_Monitor.get(1),
            "ABC",
            "ADC",
            "EBE",
            'A',
            lvReceiver,
            'B',
            steelPlate,
            'C',
            lvReceiver,
            'D',
            screen,
            'E',
            steelScrew);

        CraftingManager.getInstance()
            .getRecipeList()
            .add(recipe);

        GTSimpleWirelessNetwork.LOG.info("已添加便携无线监测终端合成配方");
    }

    /**
     * 添加无线网络链路终端的合成配方
     * <p>
     * 合成表：
     * LV发射器 | 钢外壳 | LV发射器
     * LV发射器 | 电脑屏幕覆盖板 | LV发射器
     * 钢螺丝 | 钢外壳 | 钢螺丝
     */
    private static void addWirelessEnergyTapRecipe() {
        // 获取原材料
        ItemStack lvEmitter = ItemList.Emitter_LV.get(1); // LV发射器
        ItemStack steelPlate = GTOreDictUnificator.get(OrePrefixes.plate, Materials.Steel, 1); // 钢外壳
        ItemStack steelScrew = GTOreDictUnificator.get(OrePrefixes.screw, Materials.Steel, 1); // 钢螺丝
        ItemStack screen = ItemList.Cover_Screen.get(1); // 电脑屏幕覆盖板

        // 使用 Forge 的 ShapedOreRecipe 添加合成配方
        net.minecraftforge.oredict.ShapedOreRecipe recipe = new net.minecraftforge.oredict.ShapedOreRecipe(
            Wireless_Energy_Tap.get(1),
            "ABC",
            "ADC",
            "EBE",
            'A',
            lvEmitter,
            'B',
            steelPlate,
            'C',
            lvEmitter,
            'D',
            screen,
            'E',
            steelScrew);

        CraftingManager.getInstance()
            .getRecipeList()
            .add(recipe);

        GTSimpleWirelessNetwork.LOG.info("已添加无线网络链路终端合成配方");
    }

    /**
     * 添加无线能量监视器的合成配方
     * <p>
     * 合成表（与便携版类似，但中间改为 LV 机械外壳）：
     * LV接收器 | 钢外壳 | LV接收器
     * LV接收器 | LV机械外壳 | LV接收器
     * 钢螺丝 | 钢外壳 | 钢螺丝
     */
    private static void addWirelessEnergyMonitorRecipe() {
        // 获取原材料
        ItemStack lvReceiver = ItemList.Sensor_LV.get(1); // LV接收器
        ItemStack steelPlate = GTOreDictUnificator.get(OrePrefixes.plate, Materials.Steel, 1); // 钢外壳
        ItemStack steelScrew = GTOreDictUnificator.get(OrePrefixes.screw, Materials.Steel, 1); // 钢螺丝
        ItemStack lvHull = ItemList.Hull_LV.get(1); // LV机械外壳

        // 使用 Forge 的 ShapedOreRecipe 添加合成配方
        net.minecraftforge.oredict.ShapedOreRecipe recipe = new net.minecraftforge.oredict.ShapedOreRecipe(
            Wireless_Energy_Monitor.get(1),
            "ABC",
            "ADC",
            "EBE",
            'A',
            lvReceiver,
            'B',
            steelPlate,
            'C',
            lvReceiver,
            'D',
            lvHull,
            'E',
            steelScrew);

        CraftingManager.getInstance()
            .getRecipeList()
            .add(recipe);

        GTSimpleWirelessNetwork.LOG.info("已添加无线能量监视器合成配方");
    }
}
