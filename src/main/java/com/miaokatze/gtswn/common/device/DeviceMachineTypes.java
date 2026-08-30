package com.miaokatze.gtswn.common.device;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.common.tileentities.generators.MTELightningRod;
import gregtech.common.tileentities.generators.MTESolarGenerator;
import gregtech.common.tileentities.machines.multi.MTEFusionComputer;
import gregtech.common.tileentities.machines.multi.MTELargeNaquadahReactor;
import gregtech.common.tileentities.machines.multi.turbines.MTELargeTurbineBase;
import gregtech.common.tileentities.machines.multi.xlturbines.MTEXLTurbineBase;

/**
 * 设备信息终端统一机器判别式（最小公共 helper；D1 三处调用点共用，避免判别式复制漂移）。
 * <p>
 * 【GT5U 5.09.54.20 已核实事实】
 * <ul>
 * <li>{@code MTEBasicGenerator extends MTEBasicTank}（→ MTETieredMachineBlock → MetaTileEntity），
 * 与 {@code MTEBasicMachine} / {@code MTEMultiBlockBase} 无继承关系，故 D1 必须显式列入
 * 发电常规机、太阳能与避雷针</li>
 * <li>发电分类（{@link #isGeneratorMachine}）与工作机器判别（{@link #isWorkingMachine}）
 * 刻意分离：前者含涡轮/聚变/镭反应堆（均为 {@code MTEMultiBlockBase} 子类），不得混入 D1</li>
 * <li>{@code MTEExtendedPowerMultiBlockBase} 本身含大量 Industrial 耗电子类，不可作发电信号；
 * {@code MTEWormholeGenerator} 无 EU 记账，两类均不进发电谓词</li>
 * </ul>
 */
public final class DeviceMachineTypes {

    private DeviceMachineTypes() {}

    /**
     * D1 工作机器判别式（绑定 / 放置登记 / 采样自愈 / 世界扫描统一口径）：
     * {@code MTEBasicMachine ∥ MTEMultiBlockBase ∥ MTEBasicGenerator ∥ MTESolarGenerator ∥ MTELightningRod}。
     * <p>
     * 注意 {@code mte} 可能为 null（TE 摘除瞬间），直接返回 false。
     */
    public static boolean isWorkingMachine(IMetaTileEntity mte) {
        return mte instanceof MTEBasicMachine || mte instanceof MTEMultiBlockBase
            || mte instanceof MTEBasicGenerator
            || mte instanceof MTESolarGenerator
            || mte instanceof MTELightningRod;
    }

    /**
     * 发电分类谓词（powerType=1）：发电常规机 / 太阳能 / 避雷针 / 大型涡轮 / XL 涡轮 /
     * 聚变计算机 / 镭反应堆。仅用于 {@link DeviceTerminalDataStore.MachineRecord} 的
     * powerType 分类与 GUI「发电」筛选，不影响可绑定范围（D1）。
     */
    public static boolean isGeneratorMachine(IMetaTileEntity mte) {
        return mte instanceof MTEBasicGenerator || mte instanceof MTESolarGenerator
            || mte instanceof MTELightningRod
            || mte instanceof MTELargeTurbineBase
            || mte instanceof MTEXLTurbineBase
            || mte instanceof MTEFusionComputer
            || mte instanceof MTELargeNaquadahReactor;
    }
}
