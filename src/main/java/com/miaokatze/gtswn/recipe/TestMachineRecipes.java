package com.miaokatze.gtswn.recipe;

import static gregtech.api.enums.GTValues.RA;

import com.miaokatze.gtswn.api.recipe.GTSWNRecipeMaps;
import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;

/**
 * 测试用多方块机器配方注册器
 * <p>
 * 为测试用多方块机器注册配方，用于验证配方系统的集成。
 */
public class TestMachineRecipes {

    /**
     * 初始化所有测试配方
     * <p>
     * 建议在 postInit 阶段调用
     */
    public static void init() {
        addAssemblerRecipes();
    }

    /**
     * 添加组装机配方
     * <p>
     * 包含一个配方：
     * 1. 测试硬币 -> 电子测试硬币（多方块机器测试）
     */
    private static void addAssemblerRecipes() {
        // --- 配方: 1个测试硬币 -> 1个电子测试硬币 ---
        RA.stdBuilder()
            .itemInputs(GTSWNItemList.TestCoin.get(1))
            .itemOutputs(GTSWNItemList.TestCoinE.get(1))
            .duration(40) // 2秒
            .eut(128L) // MV voltage
            .addTo(GTSWNRecipeMaps.MultiTestMachineRecipes);
    }
}
