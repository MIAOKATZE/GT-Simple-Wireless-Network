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
 * 刻意分离：前者含涡轮/镭反应堆/内燃引擎（均为 {@code MTEMultiBlockBase} 子类），不得混入 D1；
 * 聚变计算机两侧（GT5U 小聚变与 goodgenerator 大聚变）因控制器层面净耗电而不入发电分类</li>
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
 * <li>长功率符号层闸门为<b>结构前置条件</b>，不用名称名单：符号约定前提是 {@code lEUt} 为电量语义，但参考树
 * （5.09.54.133）穷举发现有 4 个 {@code extends MTEExtendedPowerMultiBlockBase}（或其 TT /
 * GT++ 派生基类）的子类把继承来的 {@code lEUt} 只当<b>非电量 display 值</b>写，正数却不向网络
 * 输出一丝 EU——{@code MTEThermalBoiler}（:168 蒸汽 {@code amount/进度/2}、:170 高压蒸汽
 * {@code amount/进度}，单位 mB/t；:180 无水归零）、
 * {@code gtPlusPlus .../production/MTEThermalBoilerLegacy}（:188,190，:186 上游注释自证
 * "Purely for display reasons, we don't actually make any EU"）、
 * {@code MTELargeBoilerBase}（:380,388 由燃料值算出；:357 归零；现役 Bronze/Steel/Titanium/
 * TungstenSteel 大锅炉全部 extends 此类，注意排除集里的 {@code MTELargeBoiler:63} 是
 * {@code MTEEnhancedMultiBlockBase} 另一条链、根本不写 lEUt）、
 * {@code gtPlusPlus .../processing/advanced/MTEAdvHeatExchanger}（:271 蒸汽系数量，:285-305
 * 反乘 1:160 换算蒸汽）。故不设名称名单，改用结构闸门：
 * <b>正 {@code lEUt} 只有当机器结构确含动态仓时才采信，负 {@code lEUt} 恒为耗电量照常采信</b>
 * （判定见 {@code DeviceSampleScheduler.readEuFlow} 的 {@code hasDynamoHatch} /
 * {@code longPowerTrusted}）。结构证据：上述 4 类的结构定义 {@code atLeast(...)} 均未注册任何
 * Dynamo 元素（ThermalBoiler :313/:327/:340；LargeBoilerBase :161-177；ThermalBoilerLegacy :331；
 * AdvHeatExchanger 只有自定义冷热仓 :76-99），而 {@code gregtech/api/util/HatchElementBuilder.java
 * :122-171} 的 adder 由 {@code atLeast} 枚举出的元素 {@code adder().rebrand()} 经 {@code orElse}
 * 归并而来 ⇒ 只覆盖被枚举的元素，合法结构里动态仓恒为空；反之真发电机结构必含 Dynamo
 * （{@code MTEUniversalChemicalFuelEngine.java:113} shape 字符 'G' + {@code :129 'G' =
 * Dynamo.newAny(...)}；{@code goodgenerator .../MTEUniversalChemicalFuelEngineLegacy.java:123}；
 * {@code MTELargeCombustionEngine.java:95}、{@code MTELargeTurbineBase.java:79}、
 * {@code MTELargeRocketEngine.java:156 Dynamo.or(TTDynamo)}）。允许 Dynamo 的储能/输电机全都不写
 * lEUt（{@code MTEActiveTransformer} 无 lEUt 写入、{@code MTEPowerSubStation.java:554} 恒置 0、
 * {@code MTETeslaTower} 同）⇒ 名称侧无需任何排除，穷举结论为<b>最小名称排除集 = 空集</b>。
 * 发电排除集与符号层彻底解耦：GG 大聚变 {@code MTELargeFusionComputer} 虽在排除集（判非发电），
 * 其负 lEUt 是真实 EU 耗电量 ⇒ 负值腿照常可信，真双零时符号层按耗电腿记入</li>
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
                // gtnhintergalactic/tile/multi/TileEntityDysonSwarm.java:65——extends TTMultiblockBase；权威值只在
                // :197 private long euPerTick（:276 按模组数×每模组功率×功率因子算，:547/:554 NBT 读写），
                // 入网经 :307-308 addEnergyOutput_EM(euPerTick, 1)，且全类不写 lEUt/mEUt ⇒ 符号层与词条
                // 幅值都取不到，幅值只能由 provider 字段链（DeviceSpecialPowerProvider）给
                "TileEntityDysonSwarm",
                // goodgenerator/blocks/tileEntity/AntimatterGenerator.java:63——已知非目标：该类只维护私有
                // euLastCycle（:73、:204、getter :330），无 lEUt/mEUt 记账 ⇒ 名称链只得 powerType 方向，
                // 幅值仍真双零（方向兜底按发电落 out）
                "AntimatterGenerator")));

    /**
     * 发电白名单——精确 simple-name 排除集（12 项，优先级高于包含集，链上命中即判非发电）。
     * 多为「名字带 Generator/Turbine/Reactor 字样但实为耗电/输电/储能/蒸汽锅炉」的陷阱类。
     * <p>
     * 本集合只服务发电分类（powerType 方向），与长功率符号层闸门解耦：符号层的前提是
     * {@code MTEExtendedPowerMultiBlockBase.lEUt} 为电量语义，闸门按<b>结构前置条件</b>判定
     * （正 lEUt 须结构含动态仓，见类注释与 {@code DeviceSampleScheduler.readEuFlow}），
     * 不按本集合成员清零；本集合多数成员本就不是扩展电力多方块子类（符号层
     * {@code instanceof} 首条件已挡住），或 lEUt 为真实电量（如 GG 大聚变
     * {@code MTELargeFusionComputer}，其负 lEUt 由符号层正确记入耗电腿）。
     */
    private static final Set<String> EXCLUDED_GENERATOR_CLASS_NAMES = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                // gregtech/common/tileentities/machines/multi/MTEWormholeGenerator.java:86——无 EU 记账
                "MTEWormholeGenerator",
                // goodgenerator/blocks/tileEntity/base/MTELargeFusionComputer.java:81——聚变控制器本身纯耗电：
                // 运行时权威 lEUt 由继承的 MTEExtendedPowerMultiBlockBase.java:108-113 setEnergyUsage 强制
                // 写为负（:241-242 只是 loadNBTData（:237 起、:239 注释 Migration code）的旧档符号迁移，
                // 不是产能时写负），:321 decreaseStoredEnergyUnits(-lEUt, true) 按需求功率扣能、
                // :561 面板键 gg.infodata.fusion.req 显示 formatNumber(-lEUt)「需求功率」，全类无 Dynamo
                // 仓与 addEnergyOutput；聚变的 EU 回报经等离子产物在别处兑现，不由本控制器入网。注：其
                // lEUt 为真实 EU 耗电量（负值）⇒ 结构闸门下负 lEUt 恒可信，真双零时符号层正确记入耗电腿
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
                // gregtech/common/tileentities/machines/multi/MTEThermalBoiler.java:58——同 Boiler 族；且它是
                // MTEExtendedPowerMultiBlockBase 子类却把 lEUt 复用为「产汽量」（:168 蒸汽
                // amount/进度/2、:170 高压蒸汽 amount/进度、:180 无水时归 0），全类不写 EU ⇒
                // 除名链一票否决外，其正 lEUt 还会被 readEuFlow 的结构闸门（结构无动态仓 ⇒ 正长功率
                // 不采信，见类注释）清零；同族的 MTEThermalBoilerLegacy / MTELargeBoilerBase 与
                // MTEAdvHeatExchanger 同样由该结构闸门拦下，不再依赖名称名单
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
     * 镭反应堆 / 内燃引擎家族 / GTSR 巨型蒸汽轮机（软检测），再叠加
     * v1.8.9 名称链白名单层（{@link #nameChainHitsGenerator}，编译期 import 禁令下的
     * tectech / goodgenerator / gtPlusPlus / gtnhintergalactic 引擎族补录）。仅用于
     * {@link DeviceTerminalDataStore.MachineRecord} 的 powerType 分类与 GUI「发电」筛选，
     * 不影响可绑定范围（D1）。
     * <p>
     * 聚变计算机不在此列（v1.8.11 起）：GT5U {@code MTEFusionComputer} 与其 Mk1-3 及
     * GT++ Adv Mk4/Mk5 子类在控制器层面是净耗电——{@code :195-196} 强制 {@code mEUt} 取负、
     * {@code :367-373} 从能量仓抽电入控制器、{@code :381} 与 {@code :416-418}（点火阈值）
     * 均走 {@code decreaseStoredEnergyUnits} 扣能，全类无 {@code addEnergyOutput}；其 EU 回报
     * 由等离子产物在下游兑现。与已列入排除集的 goodgenerator 大聚变控制器同口径。
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
     * 排除名一票否决 + 包含名命中，两者经同一链遍历入口 {@link #nameChainHitsAnyName}
     * 各扫一遍（{@code 包含命中 && !排除命中}）；语义与「逐层先查排除、命中立即返回
     * false；否则记录包含候选后继续上溯」完全等价——排除在链上任意层级（同深度上限内）
     * 命中即整条链为 false，与包含名先后无关。
     * 全程只调用 {@code getSimpleName()}，不 Class.forName、不触发额外类加载、不读注解、
     * 不遍历接口。
     */
    static boolean nameChainHitsGenerator(Class<?> clazz) {
        return nameChainHitsAnyName(clazz, GENERATOR_CLASS_NAMES)
            && !nameChainHitsAnyName(clazz, EXCLUDED_GENERATOR_CLASS_NAMES);
    }

    /**
     * 名称链遍历唯一实现（private）：从 {@code clazz} 起沿 {@code getSuperclass()} 上溯，逐段
     * 等值比对 {@code getSimpleName()} 与 {@code names}，任意层命中即 true；深度上限
     * {@link #NAME_CHAIN_MAX_DEPTH} 防无上溯终止，{@code Object} 根（superclass 为 null）自然
     * 终止；null 入参返回 false。
     */
    private static boolean nameChainHitsAnyName(Class<?> clazz, Set<String> names) {
        int depth = 0;
        for (Class<?> c = clazz; c != null && depth < NAME_CHAIN_MAX_DEPTH; c = c.getSuperclass(), depth++) {
            if (names.contains(c.getSimpleName())) {
                return true;
            }
        }
        return false;
    }
}
