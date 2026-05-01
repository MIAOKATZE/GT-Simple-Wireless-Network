package com.miaokatze.gtswn.register;

import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.GTswn_Cover_Dynamo_Wireless;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.GTswn_Cover_Energy_Wireless;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Portable_Wireless_Network_Monitor;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.TestCoin;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Wireless_Energy_Tap;

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
        // registerTestCoinE(); // 取消测试物品注册，源码保留
        registerPortableWirelessNetworkMonitor();
        registerWirelessEnergyTap();
        registerGTswnCoverEnergyWireless();
        registerGTswnCoverDynamoWireless();
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
        // TestCoinE.setAndRegister(com.miaokatze.gtswn.common.items.TestCoinE::new); // 取消测试物品注册，源码保留
    }

    /**
     * 注册便携式无线网络监测终端
     */
    private static void registerPortableWirelessNetworkMonitor() {
        Portable_Wireless_Network_Monitor
            .setAndRegister(com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor::new);
    }

    /**
     * 注册无线能量分接器
     */
    private static void registerWirelessEnergyTap() {
        Wireless_Energy_Tap.setAndRegister(com.miaokatze.gtswn.common.items.WirelessEnergyTap::new);
    }

    /**
     * 注册GTswn无线能量覆盖板
     */
    private static void registerGTswnCoverEnergyWireless() {
        GTswn_Cover_Energy_Wireless.setAndRegister(com.miaokatze.gtswn.common.items.GTSwnCoverEnergyWireless::new);
    }

    /**
     * 注册GTswn无线动力覆盖板
     */
    private static void registerGTswnCoverDynamoWireless() {
        GTswn_Cover_Dynamo_Wireless.setAndRegister(com.miaokatze.gtswn.common.items.GTSwnCoverDynamoWireless::new);
    }
}
