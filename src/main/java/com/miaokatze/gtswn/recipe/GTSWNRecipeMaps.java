package com.miaokatze.gtswn.recipe;

import static gregtech.api.recipe.RecipeMapBuilder.*;

import gregtech.api.gui.modularui.GTUITextures;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.RecipeMapBackend;

/**
 * GTSWN 模组专属配方表定义
 * <p>
 * 该类的核心目的是为模组内的机器提供独立的 NEI 分类支持。
 * 通过自定义 RecipeMap，我们可以将机器的配方与 GT 原版或其他附属模组的配方区分开，
 * 并绑定特定的机器图标作为配方页的显示标识。
 */
public class GTSWNRecipeMaps {

    /**
     * 测试多方块机器专属配方表
     * <p>
     * 配置说明：
     * - maxIO: 最大输入/输出槽位（物品 9/4, 流体 4/4）
     * - minInputs: 最小输入要求（至少 1 个物品输入）
     * - progressBar: GUI 中显示的进度条样式（箭头）
     * - neiHandlerInfo: NEI 集成配置，绑定 Test_Multiblock_HV 物品作为配方页图标
     */
    public static final RecipeMap<RecipeMapBackend> MultiTestMachineRecipes = of("gtswn.recipe.multitest")
        .maxIO(9, 4, 4, 4)
        .minInputs(1, 0)
        .progressBar(GTUITextures.PROGRESSBAR_ARROW)
        .neiHandlerInfo(
            builder -> builder
                // 设置 NEI 配方页顶部显示的图标
                .setDisplayStack(com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Test_Multiblock_HV.get(1))
                // 设置每页显示的最大配方数量
                .setMaxRecipesPerPage(1))
        .build();
}
