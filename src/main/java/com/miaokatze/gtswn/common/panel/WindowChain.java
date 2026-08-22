package com.miaokatze.gtswn.common.panel;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/**
 * 8 窗口 61 点 FIFO 采样链泛型骨架（O2-14，原 SWN-OPT-07 方案 C）。
 * <p>
 * EU 侧（{@link NetworkInfoDataSet}）与 AE 侧（{@code AEMonitorDataSet.Chain}）此前
 * 各持一份互为类型替换拷贝的窗口容器与 8 级嵌套流入链——本骨架单源承载：
 * <ul>
 * <li>窗口容器：每窗口固定 61 点 FIFO（超出容量丢弃最旧点），样本类型由 {@code <S>} 参数化</li>
 * <li>流入计数链：5m 每采样录入；1h 每 12 个 5m 点、8h 每 8 个 1h 点、24h 每 3 个 8h 点触发，
 * v1.5.17 拓展 7d 每 7 个 24h、1M 每 4 个 7d、3M 每 3 个 1M、1Y 每 4 个 3M 触发；
 * 派生点 timeMs/tick/总量字段取触发点瞬时值，聚合值（EU=eut / AE=rate）取上一级窗口
 * 最近 N 点算术均值（嵌套均值）</li>
 * <li>窗口 NBT：series 键 {@code series5m..series1Y}、counter 键 {@code counter5m..counter3M}，
 * 与逐字段旧版逐字一致；v1.5.17 旧存档（4 窗口）经 hasKey 守卫跳过缺失键</li>
 * </ul>
 * 样本 NBT 编解码、派生样本构造与聚合字段由子类钩子提供；采样锁
 * （EU 数据集级 / AE per-key）语义差异为共用硬边界，留在各自外壳。
 * <p>
 * 窗口实例化模式：{@code eager=true} 构造即建全 8 窗口（EU 侧语义：toNBT 无条件写全部
 * series 键）；{@code eager=false} 首个采样点到达才建窗口对象（AE 侧语义：未触碰窗口
 * 不写 series 键，与旧版 {@code if (series != null)} 分支一致）。
 */
public abstract class WindowChain<S> {

    public static final int WINDOW_5_MIN = 0;
    public static final int WINDOW_1_HOUR = 1;
    public static final int WINDOW_8_HOUR = 2;
    public static final int WINDOW_24_HOUR = 3;
    // v1.5.17：拓展到 8 窗口，新增 7d / 1M / 3M / 1Y
    public static final int WINDOW_7_DAY = 4;
    public static final int WINDOW_1_MONTH = 5;
    public static final int WINDOW_3_MONTH = 6;
    public static final int WINDOW_1_YEAR = 7;

    /** 窗口总数（下标 = 窗口常量值） */
    public static final int WINDOW_COUNT = 8;

    /** 每窗口固定 61 个采样点 */
    public static final int CAPACITY = 61;

    /** 流入计数链阈值（下标 = 窗口）：本窗口每 N 个采样触发下一级窗口流入（同为 getLastN 均值取点数） */
    private static final int[] INFLOW_THRESHOLDS = { 12, 8, 3, 7, 4, 3, 4 };

    /** NBT series 键（下标 = 窗口），与 v1.5.17 前逐字段版逐字一致 */
    private static final String[] SERIES_NBT_KEYS = { "series5m", "series1h", "series8h", "series24h", "series7d",
        "series1M", "series3M", "series1Y" };

    /** NBT counter 键（下标 = 窗口 0..6；1Y 为终端窗口无计数器） */
    private static final String[] COUNTER_NBT_KEYS = { "counter5m", "counter1h", "counter8h", "counter24h", "counter7d",
        "counter1M", "counter3M" };

    /** 单窗口 61 点 FIFO（内部容器，取代原 NetworkInfoWindowSeries / AEMonitorWindowSeries 两份拷贝） */
    private static final class Window<S> {

        // 内部存储：index 0 为最旧，size-1 为最新
        private final List<S> data = new ArrayList<>(CAPACITY);

        void add(S sample) {
            if (sample == null) {
                return;
            }
            if (data.size() >= CAPACITY) {
                data.remove(0);
            }
            data.add(sample);
        }

        List<S> copy() {
            return new ArrayList<>(data);
        }

        List<S> getLastN(int n) {
            int size = data.size();
            if (n <= 0) {
                return new ArrayList<>();
            }
            if (n >= size) {
                return new ArrayList<>(data);
            }
            return new ArrayList<>(data.subList(size - n, size));
        }

        S newest() {
            return data.isEmpty() ? null : data.get(data.size() - 1);
        }

        int size() {
            return data.size();
        }

        void clear() {
            data.clear();
        }
    }

    /** 每窗口 FIFO（下标 = 窗口常量；懒创建模式下未触碰窗口为 null） */
    private final List<Window<S>> windows;

    /** 流入计数链计数器（0..阈值-1，下标 = 窗口 0..6） */
    private final int[] counters = new int[WINDOW_COUNT - 1];

    /** true=构造即建全部窗口对象（EU 序列化语义）；false=懒创建（AE 序列化语义） */
    private final boolean eagerWindows;

    protected WindowChain(boolean eagerWindows) {
        this.eagerWindows = eagerWindows;
        this.windows = new ArrayList<>(WINDOW_COUNT);
        for (int w = 0; w < WINDOW_COUNT; w++) {
            windows.add(eagerWindows ? new Window<>() : null);
        }
    }

    /**
     * 构造下一级窗口的派生采样点：timeMs/tick/总量字段取触发点（source）瞬时值，
     * 聚合值取上一级窗口最近 N 点均值。
     */
    protected abstract S createDerived(S source, double aggregate);

