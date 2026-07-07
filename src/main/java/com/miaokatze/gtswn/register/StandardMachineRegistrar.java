package com.miaokatze.gtswn.register;

import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Wireless_Energy_Monitor;
import static com.miaokatze.gtswn.common.api.enums.MetaTileEntityID.WIRELESS_ENERGY_MONITOR;

import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.machine.MTEWirelessEnergyMonitor;

/**
 * 标准机器注册器
 * 继承自 MachineRegistrar，负责具体定义并注册模组内的标准单方块机器。
 */
public class StandardMachineRegistrar extends MachineRegistrar {

    /**
     * 设置机器的注册项
     * 在此处定义每台机器的 ID、名称、本地化显示名以及对应的物品索引
     */
    @Override
    protected void setupRegistrations() {
        // 注册 LV 等级无线能量监视器 (Tier 1)
        registerMachine(
            () -> new MTEWirelessEnergyMonitor(
                WIRELESS_ENERGY_MONITOR.ID,
                "gtswn.wireless_energy_monitor",
                StatCollector.translateToLocal("gtswn.machine.wireless_monitor")),
            Wireless_Energy_Monitor);
    }
}
