package com.miaokatze.gtswn.common.device;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.common.tileentities.generators.MTELightningRod;
import gregtech.common.tileentities.generators.MTESolarGenerator;
import gregtech.common.tileentities.machines.multi.MTEDieselEngineLegacy;
import gregtech.common.tileentities.machines.multi.MTEExtremeCombustionEngine;
import gregtech.common.tileentities.machines.multi.MTEFusionComputer;
import gregtech.common.tileentities.machines.multi.MTELargeCombustionEngine;
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
 * <li>GTSR 巨型蒸汽轮机机组（{@code MTEMegaSteamTurbineArray}）继承
 * {@code MTEEnhancedMultiBlockBase} 而非涡轮基类，且为 GTSR 唯一发电多方块；以
 * {@link Class#forName} 软检测纳入发电谓词，不引入编译期依赖，GTSR 缺失时退化为 null</li>
 * <li>名称链白名单层（v1.8.9）：instanceof 链只覆盖 gregtech 本包可编译直引的类，
 * tectech / goodgenerator / gtPlusPlus / gtnhintergalactic 等引擎族因项目自设的编译期
 * import 禁令（见 {@code DeviceSampleScheduler} 内注释）无法进 instanceof，此前一律被判
 * 耗电；改以「精确 simple-name 沿继承链匹配」补录（{@link #nameChainHitsGenerator}）：
 * 只读 {@code Class#getSimpleName()} 与 {@code getSuperclass()}，不 Class.forName、
 * 不触发额外类加载、不读注解、不遍历接口；排除名优先级高于包含名，链上任意层级命中
 * 排除名即判非发电</li>
 * </ul>
 */
public final class DeviceMachineTypes {

    /** GTSR 巨型蒸汽轮机机组类（GTSR 未安装时为 null）；仅加载不初始化，避免早期类加载副作用。 */
    private static final Class<?> GTSR_MEGA_STEAM_TURBINE = lookupGtsrMegaSteamTurbine();

    /**
     * 发电白名单——精确 simple-name 包含集（12 项；路径均相对参考源码
     * {@code Source_Code_Reference_Collection\GT5-Unofficial-5.09.54.133\src\main\java}，
     * 行号为类声明行，逐一核实）。命中判发电仅指 powerType 方向，幅值仍走既有采集链。
     */
    private static final Set<String> GENERATOR_CLASS_NAMES = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                // gregtech/common/tileentities/machines/multi/MTEUniversalChemicalFuelEngine.java:62
                // extends TTMultiblockBase；:231 setPowerFlow 写正 lEUt=发电
                "MTEUniversalChemicalFuelEngine",
                // goodgenerator/blocks/tileEntity/MTEUniversalChemicalFuelEngineLegacy.java:54
                "MTEUniversalChemicalFuelEngineLegacy",
                // goodgenerator/blocks/tileEntity/MTEMultiNqGeneratorLegacy.java:57
                "MTEMultiNqGeneratorLegacy",
                // gregtech/common/tileentities/machines/multi/MTELargeTurbineLegacy.java:64（abstract 基类）
                "MTELargeTurbineLegacy",
                // goodgenerator/blocks/tileEntity/base/MTELargeTurbineBaseLegacy.java:49（GG 大型涡轮 legacy 基类）
                "MTELargeTurbineBaseLegacy",
                // gtPlusPlus/xmod/gregtech/common/tileentities/machines/multi/production/turbines/
                // MTELargerTurbineBaseLegacy.java:62（GT++ 超大型涡轮 legacy 基类）
                "MTELargerTurbineBaseLegacy",
                // gregtech/common/tileentities/machines/multi/MTELargeNeutralizationEngine.java:77
                "MTELargeNeutralizationEngine",
                // gtPlusPlus/xmod/gregtech/common/tileentities/machines/multi/production/MTELargeRocketEngine.java:56
                "MTELargeRocketEngine",
                // gtPlusPlus/xmod/gregtech/common/tileentities/machines/multi/production/
                // MTELargeSemifluidGenerator.java:44
                "MTELargeSemifluidGenerator",
                // gtPlusPlus/xmod/gregtech/common/tileentities/machines/multi/production/MTENuclearReactor.java:65
                "MTENuclearReactor",
                // gtnhintergalactic/tile/multi/TileEntityDysonSwarm.java:65（extends TTMultiblockBase，发电记账走
                // setPowerFlow）
                "TileEntityDysonSwarm",
                // goodgenerator/blocks/tileEntity/AntimatterGenerator.java:63——已知非目标：该类只维护私有
                // euLastCycle（:73、:204、getter :330），无 lEUt/mEUt 记账 ⇒ 名称链只得 powerType 方向，
                // 幅值仍真双零（方向兜底按发电落 out）
                "AntimatterGenerator")));

    /**
     * 发电白名单——精确 simple-name 排除集（12 项，优先级高于包含集，链上命中即判非发电）。
     * 多为「名字带 Generator/Turbine/Reactor 字样但实为耗电/输电/储能/蒸汽锅炉」的陷阱类。
     */
    private static final Set<String> EXCLUDED_GENERATOR_CLASS_NAMES = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                // gregtech/common/tileentities/machines/multi/MTEWormholeGenerator.java:86——无 EU 记账
                "MTEWormholeGenerator",
                // goodgenerator/blocks/tileEntity/base/MTELargeFusionComputer.java:81——聚变控制器本身纯耗电：
                // :241-242 产能时强制 lEUt 取负、:321 decreaseStoredEnergyUnits(-lEUt) 扣能、:561 面板键
                // gg.infodata.fusion.req 显示为「需求功率」，全类无 Dynamo 仓与 addEnergyOutput；
                // 聚变的 EU 回报经等离子产物在别处兑现，不由本控制器入网
                "MTELargeFusionComputer",
                // tectech/thing/metaTileEntity/multi/bec/MTEBECGenerator.java:52——lEUt 负（耗电维持 BEC）
                "MTEBECGenerator",
                // kubatech/tileentity/gregtech/multiblock/MTEHighTempGasCooledReactor.java:99——lEUt 负
                "MTEHighTempGasCooledReactor",
                // bartworks/common/tileentities/multis/MTEThoriumHighTempReactor.java:69——lEUt 负
                "MTEThoriumHighTempReactor",
                // gtPlusPlus/xmod/gregtech/common/tileentities/automation/MTETesseractGenerator.java:39——输电装置
                "MTETesseractGenerator",
                // kekztech/common/tileentities/MTELapotronicSuperCapacitor.java:91——储能
                "MTELapotronicSuperCapacitor",
                // tectech/thing/metaTileEntity/multi/MTEActiveTransformer.java:55——输电
                "MTEActiveTransformer",
                // tectech/thing/metaTileEntity/multi/MTETeslaTower.java:103——输电
                "MTETeslaTower",
                // gtPlusPlus/xmod/gregtech/common/tileentities/machines/multi/storage/MTEPowerSubStation.java:85——配能
                "MTEPowerSubStation",
                // gregtech/common/tileentities/machines/multi/MTELargeBoiler.java:63——mEUt 为燃料消耗值非 EU
                "MTELargeBoiler",
                // gregtech/common/tileentities/machines/multi/MTEThermalBoiler.java:58——同 Boiler 族
                "MTEThermalBoiler")));

    /** 名称链上溯深度上限：防御异常类层级/生成代理导致无上溯终止；正常 GT MTE 链远浅于此值。 */
    private static final int NAME_CHAIN_MAX_DEPTH = 32;

    private DeviceMachineTypes() {}

    private static Class<?> lookupGtsrMegaSteamTurbine() {
        try {
            return Class.forName(
                "com.miaokatze.gtsr.common.machine.MTEMegaSteamTurbineArray",
                false,
                DeviceMachineTypes.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

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
     * 聚变计算机 / 镭反应堆 / 内燃引擎家族 / GTSR 巨型蒸汽轮机（软检测），再叠加
     * v1.8.9 名称链白名单层（{@link #nameChainHitsGenerator}，编译期 import 禁令下的
     * tectech / goodgenerator / gtPlusPlus / gtnhintergalactic 引擎族补录）。仅用于
     * {@link DeviceTerminalDataStore.MachineRecord} 的 powerType 分类与 GUI「发电」筛选，
     * 不影响可绑定范围（D1）。
     * <p>
     * 内燃引擎家族（v1.8.2 补录，5.09.54.133 已核实）：<code>getNominalOutput</code> 输出模式
     * 恰为四类——大型/极大型内燃引擎（{@code MTEExtendedPowerMultiBlockBase} 系，运行期
     * {@code lEUt = getNominalOutput()} 正值=发电）与大型/极大型柴油遗留机
     * （{@code MTEEnhancedMultiBlockBase} 系，{@code mEUt = getNominalOutput()} 正值=发电）。
     * 不在词条时会被真双零兜底按耗电方向误记（幅值腿恒记 in）。
     * <p>
     * 名称链层为「精确 simple-name 匹配」：逐段等值比对 {@code getClass()} 及其
     * {@code getSuperclass()} 链的 {@code getSimpleName()}，非子串、非宽匹配基类；
     * 排除集（{@link #EXCLUDED_GENERATOR_CLASS_NAMES}）优先级高于包含集
     * （{@link #GENERATOR_CLASS_NAMES}），链上任意层级命中排除名立即判非发电。
     * <p>
     * 为何不全面改按 EU 符号判方向（GT5U 符号约定实证，5.09.54.133）：多方块
     * {@code lEUt} 正=发电/负=耗电（MTEExtendedPowerMultiBlockBase.java:28、:53-57、
     * :108-113；UCFE 经 TTMultiblockBase.java:494-504 setPowerFlow 写正值），符号可信；
     * 但普通多方块 {@code mEUt} 语义不可靠——同一字段既被 {@code MTEMultiBlockBase.java:1318-1323}
     * 当输出又被 :1052-1059 的 setEnergyUsage 强制取负，且 MTELargeBoiler 的 mEUt 是燃料值、
     * MTEFusionComputer.java:195-196 与 :381 强制取负后按消耗扣除；单机
     * {@code MTEBasicMachine.mEUt} 正=耗电（:136、:739、:751 取 getConsumption，经
     * :608、:726-727 drainEnergyForProcess 扣能）。三套字段语义互斥，按符号统一判会
     * 把 Boiler/聚变误判发电，故只以精确类名白名单收口。
     */
    public static boolean isGeneratorMachine(IMetaTileEntity mte) {
        return mte instanceof MTEBasicGenerator || mte instanceof MTESolarGenerator
            || mte instanceof MTELightningRod
            || mte instanceof MTELargeTurbineBase
            || mte instanceof MTEXLTurbineBase
            || mte instanceof MTEFusionComputer
            || mte instanceof MTELargeNaquadahReactor
            || mte instanceof MTELargeCombustionEngine
            || mte instanceof MTEExtremeCombustionEngine
            || mte instanceof MTEDieselEngineLegacy
            || (GTSR_MEGA_STEAM_TURBINE != null && GTSR_MEGA_STEAM_TURBINE.isInstance(mte))
            || (mte != null && nameChainHitsGenerator(mte.getClass()));
    }

    /**
     * 名称链谓词（package-private 仅供 {@code DeviceGeneratorNameChainTest} 以纯 JVM 桩类
     * 直测，不放宽为公开 API）：从 {@code clazz} 起沿 {@code getSuperclass()} 上溯，
     * 每层先查排除集（命中立即 false，即使更下层已命中包含集），再查包含集（命中记
     * 候选后继续上溯验证排除优先）；深度上限 {@link #NAME_CHAIN_MAX_DEPTH} 防无上溯
     * 终止，{@code Object} 根（superclass 为 null）自然终止；null 入参返回 false。
     * 全程只调用 {@code getSimpleName()}，不 Class.forName、不触发额外类加载、不读注解、
     * 不遍历接口。
     */
    static boolean nameChainHitsGenerator(Class<?> clazz) {
        boolean includedHit = false;
        int depth = 0;
        for (Class<?> c = clazz; c != null && depth < NAME_CHAIN_MAX_DEPTH; c = c.getSuperclass(), depth++) {
            String simpleName = c.getSimpleName();
            if (EXCLUDED_GENERATOR_CLASS_NAMES.contains(simpleName)) {
                return false;
            }
            if (!includedHit && GENERATOR_CLASS_NAMES.contains(simpleName)) {
                includedHit = true;
            }
        }
        return includedHit;
    }
}
