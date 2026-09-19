package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

import com.miaokatze.gtswn.common.device.DeviceSampleScheduler.EuFlowSample;

/**
 * EU 方向统一采集与三通道 FIFO 纯逻辑单测（EU 全 0 回归修复，零 Minecraft 游戏类加载：
 * 只触 {@link DeviceSampleScheduler#collectEuFlow} 纯算法核、
 * {@link DeviceTerminalDataStore.MachineRecord} 数据类与 NBT 数据标签，不启世界）。
 * <p>
 * 覆盖口径：getter 对采集（单机直读）、多方块 hatch 双路聚合按引用去重且聚合非零压制兜底、
 * 真双零兜底门（in==0 &amp;&amp; out==0 &amp;&amp; fallbackEut&gt;0）方向由发电白名单决定
 * （单方块耗电→in、发电机→out，方向不由数值符号决定）、fallbackEut=0/负幅值零写、
 * 控制器均值非零不兜底、特殊机器权威 provider 门（任务2：真双零时正值→out/负值→in，
 * 0 落回白名单兜底，getter/hatch 非零不介入）、停机/待机三通道写 0、旧档 NBT 缺新键兼容与
 * 负 net 回环、net FIFO 均值线性（恒有 {@code avg == outAvg − inAvg}）；v1.8.4 数值链扩为四层，
 * 另覆盖<b>长功率符号权威层</b>（仅多方块带符号 lEUt：正→out / 负→in，发电词条幅值优先、
 * 单机忽略、getter/hatch 非零压制、{@code signedLongPower=0} 时链序逐位退化为原三层链、
 * Long.MIN_VALUE 钳 Long.MAX_VALUE）与 tEff 万分度效率工具（{@code applyEfficiency} 的
 * 哨兵/零/正常/饱和四例，{@code readEfficiencyScale} 的字段命中、继承链命中与 -1 降级哨兵）。
 */
public class DeviceEuFlowCollectionTest {

    /** 窗口容量（= DeviceTerminalDataStore.FIFO_SIZE 编译期内联常量，不触发外层 MC 类初始化） */
    private static final int WINDOW = DeviceTerminalDataStore.FIFO_SIZE;

    // ==================== getter 对采集 ====================

    /** 单机：基座双 5-tick 均值直接成采集结果（getter 有流量时兜底不介入，方向=读哪个 getter） */
    @Test
    public void singleBlockUsesContainerGetters() {
        assertArrayEquals(
            new long[] { 300L, 120L },
            DeviceSampleScheduler.collectEuFlow(true, 300L, 120L, false, null, null, false, 0L, 0L, 0L));
        // 发电机 getter 已记录网络流出：直读优先，兜底幅值不叠加
        assertArrayEquals(
            new long[] { 0L, 450L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 450L, false, null, null, true, 320L, 0L, 0L));
    }

