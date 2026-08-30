package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.assertEquals;

import java.util.Random;

import org.junit.Test;

/**
 * {@link FifoMath} 纯逻辑单测（v0.8.0 G1，零 Minecraft 类加载）。
 * <p>
 * 核心对拍口径：任意状态序列下 {@code runningSum == rebuildSum(fifo, count)} 恒成立
 * （滚动增量 vs 全量重算）；合法窗口另与旧实现的"全数组求和"对拍（未填位恒 0，两者恒等）。
 */
public class FifoMathTest {

    /** 窗口容量（与 DeviceTerminalDataStore.FIFO_SIZE 一致；纯常量复刻，避免测试加载 MC 类） */
    private static final int WINDOW = 60;

    /** 空窗口：区间和 0、均值 0；count 外脏位不计入 */
    @Test
    public void emptyWindow() {
        long[] fifo = new long[WINDOW];
        assertEquals(0L, FifoMath.rebuildSum(fifo, 0));
        fifo[10] = 12345L;
        assertEquals("count 外脏位不计入", 0L, FifoMath.rebuildSum(fifo, 0));
        assertEquals(0D, FifoMath.avg(555L, 0), 0D);
    }

    /** 首载重建：存量 [0,count) 一次性求和，count 外脏位不计入 */
    @Test
    public void firstLoadRebuild() {
        long[] fifo = new long[WINDOW];
        int count = 25;
        for (int i = 0; i < count; i++) {
            fifo[i] = (i + 1) * 100L;
        }
        fifo[40] = 999999L;
        assertEquals("Σ(1..25)×100", 32500L, FifoMath.rebuildSum(fifo, count));
        assertEquals(1300D, FifoMath.avg(32500L, count), 1e-9);
    }

    /**
     * 滚动增量 vs 全量重算随机对拍 1000 次（含窗口回绕 16 圈、Long 极值）：
     * 每步三重对拍——区间重算、旧实现全数组求和、均值口径。
     */
    @Test
    public void rollingVsRecompute1000() {
        Random random = new Random(20260830L);
        long[] fifo = new long[WINDOW];
        int idx = 0;
        int count = 0;
        long runningSum = 0L;
        for (int step = 0; step < 1000; step++) {
            long value;
            if (step % 97 == 0) {
                // 掺入 Long 极值：溢出回绕在增量与重算两侧恒一致（long 环绕算术）
                value = step % 194 == 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
            } else {
                value = random.nextLong() % 1000000007L;
            }
            runningSum = FifoMath.push(fifo, idx, count, runningSum, value);
            fifo[idx] = value;
            idx = (idx + 1) % WINDOW;
            if (count < WINDOW) {
                count++;
            }
            assertEquals("增量 vs 区间重算 step=" + step, FifoMath.rebuildSum(fifo, count), runningSum);
            long legacySum = 0L;
            for (long v : fifo) {
                legacySum += v;
            }
            assertEquals("增量 vs 旧全数组求和 step=" + step, legacySum, runningSum);
            assertEquals("均值口径 step=" + step, (double) legacySum / count, FifoMath.avg(runningSum, count), 0D);
        }
    }

    /** 窗口回绕：写入 1..150，窗口只保留最后 60 个（91..150），写指针回绕至 150%60=30 */
    @Test
    public void windowWraparound() {
        long[] fifo = new long[WINDOW];
        int idx = 0;
        int count = 0;
        long runningSum = 0L;
        for (long v = 1; v <= 150; v++) {
            runningSum = FifoMath.push(fifo, idx, count, runningSum, v);
            fifo[idx] = v;
            idx = (idx + 1) % WINDOW;
            if (count < WINDOW) {
                count++;
            }
            if (v <= WINDOW) {
                assertEquals("首载阶段 Σ1..v", v * (v + 1) / 2, runningSum);
            }
        }
        assertEquals("Σ(91..150)", (91L + 150L) * 60 / 2, runningSum);
        assertEquals(WINDOW, count);
        assertEquals(30, idx);
        for (long v = 91; v <= 150; v++) {
            assertEquals("值 " + v + " 应落在环形槽位 " + (v - 1) % WINDOW, v, fifo[(int) ((v - 1) % WINDOW)]);
        }
        assertEquals(FifoMath.rebuildSum(fifo, count), runningSum);
    }

    /**
     * 坏档防御一致性：idx 落在有效区间外的脏位（正常路径不可达，手改存档）——
     * 覆盖不挤出、不扩入，增量与重算口径仍严格一致。
     */
    @Test
    public void corruptStateStaysConsistent() {
        // ① idx > count：写位是区间外脏位，不计入不挤出
        long[] fifo = new long[WINDOW];
        fifo[0] = 5L;
        fifo[1] = 6L;
        fifo[2] = 7L;
        fifo[10] = 999L;
        assertEquals(18L, FifoMath.rebuildSum(fifo, 3));
        long runningSum = FifoMath.push(fifo, 10, 3, 18L, 100L);
        assertEquals("区间外脏位覆盖不应改变有效和", 18L, runningSum);
        fifo[10] = 100L;
        assertEquals(FifoMath.rebuildSum(fifo, 4), runningSum);

        // ② idx == count（正常首载扩容路径）：空位扩入区间，只计入新值
        long[] fifo2 = new long[WINDOW];
        fifo2[0] = 5L;
        fifo2[1] = 6L;
        fifo2[2] = 7L;
        long sum2 = FifoMath.push(fifo2, 3, 3, 18L, 100L);
        assertEquals(118L, sum2);
        fifo2[3] = 100L;
        assertEquals(FifoMath.rebuildSum(fifo2, 4), sum2);

        // ③ idx < count 且未满（区间内脏位覆盖）：挤出旧值计入新值
        long[] fifo3 = new long[WINDOW];
        fifo3[0] = 5L;
        fifo3[1] = 6L;
        fifo3[2] = 7L;
        long sum3 = FifoMath.push(fifo3, 1, 3, 18L, 100L);
        assertEquals(12L + 100L, sum3);
        fifo3[1] = 100L;
        assertEquals(FifoMath.rebuildSum(fifo3, 4), sum3);
    }
}
