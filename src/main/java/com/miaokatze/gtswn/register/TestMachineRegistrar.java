package com.miaokatze.gtswn.register;

import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Test_Machine_EV;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Test_Machine_IV;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.Test_Machine_LuV;
import static com.miaokatze.gtswn.common.api.enums.MetaTileEntityID.MTETEST_EV;
import static com.miaokatze.gtswn.common.api.enums.MetaTileEntityID.MTETEST_IV;
import static com.miaokatze.gtswn.common.api.enums.MetaTileEntityID.MTETEST_LuV;

import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.machine.MTETestMachine;

/**
 * 测试机器注册器
 */
public class TestMachineRegistrar extends MachineRegistrar {

    @Override
    protected void setupRegistrations() {
        // 注册 EV 等级测试机器
        registerMachine(
            () -> new MTETestMachine(
                MTETEST_EV.ID,
                "gtswn.mtetest.ev",
                StatCollector.translateToLocal("wts.mtetest.tier.EV"),
                4),
            Test_Machine_EV);

        // 注册 IV 等级测试机器
        registerMachine(
            () -> new MTETestMachine(
                MTETEST_IV.ID,
                "gtswn.mtetest.iv",
                StatCollector.translateToLocal("wts.mtetest.tier.IV"),
                5),
            Test_Machine_IV);

        // 注册 LuV 等级测试机器
        registerMachine(
            () -> new MTETestMachine(
                MTETEST_LuV.ID,
                "gtswn.mtetest.luv",
                StatCollector.translateToLocal("wts.mtetest.tier.LuV"),
                6),
            Test_Machine_LuV);
    }
}
