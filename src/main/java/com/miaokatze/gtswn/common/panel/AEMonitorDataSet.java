package com.miaokatze.gtswn.common.panel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/**
 * AE 监视数据集（每网络信息屏一份，每个被监视物品/流体独立维护 8 窗口 × 61 点 FIFO）。
 * <p>
 * key 为字符串标识（{@code item:<registryName>:<meta>} 或 {@code fluid:<fluidName>}）。
 * 8 个独立 {@link AEMonitorWindowSeries} 分别承载 5m / 1h / 8h / 24h / 7d / 1M / 3M / 1Y 时间窗口，
 * 原始采样点按"流入计数链"分发到各级窗口（均值录入）：
 * <ul>
 * <li>5m 集：每接收 1 个采样点都以瞬时值录入 5m 集</li>
 * <li>1h 集：每 12 个 5m 采样触发一次，rate 取最近 12 个 5m 点的算术均值，amount/timeMs/tick 用触发点瞬时值</li>
 * <li>8h 集：每 8 个 1h 采样触发一次，rate 取最近 8 个 1h 点的算术均值（嵌套均值），amount/timeMs/tick 用触发点 1h 瞬时值</li>
 * <li>24h 集：每 3 个 8h 采样触发一次，rate 取最近 3 个 8h 点的算术均值（嵌套均值），amount/timeMs/tick 用触发点 8h 瞬时值</li>
 * <li>7d 集：每 7 个 24h 采样触发一次，rate 取最近 7 个 24h 点的算术均值（嵌套均值），amount/timeMs/tick 用触发点 24h 瞬时值</li>
 * <li>1M 集：每 4 个 7d 采样触发一次（1M=4*7d=28天），rate 取最近 4 个 7d 点的算术均值（嵌套均值）</li>
 * <li>3M 集：每 3 个 1M 采样触发一次（3M=3*1M=84天），rate 取最近 3 个 1M 点的算术均值（嵌套均值）</li>
 * <li>1Y 集：每 4 个 3M 采样触发一次（1Y=4*3M=336天≈11个月），rate 取最近 4 个 3M 点的算术均值（嵌套均值）</li>
 * </ul>
 * <p>
 * O2-13：窗口结构（NBT 键名 + 流入阈值 + 各触碰点循环）此前散布在 16 张并行 Map
 * （8 series Map + 7 counter Map），v1.5.17 扩窗时全部触碰点被双改——现收敛为
 * {@link #WINDOW_DEFS} 单点定义，addSample/query/size/clear/toNBT/readFromNBT
 * 按下标循环化，后续再扩窗口只改一处。NBT 键名与 v1.5.17 hasKey 守卫逐字保留。
 */
public class AEMonitorDataSet {

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

    /**
     * 窗口定义单点（O2-13）：series/counter 的 NBT 键名与本窗口流入阈值。
     * <p>
     * {@code inflowCount} = 本窗口每 N 个采样触发下一级窗口流入一次（同时也是
     * 计算下一级 rate 时对上一级窗口 {@code getLastN} 的取点数）。1Y 为终端窗口：
     * 无计数器（counterKey=null）、无下一级流入。
     */
    private static final class WindowDef {

        final String seriesKey;
        final String counterKey;
        final int inflowCount;

        WindowDef(String seriesKey, String counterKey, int inflowCount) {
            this.seriesKey = seriesKey;
            this.counterKey = counterKey;
            this.inflowCount = inflowCount;
        }
    }

    private static final WindowDef[] WINDOW_DEFS = {
        // 5m：每 12 点触发 1h 流入
        new WindowDef("series5m", "counter5m", 12), new WindowDef("series1h", "counter1h", 8),
        new WindowDef("series8h", "counter8h", 3),
        // v1.5.17：24h 不再是终端，新增后续窗口
        new WindowDef("series24h", "counter24h", 7), new WindowDef("series7d", "counter7d", 4),
        new WindowDef("series1M", "counter1M", 3), new WindowDef("series3M", "counter3M", 4),
        // 1Y 终端窗口：无计数器、无下一级
        new WindowDef("series1Y", null, 0) };

    /** 每窗口一张 series Map（下标 = 窗口常量，取代 8 个并行字段） */
    private final List<Map<String, AEMonitorWindowSeries>> seriesByWindow;

    /** 每窗口一张流入计数 Map（与 seriesByWindow 同下标；1Y 槽位为 null） */
    private final List<Map<String, Integer>> counterByWindow;

    // 每个 key 独立的采样锁（world tick），距离上次采样 ≥ 100 ticks 才允许新采样
    private final Map<String, Long> lastSampleTick = new HashMap<>();

    public AEMonitorDataSet() {
        this.seriesByWindow = new ArrayList<>(WINDOW_COUNT);
        this.counterByWindow = new ArrayList<>(WINDOW_COUNT);
        for (int w = 0; w < WINDOW_COUNT; w++) {
            seriesByWindow.add(new HashMap<>());
            counterByWindow.add(WINDOW_DEFS[w].counterKey == null ? null : new HashMap<>());
        }
    }

