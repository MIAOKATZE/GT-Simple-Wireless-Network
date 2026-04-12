package com.miaokatze.gtswn.register;

import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Test_Machine_EV;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Test_Machine_IV;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Test_Machine_LuV;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Wireless_Energy_Monitor;
import static com.miaokatze.gtswn.common.api.enums.MetaTileEntityID.MTETEST_EV;
import static com.miaokatze.gtswn.common.api.enums.MetaTileEntityID.MTETEST_IV;
import static com.miaokatze.gtswn.common.api.enums.MetaTileEntityID.MTETEST_LuV;
import static com.miaokatze.gtswn.common.api.enums.MetaTileEntityID.WIRELESS_ENERGY_MONITOR;

import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.machine.MTETestMachine;
import com.miaokatze.gtswn.common.machine.MTEWirelessEnergyMonitor;

/**
 * 标准机器注册器
 * 继承自 MachineRegistrar，负责具体定义并注册 EV、IV 和 LuV 等级的单方块测试机器。
 */
public class StandardMachineRegistrar extends MachineRegistrar {

    /**
     * 设置测试机器的注册项
     * 在此处定义每台机器的 ID、名称、本地化显示名以及对应的物品索引
     */
    @Override
    protected void setupRegistrations() {
        // 注册 EV 等级测试机器 (Tier 4)
        registerMachine(
            () -> new MTETestMachine(
                MTETEST_EV.ID,
                "gtswn.mtetest.ev",
                StatCollector.translateToLocal("gtswn.machine.test.ev"),
                4),
            Test_Machine_EV);

        // 注册 IV 等级测试机器 (Tier 5)
        registerMachine(
            () -> new MTETestMachine(
                MTETEST_IV.ID,
                "gtswn.mtetest.iv",
                StatCollector.translateToLocal("gtswn.machine.test.iv"),
                5),
            Test_Machine_IV);

        // 注册 LuV 等级测试机器 (Tier 6)
        registerMachine(
            () -> new MTETestMachine(
                MTETEST_LuV.ID,
                "gtswn.mtetest.luv",
                StatCollector.translateToLocal("gtswn.machine.test.luv"),
                6),
            Test_Machine_LuV);

        // 注册 LV 等级无线能量监视器 (Tier 1)
        registerMachine(
            () -> new MTEWirelessEnergyMonitor(
                WIRELESS_ENERGY_MONITOR.ID,
                "gtswn.wireless_energy_monitor",
                StatCollector.translateToLocal("gtswn.machine.wireless_monitor")),
            Wireless_Energy_Monitor);
    }
}
