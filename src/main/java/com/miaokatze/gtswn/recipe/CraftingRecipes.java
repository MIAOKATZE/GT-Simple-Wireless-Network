package com.miaokatze.gtswn.recipe;

import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.ME_Network_Quantum_Terminal;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Network_Info_Panel;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Network_Info_Panel_Extender;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Portable_Wireless_Network_Monitor;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Wireless_Energy_Monitor;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Wireless_Energy_Tap;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.registry.GameRegistry;
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
        addNetworkInfoPanelRecipe();
        addNetworkInfoPanelExtenderRecipe();
        // ME 网络量子终端
        addQuantumTerminalRecipe();
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

    /**
     * 添加网络信息屏的合成配方
     * <p>
     * 合成表（四角=LV传感器，十字=玻璃块，中心=无线能量监视器(MTE)）：
     * LV传感器 | 玻璃块 | LV传感器
     * 玻璃块 | 无线能量监视器(MTE) | 玻璃块
     * LV传感器 | 玻璃块 | LV传感器
     */
    private static void addNetworkInfoPanelRecipe() {
        ItemStack lvSensor = ItemList.Sensor_LV.get(1); // LV传感器（接收器）
        // 原版玻璃块：通配符meta(32767=WILDCARD_VALUE)接受普通玻璃+染色玻璃
        // 不用 GTOreDictUnificator.get(OrePrefixes.glass, Materials.Glass, 1)，因 glass 是 selfReferencing 自引用前缀会返回 null
        ItemStack glass = new ItemStack(Blocks.glass, 1, 32767);

        net.minecraftforge.oredict.ShapedOreRecipe recipe = new net.minecraftforge.oredict.ShapedOreRecipe(
            Network_Info_Panel.get(1),
            "SGS",
            "GDG",
            "SGS",
            'S',
            lvSensor,
            'G',
            glass,
            'D',
            Wireless_Energy_Monitor.get(1)); // 中心材料：无线能量监视器(MTE)

        CraftingManager.getInstance()
            .getRecipeList()
            .add(recipe);

        GTSimpleWirelessNetwork.LOG.info("已添加网络信息屏合成配方");
    }

    /**
     * 添加网络信息拓展屏的合成配方
     * <p>
     * 合成表（四角=LV接收器，十字=玻璃块，中心=电脑屏幕覆盖板）：
     * LV接收器 | 玻璃块 | LV接收器
     * 玻璃块 | 电脑屏幕覆盖板 | 玻璃块
     * LV接收器 | 玻璃块 | LV接收器
     * <p>
     * 输出 2 个拓展屏
     */
    private static void addNetworkInfoPanelExtenderRecipe() {
        ItemStack lvReceiver = ItemList.Sensor_LV.get(1); // LV接收器
        // 原版玻璃块：通配符meta(32767=WILDCARD_VALUE)接受普通玻璃+染色玻璃
        // 不用 GTOreDictUnificator.get(OrePrefixes.glass, Materials.Glass, 1)，因 glass 是 selfReferencing 自引用前缀会返回 null
        ItemStack glass = new ItemStack(Blocks.glass, 1, 32767);
        ItemStack screen = ItemList.Cover_Screen.get(1); // 电脑屏幕覆盖板

        net.minecraftforge.oredict.ShapedOreRecipe recipe = new net.minecraftforge.oredict.ShapedOreRecipe(
            Network_Info_Panel_Extender.get(2),
            "RGR",
            "GSG",
            "RGR",
            'R',
            lvReceiver,
            'G',
            glass,
            'S',
            screen);

        CraftingManager.getInstance()
            .getRecipeList()
            .add(recipe);

        GTSimpleWirelessNetwork.LOG.info("已添加网络信息拓展屏合成配方");
    }

    /**
     * 添加 ME 网络量子终端的合成配方
     * <p>
     * 合成表（十字结构，中心 C = AE2 控制器）：
     * 福鲁伊克斯方块 | GT metaitem.01:32680 | 福鲁伊克斯方块
     * GT metaitem.01:32680 | AE2 控制器 | GT metaitem.01:32680
     * 福鲁伊克斯方块 | GT metaitem.01:32680 | 福鲁伊克斯方块
     * <p>
     * 材料引用遵循 plan/crafting_20260727_094902.java 参考配方：
     * AE2 方块经 GameRegistry.findItem 解耦（项目无 AE2 方块 OreDict 先例），
     * GT metaitem.01:32680 经 findItem + meta（动态注册 meta，无 ItemList 静态映射）。
     */
    private static void addQuantumTerminalRecipe() {
        // A = AE2 福鲁伊克斯方块（appliedenergistics2:tile.BlockFluix）
        Item fluixItem = GameRegistry.findItem("appliedenergistics2", "tile.BlockFluix");
        // B = GT metaitem.01:32680（gregtech:gt.metaitem.01 的 meta 32680）
        Item gtMetaItem = GameRegistry.findItem("gregtech", "gt.metaitem.01");
        // C = AE2 控制器（appliedenergistics2:tile.BlockController）
        Item controllerItem = GameRegistry.findItem("appliedenergistics2", "tile.BlockController");

        // 防御性校验：AE2/GT 未加载时 findItem 返回 null，跳过注册避免 NPE
        if (fluixItem == null || gtMetaItem == null || controllerItem == null) {
            GTSimpleWirelessNetwork.LOG.warn("[配方] ME 网络量子终端合成配方注册跳过：AE2/GT 物品缺失");
            return;
        }

        ItemStack fluix = new ItemStack(fluixItem, 1, 0);
        ItemStack gtComponent = new ItemStack(gtMetaItem, 1, 32680);
        ItemStack controller = new ItemStack(controllerItem, 1, 0);

        net.minecraftforge.oredict.ShapedOreRecipe recipe = new net.minecraftforge.oredict.ShapedOreRecipe(
            ME_Network_Quantum_Terminal.get(1),
            "ABA",
            "BCB",
            "ABA",
            'A',
            fluix,
            'B',
            gtComponent,
            'C',
            controller);

        CraftingManager.getInstance()
            .getRecipeList()
            .add(recipe);
        GTSimpleWirelessNetwork.LOG.info("已添加 ME 网络量子终端合成配方");
    }
}