    /** 窗口常量 → 下标（越界/非法值回落 5m，与原 switch default 分支语义一致） */
    private static int indexOf(int window) {
        return (window >= 0 && window < WINDOW_COUNT) ? window : WINDOW_5_MIN;
    }

    private AEMonitorWindowSeries getOrCreate(Map<String, AEMonitorWindowSeries> map, String key) {
        AEMonitorWindowSeries series = map.get(key);
        if (series == null) {
            series = new AEMonitorWindowSeries();
            map.put(key, series);
        }
        return series;
    }

    private static int getCounter(Map<String, Integer> map, String key) {
        Integer value = map.get(key);
        return value == null ? 0 : value.intValue();
    }

    /**
     * 主入口：为指定 key 添加一个原始采样点，按流入计数链分发到各级窗口（均值录入）。
     *
     * @param key    被监视物品/流体的字符串标识
     * @param amount 当前数量（负值会取 0）
     * @param tick   世界 tick
     * @param timeMs 真实时间戳（毫秒）
     */
    public void addSample(String key, long amount, long tick, long timeMs) {
        AEMonitorSample newest = newest(key);
        double rate = 0.0D;
        if (newest != null && tick > newest.tick) {
            rate = (amount - newest.amount) / (double) (tick - newest.tick) * 1200.0D;
        }

        AEMonitorSample sample = new AEMonitorSample(timeMs, tick, amount, rate);
        getOrCreate(seriesByWindow.get(WINDOW_5_MIN), key).add(sample);

        // 流入计数链（O2-13 循环化）：本窗口计数 +1；满阈值时以「上一级窗口最近 N 点 rate
        // 均值 + 触发点瞬时 amount/timeMs/tick」录入下一级窗口；未满阈值则更深层级全部不动
        // （与原 8 级嵌套 if 链语义一致，source 沿链传递始终为最近一次触发点）
        AEMonitorSample source = sample;
        for (int w = 0; w + 1 < WINDOW_COUNT; w++) {
            Map<String, Integer> counters = counterByWindow.get(w);
            int count = getCounter(counters, key) + 1;
            counters.put(key, count);
            if (count < WINDOW_DEFS[w].inflowCount) {
                break;
            }
            counters.put(key, 0);
            double avgRate = averageRate(getOrCreate(seriesByWindow.get(w), key).getLastN(WINDOW_DEFS[w].inflowCount));
            AEMonitorSample derived = new AEMonitorSample(source.timeMs, source.tick, source.amount, avgRate);
            getOrCreate(seriesByWindow.get(w + 1), key).add(derived);
            source = derived;
        }

        lastSampleTick.put(key, tick);
    }

    private double averageRate(List<AEMonitorSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (AEMonitorSample s : samples) {
            sum += s.rate;
        }
        return sum / samples.size();
    }

    /**
     * 采样锁：每个 key 独立。距离上次采样 ≥ intervalTicks 才允许触发新采样。
     *
     * @param key           被监视物品/流体的字符串标识
     * @param tick          当前世界 tick
     * @param intervalTicks 采样间隔（tick），小于等于 0 时按 100 ticks 兜底
     * @return true=获得锁并已更新 lastSampleTick；false=尚未到下一次采样时间
     */
    public boolean tryAcquireSampleLock(String key, long tick, int intervalTicks) {
        long interval = intervalTicks > 0 ? intervalTicks : 100L;
        Long last = lastSampleTick.get(key);
        if (last == null || tick - last.longValue() >= interval) {
            lastSampleTick.put(key, tick);
            return true;
        }
        return false;
    }

    /**
     * 计算指定 key 在 5 分钟窗口内的平均变化率（amount / minute）。
     * <p>
     * 使用 {@code series5m} 窗口的首尾两个采样点：
     * {@code (last.amount - first.amount) / (last.tick - first.tick) * 1200.0}。
     * 其中 1200 = 20 ticks/秒 × 60 秒/分钟，将 tick 差转换为分钟。
     *
     * @param key 被监视物品/流体的字符串标识
     * @return 平均变化率（每分钟数量变化）；样本不足或 tick 差非正则返回 0.0
     */
    public double averageRate300s(String key) {
        AEMonitorWindowSeries series = seriesByWindow.get(WINDOW_5_MIN)
            .get(key);
        if (series == null || series.size() < 2) {
            return 0.0D;
        }
        List<AEMonitorSample> samples = series.copy();
        AEMonitorSample first = samples.get(0);
        AEMonitorSample last = samples.get(samples.size() - 1);
        long tickDiff = last.tick - first.tick;
        if (tickDiff <= 0L) {
            return 0.0D;
        }
        return (last.amount - first.amount) / (double) tickDiff * 1200.0D;
    }

