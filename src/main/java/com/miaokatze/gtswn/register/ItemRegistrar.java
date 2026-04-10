package com.miaokatze.gtswn.register;

import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Portable_Wireless_Network_Monitor;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.TestCoin;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.TestCoinE;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

/**
 * 物品注册器
 * 负责模组内所有普通物品（非机器方块）的注册与初始化逻辑
 */
public class ItemRegistrar {

    /**
     * 初始化并注册所有物品
     */
    public static void init() {
        GTSimpleWirelessNetwork.LOG.info("开始通过 ItemRegistrar 注册物品...");
        registerTestCoin();
        registerTestCoinE();
        registerPortableWirelessNetworkMonitor();
        GTSimpleWirelessNetwork.LOG.info("物品注册完成。");
    }

    /**
     * 注册测试硬币
     */
    private static void registerTestCoin() {
        TestCoin.setAndRegister(com.miaokatze.gtswn.common.items.TestCoin::new);
    }

    /**
     * 注册电子测试硬币
     */
    private static void registerTestCoinE() {
        TestCoinE.setAndRegister(com.miaokatze.gtswn.common.items.TestCoinE::new);
    }

    /**
     * 注册便携式无线网络监测终端
     */
    private static void registerPortableWirelessNetworkMonitor() {
        Portable_Wireless_Network_Monitor
            .setAndRegister(com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor::new);
    }
}
