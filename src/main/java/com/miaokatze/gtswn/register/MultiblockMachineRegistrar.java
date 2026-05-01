package com.miaokatze.gtswn.register;

/**
 * 多方块机器注册器
 * 负责注册 HV 等级的测试用多方块机器。
 */
public class MultiblockMachineRegistrar extends MachineRegistrar {

    @Override
    protected void setupRegistrations() {
        // 注册 HV 等级测试多方块机器 (Tier 5)
        // registerMachine(
        // () -> new MTEMultiTestMachine(
        // MTETEST_MULTIBLOCK_HV.ID,
        // "gtswn.multitest.hv",
        // StatCollector.translateToLocal("gtswn.machine.multitest.hv")),
        // Test_Multiblock_HV);
    }
}
