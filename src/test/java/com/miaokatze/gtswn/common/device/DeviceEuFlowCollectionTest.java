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
 * 控制器均值非零不兜底、停机/待机三通道写 0、旧档 NBT 缺新键兼容与负 net 回环、
 * net FIFO 均值线性（恒有 {@code avg == outAvg − inAvg}）。
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
            DeviceSampleScheduler.collectEuFlow(true, 300L, 120L, false, null, null, false, 0L));
        // 发电机 getter 已记录网络流出：直读优先，兜底幅值不叠加
        assertArrayEquals(
            new long[] { 0L, 450L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 450L, false, null, null, true, 320L));
    }

    /** 基座未实现 IBasicEnergyContainer（理论不可达防御）：两控制器读数不参与采集 */
    @Test
    public void missingContainerYieldsZeroFlow() {
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(false, 999L, 999L, false, null, null, false, 0L));
        // container 缺失不阻断兜底门（in/out 均为 0 即真双零）：白名单耗电方向照常写入
        assertArrayEquals(
            new long[] { 500L, 0L },
            DeviceSampleScheduler.collectEuFlow(false, 999L, 999L, false, null, null, false, 500L));
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
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, hatchIn, hatchOut, true, 777L));
        // 普通耗电多方块：控制器与 hatch 聚合叠加
        assertArrayEquals(
            new long[] { 350L, 110L },
            DeviceSampleScheduler.collectEuFlow(true, 100L, 30L, true, hatchIn, hatchOut, false, 777L));
        // 不同引用同值不合并（去重仅按引用）
        List<EuFlowSample> twoDistinct = Arrays
            .asList(new EuFlowSample(new Object(), 70L), new EuFlowSample(new Object(), 70L));
        assertArrayEquals(
            new long[] { 140L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, twoDistinct, null, false, 0L));
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
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 500L));
        // 白名单发电（isGenerator=true）→ 记 output
        assertArrayEquals(
            new long[] { 0L, 320L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 320L));
        // fallbackEut=0：零写
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 0L));
    }

    /** 单方块耗电兜底（v1.7.18 无线喂电回归主场景）：不再提前返回，真双零时 in=|mEUt| */
    @Test
    public void singleBlockConsumerFallsBackToInput() {
        assertArrayEquals(
            new long[] { 500L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, false, 500L));
        // 方向只由白名单决定：即使 false（耗电）也不写 out
        assertArrayEquals(
            new long[] { 1L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, false, 1L));
    }

    /** 发电机兜底：白名单 isGenerator=true 时 out=|lEUt|/|mEUt|（单方块与多方块无仓同口径） */
    @Test
    public void generatorFallsBackToOutput() {
        assertArrayEquals(
            new long[] { 0L, 320L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, true, 320L));
        // 多方块（无仓/仓全零）发电：同一白名单方向
        assertArrayEquals(
            new long[] { 0L, 777L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, 777L));
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
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, inOnly, emptySamples(), false, 500L));
        // 输出路聚合非零：out=hatch 聚合，兜底幅值更大也不叠加
        List<EuFlowSample> outOnly = Arrays.asList(new EuFlowSample(new Object(), 80L));
        assertArrayEquals(
            new long[] { 0L, 80L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), outOnly, true, 500L));
        // 两路均空列表：真双零，兜底照常进入
        assertArrayEquals(
            new long[] { 500L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 500L));
    }

    /** fallbackEut=0（该机无兜底幅值）与负幅值（调用方 absEut 契约防御）：一律零写、不翻方向 */
    @Test
    public void zeroOrNegativeFallbackEutWritesNothing() {
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), false, 0L));
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, true, 0L));
        // 契约上调用方恒传 ≥0；核内 >0 门对负值同样零写（不写入、不取反、不改方向）
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, false, null, null, false, -500L));
        assertArrayEquals(
            new long[] { 0L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 0L, true, emptySamples(), emptySamples(), true, -500L));
    }

    /** 控制器任一均值非 0（记账已在网络路径体现）→ 兜底不介入，非零通道保持原值 */
    @Test
    public void controllerNonZeroSuppressesFallback() {
        assertArrayEquals(
            new long[] { 0L, 40L },
            DeviceSampleScheduler.collectEuFlow(true, 0L, 40L, true, emptySamples(), emptySamples(), true, 500L));
        assertArrayEquals(
            new long[] { 90L, 0L },
            DeviceSampleScheduler.collectEuFlow(true, 90L, 0L, true, emptySamples(), emptySamples(), false, 500L));
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
}