    /**
     * 查询指定 key 与窗口的数据集副本。
     *
     * @param key    被监视物品/流体的字符串标识
     * @param window 窗口常量（WINDOW_5_MIN / WINDOW_1_HOUR / WINDOW_8_HOUR / WINDOW_24_HOUR / WINDOW_7_DAY / WINDOW_1_MONTH
     *               / WINDOW_3_MONTH / WINDOW_1_YEAR）
     * @return 该窗口的 ArrayList 副本；key 不存在返回空列表
     */
    public List<AEMonitorSample> query(String key, int window) {
        AEMonitorWindowSeries series = seriesByWindow.get(indexOf(window))
            .get(key);
        return series == null ? new ArrayList<>() : series.copy();
    }

    /**
     * 取指定 key 的最新采样点（5m 集的最新点）。
     *
     * @param key 被监视物品/流体的字符串标识
     * @return 最新点；key 不存在或空集返回 null
     */
    public AEMonitorSample newest(String key) {
        AEMonitorWindowSeries series = seriesByWindow.get(WINDOW_5_MIN)
            .get(key);
        return series == null ? null : series.newest();
    }

    /**
     * 查询指定 key 与窗口的当前样本数。
     */
    public int size(String key, int window) {
        AEMonitorWindowSeries series = seriesByWindow.get(indexOf(window))
            .get(key);
        return series == null ? 0 : series.size();
    }

    /**
     * 清空指定 key 的所有窗口、计数器与采样锁，释放内存。
     */
    public void clear(String key) {
        for (int w = 0; w < WINDOW_COUNT; w++) {
            seriesByWindow.get(w)
                .remove(key);
        }
        for (int w = 0; w + 1 < WINDOW_COUNT; w++) {
            counterByWindow.get(w)
                .remove(key);
        }
        lastSampleTick.remove(key);
    }

    /**
     * 清空全部 key。
     */
    public void clear() {
        for (int w = 0; w < WINDOW_COUNT; w++) {
            seriesByWindow.get(w)
                .clear();
        }
        for (int w = 0; w + 1 < WINDOW_COUNT; w++) {
            counterByWindow.get(w)
                .clear();
        }
        lastSampleTick.clear();
    }

    /**
     * 获取当前所有 key 的集合（以 5m 集为准：每个 addSample 的 key 必先入 5m 集）。
     */
    public Set<String> getKeys() {
        return new HashSet<>(
            seriesByWindow.get(WINDOW_5_MIN)
                .keySet());
    }

    /**
     * NBT 序列化：每个 key 一个 entry，包含 8 个 series、7 个 counter 与 lastSampleTick。
     * 键写入顺序（series 5m→1Y、counter 5m→3M、lastSampleTick）与 O2-13 前逐字段版一致。
     */
    public NBTTagCompound toNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (String key : getKeys()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("key", key);

            NBTTagCompound data = new NBTTagCompound();
            for (int w = 0; w < WINDOW_COUNT; w++) {
                AEMonitorWindowSeries series = seriesByWindow.get(w)
                    .get(key);
                if (series != null) {
                    data.setTag(WINDOW_DEFS[w].seriesKey, series.toNBT());
                }
            }
            for (int w = 0; w + 1 < WINDOW_COUNT; w++) {
                data.setInteger(WINDOW_DEFS[w].counterKey, getCounter(counterByWindow.get(w), key));
            }
            Long lastTick = lastSampleTick.get(key);
            data.setLong("lastSampleTick", lastTick == null ? -1L : lastTick.longValue());

            entry.setTag("data", data);
            list.appendTag(entry);
        }
        tag.setTag("keys", list);
        return tag;
    }

    /**
     * NBT 反序列化：读取 keys 列表，恢复每个 key 的 8 个 series、7 个 counter 与 lastSampleTick。
     * <p>
     * v1.5.17 向后兼容：旧存档（4 窗口）无 series7d/1M/3M/1Y 与 counter24h/7d/1M/3M 键时，
     * 通过 hasKey 守卫跳过 series 读取、getInteger 缺省返回 0，确保旧存档可正常加载。
     *
     * @param tag 待读取 NBT；null 直接返回
     */
    public void readFromNBT(NBTTagCompound tag) {
        clear();
        if (tag == null) {
            return;
        }
        NBTTagList list = tag.getTagList("keys", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            String key = entry.getString("key");
            if (key == null || key.isEmpty()) {
                continue;
            }
            NBTTagCompound data = entry.getCompoundTag("data");

            for (int w = 0; w < WINDOW_COUNT; w++) {
                if (data.hasKey(WINDOW_DEFS[w].seriesKey)) {
                    getOrCreate(seriesByWindow.get(w), key).readFromNBT(data.getCompoundTag(WINDOW_DEFS[w].seriesKey));
                }
            }
            for (int w = 0; w + 1 < WINDOW_COUNT; w++) {
                counterByWindow.get(w)
                    .put(key, data.getInteger(WINDOW_DEFS[w].counterKey));
            }
            lastSampleTick.put(key, data.getLong("lastSampleTick"));
        }
    }
}