    /** 聚合字段（EU 侧=eut，AE 侧=rate）：均值录入时参与算术平均的字段 */
    protected abstract double aggregateOf(S sample);

    protected abstract NBTTagCompound sampleToNBT(S sample);

    protected abstract S sampleFromNBT(NBTTagCompound tag);

    /** 窗口常量 → 下标（越界/非法值回落 5m，与原 switch default 分支语义一致） */
    private static int indexOf(int window) {
        return (window >= 0 && window < WINDOW_COUNT) ? window : WINDOW_5_MIN;
    }

    /** 取或创建指定窗口容器 */
    private Window<S> window(int index) {
        Window<S> window = windows.get(index);
        if (window == null) {
            window = new Window<>();
            windows.set(index, window);
        }
        return window;
    }

    /** 录入一个采样点到指定窗口（public 入口：5m 窗口由外壳 addSample 直录） */
    protected void addToWindow(int window, S sample) {
        window(indexOf(window)).add(sample);
    }

    /**
     * 流入计数链分发（O2-14 循环化，调用方应已把 origin 录入 5m 窗口）：
     * 本窗口计数 +1；满阈值时以「上一级窗口最近 N 点聚合值均值 + 触发点瞬时值」
     * 录入下一级窗口并沿链继续；未满阈值则更深层级全部不动
     * （与原 8 级嵌套 if 链语义一致，source 沿链传递始终为最近一次触发点）。
     */
    protected void flow(S origin) {
        S source = origin;
        for (int w = 0; w + 1 < WINDOW_COUNT; w++) {
            counters[w]++;
            if (counters[w] < INFLOW_THRESHOLDS[w]) {
                break;
            }
            counters[w] = 0;
            double aggregate = averageAggregate(window(w).getLastN(INFLOW_THRESHOLDS[w]));
            source = createDerived(source, aggregate);
            window(w + 1).add(source);
        }
    }

    private double averageAggregate(List<S> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (S s : samples) {
            sum += aggregateOf(s);
        }
        return sum / samples.size();
    }

    /** 指定窗口是否已实例化（懒创建模式下=曾被写入/读取过；eager 模式恒 true） */
    public boolean hasWindow(int window) {
        return windows.get(indexOf(window)) != null;
    }

    /** 查询指定窗口的数据集副本（渲染线程安全读取）；窗口未实例化返回空列表 */
    public List<S> query(int window) {
        Window<S> w = windows.get(indexOf(window));
        return w == null ? new ArrayList<>() : w.copy();
    }

    /** 查询指定窗口的当前样本数 */
    public int size(int window) {
        Window<S> w = windows.get(indexOf(window));
        return w == null ? 0 : w.size();
    }

    /** 最新采样点（5m 窗口的最新点 = 全局最新点）；空窗口返回 null */
    public S newest() {
        Window<S> w = windows.get(WINDOW_5_MIN);
        return w == null ? null : w.newest();
    }

    /** 清空全部窗口与流入计数器（不含子类自有状态） */
    public void clearWindows() {
        for (int w = 0; w < WINDOW_COUNT; w++) {
            Window<S> window = windows.get(w);
            if (window != null) {
                window.clear();
            }
        }
        for (int w = 0; w < counters.length; w++) {
            counters[w] = 0;
        }
    }

    /**
     * 写 8 个 series + 7 个 counter 键（顺序 series5m→series1Y、counter5m→counter3M，
     * 与逐字段旧版一致）。懒创建模式下未触碰窗口不写 series 键（原 AE 侧
     * {@code if (series != null)} 语义）；eager 模式窗口恒存在，等价于原 EU 侧无条件写全 8 键。
     */
    protected void writeWindowsToNBT(NBTTagCompound tag) {
        for (int w = 0; w < WINDOW_COUNT; w++) {
            Window<S> window = windows.get(w);
            if (window == null) {
                continue;
            }
            NBTTagCompound seriesTag = new NBTTagCompound();
            seriesTag.setInteger("count", window.size());
            NBTTagList list = new NBTTagList();
            for (S sample : window.copy()) {
                list.appendTag(sampleToNBT(sample));
            }
            seriesTag.setTag("samples", list);
            tag.setTag(SERIES_NBT_KEYS[w], seriesTag);
        }
        for (int w = 0; w < counters.length; w++) {
            tag.setInteger(COUNTER_NBT_KEYS[w], counters[w]);
        }
    }

    /**
     * 读 8 个 series + 7 个 counter 键。v1.5.17 向后兼容：旧存档（4 窗口）无
     * series7d/1M/3M/1Y 与 counter24h/7d/1M/3M 键时，hasKey 守卫跳过 series 读取、
     * getInteger 缺省返回 0；单个 series 超过 CAPACITY 时只取最后 61 个（防脏数据）。
     */
    protected void readWindowsFromNBT(NBTTagCompound tag) {
        clearWindows();
        if (tag == null) {
            return;
        }
        for (int w = 0; w < WINDOW_COUNT; w++) {
            if (!tag.hasKey(SERIES_NBT_KEYS[w])) {
                continue;
            }
            Window<S> window = window(w);
            NBTTagList list = tag.getCompoundTag(SERIES_NBT_KEYS[w])
                .getTagList("samples", Constants.NBT.TAG_COMPOUND);
            int start = Math.max(0, list.tagCount() - CAPACITY);
            for (int i = start; i < list.tagCount(); i++) {
                S sample = sampleFromNBT(list.getCompoundTagAt(i));
                if (sample != null) {
                    window.add(sample);
                }
            }
        }
        for (int w = 0; w < counters.length; w++) {
            counters[w] = tag.getInteger(COUNTER_NBT_KEYS[w]);
        }
    }
}