    /** 基座未实现 IBasicEnergyContainer（理论不可达防御）：两控制器读数不参与采集 */
    @Test
    public void missingContainerYieldsZeroFlow() {
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(false, 999L, 999L, false, null, null, false, 0L, 0L, 0L));
        // container 缺失不阻断兜底门（in/out 均为 0 即真双零）：白名单耗电方向照常写入
        assertArrayEquals(
            new long[] { 500L, 0L },
            DeviceSampleScheduler.collectEuFlow(false, 999L, 999L, false, null, null, false, 500L, 0L, 0L));
    }

    // ==================== 多方块 hatch 聚合去重 ====================

    /**
     * 两路（super public 字段 + TT 反射列表，TT 列表含 mEnergyHatches 子集）提交同一 hatch 基座
     * 引用时按引用去重只计一次；同方向保留首次出现值；控制器读数与 hatch 聚合叠加；
     * 聚合非零即压制兜底（同一断言内 fallbackEut=777 未介入）。
     */
    @Test
    public void multiblockHatchAggregationDeduplicatesByIdentity() {
        Object energyHatchA = new Object();
        Object energyHatchB = new Object();
        Object dynamoHatch = new Object();
        List<EuFlowSample> hatchIn = Arrays.asList(
            new EuFlowSample(energyHatchA, 200L),
            new EuFlowSample(energyHatchA, 260L),
            new EuFlowSample(energyHatchB, 50L));
        List<EuFlowSample> hatchOut = Arrays
            .asList(new EuFlowSample(dynamoHatch, 80L), new EuFlowSample(dynamoHatch, 99L));
        // 大型硅岩反应堆场景：控制器双均值稳态 0，实际流量全记 hatch 基座；聚合非零压制兜底
        assertArrayEquals(
            new long[] { 250L, 80L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, hatchIn, hatchOut, true, 777L, 0L, 0L));
        // 普通耗电多方块：控制器与 hatch 聚合叠加
        assertArrayEquals(
            new long[] { 350L, 110L },
            DeviceSampleScheduler.collectEuFlow(true, 100L, 30L, true, hatchIn, hatchOut, false, 777L, 0L, 0L));
        // 不同引用同值不合并（去重仅按引用）
        List<EuFlowSample> twoDistinct = Arrays
            .asList(new EuFlowSample(new Object(), 70L), new EuFlowSample(new Object(), 70L));
        assertArrayEquals(
            new long[] { 140L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, twoDistinct, null, false, 0L, 0L, 0L));
    }

    // ==================== 真双零兜底门：方向由发电白名单决定 ====================

    /**
     * 兜底三态：仅「in==0 &amp;&amp; out==0 &amp;&amp; fallbackEut&gt;0」时按调用方白名单
     * isGenerator 记方向（耗电→in / 发电→out），方向不由数值符号决定；fallbackEut=0 零写。
     */
    @Test
    public void fallbackDirectionFollowsGeneratorWhitelist() {
        // 白名单耗电（isGenerator=false）→ 记 input
        assertArrayEquals(
            new long[] { 500L, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 500L, 0L, 0L));
        // 白名单发电（isGenerator=true）→ 记 output
        assertArrayEquals(
            new long[] { 0L, 320L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 320L, 0L, 0L));
        // fallbackEut=0：零写
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 0L, 0L, 0L));
    }

    /** 单方块耗电兜底（v1.7.18 无线喂电回归主场景）：不再提前返回，真双零时 in=|mEUt| */
    @Test
    public void singleBlockConsumerFallsBackToInput() {
        assertArrayEquals(
            new long[] { 500L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, false, 500L, 0L, 0L));
        // 方向只由白名单决定：即使 false（耗电）也不写 out
        assertArrayEquals(
            new long[] { 1L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, false, 1L, 0L, 0L));
    }

    /** 发电机兜底：白名单 isGenerator=true 时 out=|lEUt|/|mEUt|（单方块与多方块无仓同口径） */
    @Test
    public void generatorFallsBackToOutput() {
        assertArrayEquals(
            new long[] { 0L, 320L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, true, 320L, 0L, 0L));
        // 多方块（无仓/仓全零）发电：同一白名单方向
        assertArrayEquals(
            new long[] { 0L, 777L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 777L, 0L, 0L));
    }

    /**
     * hatch 聚合非零必须压制兜底（即使 isGenerator 与 fallbackEut 都指向兜底）；
     * 空列表（枚举成功但聚合为 0）不再压制兜底——结构上无仓不等于无流量（v1.7.18 死码根因反转）。
     */
    @Test
    public void hatchNonZeroSuppressesFallback() {
        // 输入路聚合非零：in=hatch 聚合，兜底不介入
        List<EuFlowSample> inOnly = Arrays.asList(new EuFlowSample(new Object(), 250L));
        assertArrayEquals(
            new long[] { 250L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, inOnly, emptySamples(), false, 500L, 0L, 0L));
        // 输出路聚合非零：out=hatch 聚合，兜底幅值更大也不叠加
        List<EuFlowSample> outOnly = Arrays.asList(new EuFlowSample(new Object(), 80L));
        assertArrayEquals(
            new long[] { 0L, 80L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), outOnly, true, 500L, 0L, 0L));
        // 两路均空列表：真双零，兜底照常进入
        assertArrayEquals(
            new long[] { 500L, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 500L, 0L, 0L));
    }

    /** fallbackEut=0（该机无兜底幅值）与负幅值（调用方 absEut 契约防御）：一律零写、不翻方向 */
    @Test
    public void zeroOrNegativeFallbackEutWritesNothing() {
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 0L, 0L, 0L));
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, true, 0L, 0L, 0L));
        // 契约上调用方恒传 ≥0；核内 >0 门对负值同样零写（不写入、不取反、不改方向）
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, false, -500L, 0L, 0L));
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, -500L, 0L, 0L));
    }

    /** 控制器任一均值非 0（记账已在网络路径体现）→ 兜底不介入，非零通道保持原值 */
    @Test
    public void controllerNonZeroSuppressesFallback() {
        assertArrayEquals(
            new long[] { 0L, 40L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 40L, true, emptySamples(), emptySamples(), true, 500L, 0L, 0L));
        assertArrayEquals(
            new long[] { 90L, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 90L, 0L, true, emptySamples(), emptySamples(), false, 500L, 0L, 0L));
    }

    // ==================== 特殊机器权威 provider 门（任务2） ====================

    /**
     * provider 层（v1.8.4 起为四层数值链第 ③ 词条，v1.8.3 起词条命中机器同样按链咨询；本组用例
     * {@code signedLongPower=0}，符号层整体不介入，链序与 v1.8.3 逐位一致）：
     * 正值=发电→out、负值=消耗→in=|值|（方向由权威字段符号决定）；发电词条幅值仍居链首
     * （幅值>0 时 provider 不介入）；词条命中但幅值缺失（=0，如 LNR |mEUt| 恒 0、权威值只在
     * trueOutput）时由 provider 接管；providerEut=0（未命中/字段缺失/异常/权威值全 0）→ 数值链
     * 继续下一词条（耗电腿，仅非发电）；getter/hatch 已有流量（非双零）→ provider 不介入。
     */
    @Test
    public void specialProviderSignDecidesDirectionOnDoubleZero() {
        // 真双零 + provider 正值（发电型权威字段，如 LNR trueOutput/DysonSwarm euPerTick）→ out，
        // 白名单即使判定耗电（isGenerator=false）也被 provider 命中替代
        assertArrayEquals(
            new long[] { 0L, 600L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 500L, 600L, 0L));
        // 真双零 + provider 负值（未命中共享发电词条）→ in=|值|，符号决定消耗方向
        assertArrayEquals(
            new long[] { 800L, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 0L, -800L, 0L));
        // 数值链第 1 词条优先：发电词条幅值 > 0 时即使 provider 有冲突值也只用既有幅值
        assertArrayEquals(
            new long[] { 0L, 500L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 500L, -800L, 0L));
        // v1.8.3 链语义（LNR 修复）：词条命中但幅值缺失（=0）→ provider 正值接管 → out
        assertArrayEquals(
            new long[] { 0L, 800L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 0L, 800L, 0L));
        // 链语义补充：词条命中幅值缺失 + provider 负值 → 按符号记 in（不因词条命中而丢弃权威值）
        assertArrayEquals(
            new long[] { 800L, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 0L, -800L, 0L));
        // providerEut=0：未命中/失败/权威值全 0 → 数值链继续下一词条（耗电→in / 发电词条幅值已在链首消耗）
        assertArrayEquals(
            new long[] { 500L, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 500L, 0L, 0L));
        assertArrayEquals(
            new long[] { 0L, 320L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 320L, 0L, 0L));
        // 词条命中 + 幅值缺失 + provider 也 0 → 全链零保持双零（不编造数值）
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 0L, 0L, 0L));
        // getter/hatch 已有流量（非双零）：provider 不介入（权威值不叠加、不重复计数）
        List<EuFlowSample> inOnly = Arrays.asList(new EuFlowSample(new Object(), 250L));
        assertArrayEquals(
            new long[] { 250L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, inOnly, emptySamples(), false, 0L, 999L, 0L));
        // Long.MIN_VALUE 幅值防御（absEut 钳 Long.MAX_VALUE，不回绕为负）
        assertArrayEquals(
            new long[] { Long.MAX_VALUE, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 0L, Long.MIN_VALUE, 0L));
    }

    // ==================== 多方块长功率符号权威层（数值链第 ② 层） ====================

    /**
     * 符号层正向：真双零 + 多方块带符号长功率为正 → out=值、in=0（GT5U
     * MTEExtendedPowerMultiBlockBase 的 {@code lEUt > 0} 走 addEnergyOutput=发电；
     * 此处 isGenerator=false 且 fallback=0，只有符号层能给方向，证明该层不依赖白名单与 provider）。
     */
    @Test
    public void positiveSignedLongPowerWritesOutput() {
        assertArrayEquals(
            new long[] { 0L, 800L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 0L, 0L, 800L));
    }

    /**
     * 符号层负向：{@code lEUt} 为负走 drainEnergyInput=耗电（MTEAssemblyLine 实证）→ in=|值|、out=0；
     * 即使白名单判发电（isGenerator=true）也不改方向（方向此时来自权威字段符号）。
     */
    @Test
    public void negativeSignedLongPowerWritesInput() {
        assertArrayEquals(
            new long[] { 1200L, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 0L, 0L, -1200L));
    }

    /**
     * 符号层只作用于「多方块 + 长功率」：isMultiBlock=false 时 {@code signedLongPower} 整体被忽略
     * （单机 mEUt / maxEUOutput 无「正=发电」符号约定）——正长功率不得写 out，负长功率不得写 in。
     */
    @Test
    public void signedLongPowerIgnoredForSingleBlock() {
        // 单机 + 正长功率 900 + 耗电词条幅值 500：符号层不参与，链落到 ④ 耗电腿 in=500
        assertArrayEquals(
            new long[] { 500L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, false, 500L, 0L, 900L));
        // 单机 + 负长功率 + 无任何其它词条：全链零 → 保持双零（不编造方向）
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, true, 0L, 0L, -900L));
        // 单机 + 正长功率 + provider 命中：provider 照常接管（符号层不抢先、不覆盖）
        assertArrayEquals(
            new long[] { 0L, 600L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, false, 0L, 600L, 900L));
    }

    /**
     * 链序 ① 优先于 ②：发电词条幅值与符号层同时可得时白名单幅值胜出。
     * 必要保护：GT5U goodgenerator MTELargeFusionComputer 产能时把 lEUt 写成<b>负</b>
     * （该家族 :241-242 强制取负、:561 显示取 -lEUt），符号层若抢先即把发电多方块误判为耗电。
     */
    @Test
    public void generatorWhitelistAmplitudeBeatsSignLayer() {
        // isGenerator=true, fallback=500, signed=800 → out=500（词条幅值胜出，符号层不参与）
        assertArrayEquals(
            new long[] { 0L, 500L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 500L, 0L, 800L));
        // 反向保护（fusion 家族场景）：词条判发电而长功率为负 → 仍记 out=500，绝不记 in
        assertArrayEquals(
            new long[] { 0L, 500L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 500L, 0L, -800L));
    }

    /**
     * 链序 ② 优先于 ③④：符号层命中时 provider 与耗电腿都不介入（长功率是多方块权威字段）；
     * 反之符号层为 0 时 provider/耗电腿照常（见 {@link #zeroSignedLongPowerKeepsLegacyChainOrder}）。
     */
    @Test
    public void signLayerPrecedesProviderAndConsumerLeg() {
        // 符号层负值优先于 ④ 耗电腿：in=700（长功率幅值）而非 500（词条幅值）
        assertArrayEquals(
            new long[] { 700L, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 500L, 0L, -700L));
        // 符号层正值优先于 provider 正值：out=700 而非 999（不叠加、不重复计数）
        assertArrayEquals(
            new long[] { 0L, 700L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 0L, 999L, 700L));
        // 符号层正值优先于 provider 负值：out=700、in=0
        assertArrayEquals(
            new long[] { 0L, 700L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 0L, -999L, 700L));
    }

    /**
     * {@code signedLongPower=0}（单机 / 普通多方块 / 长功率字段为 0）时符号层整体跳过，
     * 四层链逐位退化为 v1.8.3 三层链：发电词条 → provider（正/负）→ 耗电词条 → 全链零。
     */
    @Test
    public void zeroSignedLongPowerKeepsLegacyChainOrder() {
        // ① 发电词条幅值优先于 provider
        assertArrayEquals(
            new long[] { 0L, 500L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 500L, -800L, 0L));
        // ③ provider 正 → out
        assertArrayEquals(
            new long[] { 0L, 800L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 0L, 800L, 0L));
        // ③ provider 负 → in=|值|
        assertArrayEquals(
            new long[] { 800L, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 0L, -800L, 0L));
        // ③ provider 正优先于 ④ 耗电腿
        assertArrayEquals(
            new long[] { 0L, 800L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 500L, 800L, 0L));
        // ④ 耗电腿（非发电 + provider 未命中）
        assertArrayEquals(
            new long[] { 500L, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 500L, 0L, 0L));
        // 全链零 → 双零
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 0L, 0L, 0L));
        // 单机同口径：发电家族名义输出与耗电幅值各自方向不变
        assertArrayEquals(
            new long[] { 0L, 320L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, true, 320L, 0L, 0L));
        assertArrayEquals(
            new long[] { 500L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, false, 500L, 0L, 0L));
    }

    /** getter/hatch 任一非零即压制符号层：真实网络流量优先于字段符号，符号层不叠加、不覆盖 */
    @Test
    public void hatchAndControllerNonZeroSuppressSignLayer() {
        List<EuFlowSample> outOnly = Arrays.asList(new EuFlowSample(new Object(), 80L));
        assertArrayEquals(
            new long[] { 0L, 80L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), outOnly, false, 0L, 0L, -4000L));
        assertArrayEquals(
            new long[] { 250L, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 250L, 0L, true, emptySamples(), emptySamples(), true, 0L, 0L, 900L));
    }

    /** 符号层入参 Long.MIN_VALUE 防御：absEut 钳 Long.MAX_VALUE，in 通道不回绕为负 */
    @Test
    public void signedLongPowerMinValueClampsToMax() {
        assertArrayEquals(
            new long[] { Long.MAX_VALUE, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 0L, 0L, Long.MIN_VALUE));
        // 负号 + 词条幅值为 0 的发电白名单机器：仍走符号层取幅，out 通道保持 0
        assertArrayEquals(
            new long[] { Long.MAX_VALUE, 0L },
            DeviceSampleScheduler
                .collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 0L, 0L, Long.MIN_VALUE));
    }

    // ==================== tEff 万分度效率工具（readEuFlow 取幅值处） ====================

    /** scale 为 -1 哨兵（无 tEff 字段 / 不可读 / 非长功率机器）→ 原值返回，既有读数逐位不变 */
    @Test
    public void efficiencySentinelLeavesMagnitudeUntouched() {
        assertEquals("哨兵不缩放", 1234L, DeviceSampleScheduler.applyEfficiency(1234L, -1L));
        assertEquals("哨兵下 0 仍 0", 0L, DeviceSampleScheduler.applyEfficiency(0L, -1L));
        assertEquals("哨兵下 MAX 原样", Long.MAX_VALUE, DeviceSampleScheduler.applyEfficiency(Long.MAX_VALUE, -1L));
    }

    /** scale=0（UCFE 无促进剂时 tEff=0，即真实 0 输出）与幅值非正：一律 0，不翻方向 */
    @Test
    public void zeroEfficiencyYieldsZeroMagnitude() {
        assertEquals(0L, DeviceSampleScheduler.applyEfficiency(1000000L, 0L));
        assertEquals(0L, DeviceSampleScheduler.applyEfficiency(0L, 15000L));
        // 入参契约是幅值；负值按 0 防御（绝不写出负的功率幅值）
        assertEquals(0L, DeviceSampleScheduler.applyEfficiency(-500L, 15000L));
    }

    /** 正常缩放：10000=100% 恒等、15000=150%（EFFICIENCY_CEILING=1.5 上限）、2500=25%、含低位截断 */
    @Test
    public void normalEfficiencyScalesProportionally() {
        assertEquals("100% 恒等", 500L, DeviceSampleScheduler.applyEfficiency(500L, 10000L));
        assertEquals("150%", 750L, DeviceSampleScheduler.applyEfficiency(500L, 15000L));
        assertEquals("25%", 125L, DeviceSampleScheduler.applyEfficiency(500L, 2500L));
        // 高低位拆分等价于 floor(magnitude*scale/10000)：30001*15000/10000 = 45001
        assertEquals("低位截断", 45001L, DeviceSampleScheduler.applyEfficiency(30001L, 15000L));
        assertEquals("大值 100% 恒等", 1234567890123L, DeviceSampleScheduler.applyEfficiency(1234567890123L, 10000L));
    }

    /**
     * 饱和钳制：只有<b>真实商</b>确实超出 long 才钳 Long.MAX_VALUE；
     * 中间乘积溢出而真实商放得下时不得假性饱和（1e18×15000 的乘积溢出，但商 1.5e18 可用）。
     */
    @Test
    public void efficiencySaturatesOnlyWhenTrueResultOverflows() {
        // 乘积溢出但真实商可用：不得假性饱和
        assertEquals(1500000000000000000L, DeviceSampleScheduler.applyEfficiency(1000000000000000000L, 15000L));
        assertEquals(Long.MAX_VALUE, DeviceSampleScheduler.applyEfficiency(Long.MAX_VALUE, 10000L));
        assertEquals("商真溢出", Long.MAX_VALUE, DeviceSampleScheduler.applyEfficiency(Long.MAX_VALUE, 15000L));
        // 标尺畸形巨大（远超 15000 上限）：整条读数不可信，饱和上报
        assertEquals(Long.MAX_VALUE, DeviceSampleScheduler.applyEfficiency(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    /** 效率字段命中：本类声明的 private long tEff（UCFE :76 同形字段）经 setAccessible 读出万分度值 */
    @Test
    public void efficiencyFieldFoundOnOwnClass() {
        assertEquals(12345L, DeviceSampleScheduler.readEfficiencyScale(new StubDeclaringTEff(12345L)));
        assertEquals(15000L, DeviceSampleScheduler.readEfficiencyScale(new StubDeclaringTEff(15000L)));
        // tEff=0 是「有效值 0」（无促进剂 ⇒ 真实 0 输出），不是「不缩放」哨兵
        assertEquals(0L, DeviceSampleScheduler.readEfficiencyScale(new StubDeclaringTEff(0L)));
        // 负 tEff = 未初始化/不适用 ⇒ 降级为 -1 哨兵
        assertEquals(-1L, DeviceSampleScheduler.readEfficiencyScale(new StubDeclaringTEff(-7L)));
    }

    /** 继承链命中：tEff 只声明在父类（子类不重复声明）也沿 getSuperclass() 找到 */
    @Test
    public void efficiencyFieldFoundOnSuperclass() {
        long inheritedScale = DeviceSampleScheduler.readEfficiencyScale(new StubInheritingTEff());
        assertEquals(13500L, inheritedScale);
        // 继承链命中同样参与缩放：2000 × 135% = 2700
        assertEquals(2700L, DeviceSampleScheduler.applyEfficiency(2000L, inheritedScale));
    }

    /** 无 tEff 字段 / 字段非数值 / null 入参 → -1 哨兵（不缩放），保证未接入效率的机器语义零变化 */
    @Test
    public void missingOrNonNumericEfficiencyFieldFallsBackToSentinel() {
        assertEquals(-1L, DeviceSampleScheduler.readEfficiencyScale(new StubWithoutTEff()));
        assertEquals(-1L, DeviceSampleScheduler.readEfficiencyScale(new StubWithNonNumericTEff()));
        assertEquals(-1L, DeviceSampleScheduler.readEfficiencyScale(null));
        // 组合口径：无字段 ⇒ 整条链按原值走（既有普通多方块 fallback 逐位不变）
        long noFieldScale = DeviceSampleScheduler.readEfficiencyScale(new StubWithoutTEff());
        assertEquals(777L, DeviceSampleScheduler.applyEfficiency(777L, noFieldScale));
    }

    // ==================== 停机 / 待机三通道写 0 ====================

    /**
     * 调度器对非 RUNNING 态跳过采集、按 (in=0, out=0, net=0) 推进窗口：
     * 停机/待机点写入后三通道槽位为 0、均值随 0 点稀释（双 0 语义扩展到三通道）。
     */
    @Test
    public void stoppedAndIdleWriteZeroOnAllChannels() {
        DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
        appendSample(record, 0L, 0L);
        appendSample(record, 0L, 0L);
        assertEquals("停机点流入通道为 0", 0L, record.fifoIn[0]);
        assertEquals("停机点流出通道为 0", 0L, record.fifoOut[0]);
        assertEquals(0D, record.inAvg, 0D);
        assertEquals(0D, record.outAvg, 0D);
        // 运行点 (in=100,out=40 → net=-60) 后接停机点：共 4 点，三通道按窗口稀释且线性保持
        appendSample(record, 100L, 40L);
        appendSample(record, 0L, 0L);
        assertEquals(4, record.count);
        assertEquals(100D / 4D, record.inAvg, 1e-9);
        assertEquals(40D / 4D, record.outAvg, 1e-9);
    }

    // ==================== 旧档 NBT 兼容 ====================

    /** v1.7.18 前旧档（仅 fifo/idx/count/avg 等旧键）：新键缺省 0，旧 net 通道键照常可用 */
    @Test
    public void oldSaveNbtWithoutNewKeysStillLoads() {
        NBTTagCompound legacy = new NBTTagCompound();
        NBTTagList fifo = new NBTTagList();
        for (long v : new long[] { 10L, 20L, -30L }) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setLong("v", v);
            fifo.appendTag(entry);
        }
        legacy.setTag("fifo", fifo);
        legacy.setInteger("idx", 3);
        legacy.setInteger("count", 3);
        legacy.setDouble("avg", 20D / 3D);
        legacy.setInteger("state", DeviceTerminalDataStore.STATE_RUNNING);
        DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
        record.readFromNBT(legacy);
        assertEquals(3, record.idx);
        assertEquals(3, record.count);
        assertEquals(DeviceTerminalDataStore.STATE_RUNNING, record.state);
        // 缺新键 → 通道数组全 0、两方向均值 0（powerType 旧档缺省钳耗电）
        for (int i = 0; i < WINDOW; i++) {
            assertEquals("fifoIn 缺省 0", 0L, record.fifoIn[i]);
            assertEquals("fifoOut 缺省 0", 0L, record.fifoOut[i]);
        }
        assertEquals(0D, record.inAvg, 0D);
        assertEquals(0D, record.outAvg, 0D);
        assertEquals(DeviceTerminalDataStore.POWER_TYPE_CONSUME, record.powerType);
    }

    /** 新档回环：三通道 + 三均值 + 负 net 全部对称读写（含旧档补写新键后再载） */
    @Test
    public void newKeysRoundTripIncludingNegativeNet() {
        DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
        appendSample(record, 0L, 0L);
        appendSample(record, 120L, 0L);
        appendSample(record, 0L, 999999999999L);
        record.powerType = DeviceTerminalDataStore.POWER_TYPE_GENERATE;
        DeviceTerminalDataStore.MachineRecord reloaded = new DeviceTerminalDataStore.MachineRecord();
        reloaded.readFromNBT(record.toNBT());
        assertArrayEquals(record.fifoIn, reloaded.fifoIn);
        assertArrayEquals(record.fifoOut, reloaded.fifoOut);
        assertEquals(record.idx, reloaded.idx);
        assertEquals(record.count, reloaded.count);
        assertEquals(record.inAvg, reloaded.inAvg, 0D);
        assertEquals(record.outAvg, reloaded.outAvg, 0D);
        assertEquals(DeviceTerminalDataStore.POWER_TYPE_GENERATE, reloaded.powerType);
    }

    // ==================== net FIFO 均值线性 ====================

    /**
     * 复刻调度器 drainSamples 的三通道增量推进（FifoMath.push 三份 runningSum、共用 idx/count，
     * 含停机 0 点与窗口回绕约 7 圈）：每步恒有 {@code sumNet == sumOut − sumIn} 且
     * {@code avg == outAvg − inAvg}（线性口径，A2 迁移前下游沿用 avg 即 net 均值）。
     */
    @Test
    public void netAverageIsLinearOverRollingWindow() {
        Random random = new Random(20260904L);
        DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
        long sumIn = 0L;
        long sumOut = 0L;
        for (int step = 0; step < 400; step++) {
            long euIn = step % 5 == 0 ? 0L : random.nextInt(1000);
            long euOut = step % 3 == 0 ? 0L : random.nextInt(1000);
            sumIn = FifoMath.push(record.fifoIn, record.idx, record.count, sumIn, euIn);
            sumOut = FifoMath.push(record.fifoOut, record.idx, record.count, sumOut, euOut);
            record.fifoIn[record.idx] = euIn;
            record.fifoOut[record.idx] = euOut;
            record.idx = (record.idx + 1) % WINDOW;
            if (record.count < WINDOW) {
                record.count++;
            }
            record.inAvg = FifoMath.avg(sumIn, record.count);
            record.outAvg = FifoMath.avg(sumOut, record.count);
            long expectedNetSum = sumOut - sumIn;
            assertEquals(
                "派生净均值 step=" + step,
                (double) expectedNetSum / record.count,
                record.outAvg - record.inAvg,
                1e-9);
            assertEquals("输入增量 vs 区间重算 step=" + step, FifoMath.rebuildSum(record.fifoIn, record.count), sumIn);
            assertEquals("输出增量 vs 区间重算 step=" + step, FifoMath.rebuildSum(record.fifoOut, record.count), sumOut);
        }
        assertEquals(WINDOW, record.count);
    }

    // ==================== 辅助 ====================

    private static List<EuFlowSample> emptySamples() {
        return new ArrayList<>();
    }

    /**
     * 三通道同点推进（调度器 drainSamples 口径的等效实现：增量 push 已由
     * {@link #netAverageIsLinearOverRollingWindow} 与 {@code FifoMathTest} 对拍覆盖，此处按全量重算）
     */
    private static void appendSample(DeviceTerminalDataStore.MachineRecord record, long euIn, long euOut) {
        record.fifoIn[record.idx] = euIn;
        record.fifoOut[record.idx] = euOut;
        record.idx = (record.idx + 1) % WINDOW;
        if (record.count < WINDOW) {
            record.count++;
        }
        record.inAvg = FifoMath.avg(FifoMath.rebuildSum(record.fifoIn, record.count), record.count);
        record.outAvg = FifoMath.avg(FifoMath.rebuildSum(record.fifoOut, record.count), record.count);
    }

    // ==================== tEff 字段反射桩（零 Minecraft 类加载） ====================

    /**
     * 自身类声明 {@code private long tEff}：与 GT5U
     * MTEUniversalChemicalFuelEngine.java:76 的字段形态同形（私有、long、由机器自身维护），
     * 用于验证 {@code setAccessible(true)} 命中本类字段。
     */
    private static final class StubDeclaringTEff {

        @SuppressWarnings("unused")
        private final long tEff;

        StubDeclaringTEff(long tEff) {
            this.tEff = tEff;
        }
    }

    /** tEff 只声明在父类：验证沿 {@code getClass()→getSuperclass()} 向上命中 */
    private static class StubDeclaringTEffParent {

        @SuppressWarnings("unused")
        private final long tEff = 13500L;
    }

    /** 子类自身无任何字段 */
    private static final class StubInheritingTEff extends StubDeclaringTEffParent {
    }

    /** 完全没有 tEff 字段：验证 -1 哨兵（不缩放） */
    private static final class StubWithoutTEff {

        @SuppressWarnings("unused")
        private final long otherEfficiencyField = 12345L;
    }

    /** 同名字段但非数值类型：不得算命中（同样 -1 哨兵） */
    private static final class StubWithNonNumericTEff {

        @SuppressWarnings("unused")
        private final Boolean tEff = Boolean.TRUE;
    }
}
