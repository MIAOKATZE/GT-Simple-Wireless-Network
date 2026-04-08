package com.miaokatze.gtswn.loader;

import static gregtech.api.enums.GTValues.RA;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.api.recipe.GTSWNRecipeMaps;
import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;

/**
 * 测试用机器配方加载器
 * 为测试用多方块机器注册一些简单的测试配方，用于验证配方系统的集成。
 */
public class TestMachineRecipeLoader {

    /**
     * 初始化所有测试配方
     * 建议在 postInit 阶段调用
     */
    public static void initRecipes() {
        addAssemblerRecipes();
    }

    /**
     * 添加测试配方
     * 仅包含一个最简单的测试：硬币合成纸
     */
    private static void addAssemblerRecipes() {
        // --- 配方: 1个测试硬币 -> 1张纸 ---
        RA.stdBuilder()
            .itemInputs(GTSWNItemList.TestCoin.get(1))
            .itemOutputs(new ItemStack(Items.paper, 1))
            .duration(20) // 1秒
            .eut(32L) // LV voltage
            .addTo(GTSWNRecipeMaps.MultiTestMachineRecipes);
    }
}
