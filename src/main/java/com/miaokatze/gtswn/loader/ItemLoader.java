package com.miaokatze.gtswn.loader;

import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.TestCoin;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

/**
 * 物品加载器
 * 负责初始化并执行所有物品的注册逻辑
 */
public class ItemLoader {

    /**
     * 初始化所有物品
     * 该方法应在模组预初始化 (PreInit) 阶段调用
     */
    public static void initItems() {
        GTSimpleWirelessNetwork.LOG.info("开始注册物品...");

        // 注册测试硬币
        TestCoin.setAndRegister(com.miaokatze.gtswn.common.items.TestCoin::new);

        GTSimpleWirelessNetwork.LOG.info("物品注册完成。");
    }
}
