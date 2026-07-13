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

    private final Map<String, AEMonitorWindowSeries> series5m = new HashMap<>();
    private final Map<String, AEMonitorWindowSeries> series1h = new HashMap<>();
    private final Map<String, AEMonitorWindowSeries> series8h = new HashMap<>();
    private final Map<String, AEMonitorWindowSeries> series24h = new HashMap<>();
    // v1.5.17：新增 4 个高级窗口 series
    private final Map<String, AEMonitorWindowSeries> series7d = new HashMap<>();
    private final Map<String, AEMonitorWindowSeries> series1M = new HashMap<>();
    private final Map<String, AEMonitorWindowSeries> series3M = new HashMap<>();
    private final Map<String, AEMonitorWindowSeries> series1Y = new HashMap<>();

    private final Map<String, Integer> counter5m = new HashMap<>();
    private final Map<String, Integer> counter1h = new HashMap<>();
    private final Map<String, Integer> counter8h = new HashMap<>();
    // v1.5.17：24h 不再是终端，新增后续计数器
    private final Map<String, Integer> counter24h = new HashMap<>(); // 满 7 触发 7d 流入
    private final Map<String, Integer> counter7d = new HashMap<>(); // 满 4 触发 1M 流入
    private final Map<String, Integer> counter1M = new HashMap<>(); // 满 3 触发 3M 流入
    private final Map<String, Integer> counter3M = new HashMap<>(); // 满 4 触发 1Y 流入

    // 每个 key 独立的采样锁（world tick），距离上次采样 ≥ 100 ticks 才允许新采样
    private final Map<String, Long> lastSampleTick = new HashMap<>();

    private AEMonitorWindowSeries getOrCreate(Map<String, AEMonitorWindowSeries> map, String key) {
        AEMonitorWindowSeries series = map.get(key);
        if (series == null) {
            series = new AEMonitorWindowSeries();
            map.put(key, series);
        }
        return series;
    }

    private int getCounter(Map<String, Integer> map, String key) {
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
        getOrCreate(series5m, key).add(sample);
        counter5m.put(key, getCounter(counter5m, key) + 1);

        if (getCounter(counter5m, key) >= 12) {
            counter5m.put(key, 0);
            double avgRate1h = averageRate(getOrCreate(series5m, key).getLastN(12));
            AEMonitorSample sample1h = new AEMonitorSample(sample.timeMs, sample.tick, sample.amount, avgRate1h);
            getOrCreate(series1h, key).add(sample1h);
            counter1h.put(key, getCounter(counter1h, key) + 1);

            if (getCounter(counter1h, key) >= 8) {
                counter1h.put(key, 0);
                double avgRate8h = averageRate(getOrCreate(series1h, key).getLastN(8));
                AEMonitorSample sample8h = new AEMonitorSample(
                    sample1h.timeMs,
                    sample1h.tick,
                    sample1h.amount,
                    avgRate8h);
                getOrCreate(series8h, key).add(sample8h);
                counter8h.put(key, getCounter(counter8h, key) + 1);

                if (getCounter(counter8h, key) >= 3) {
                    counter8h.put(key, 0);
                    double avgRate24h = averageRate(getOrCreate(series8h, key).getLastN(3));
                    AEMonitorSample sample24h = new AEMonitorSample(
                        sample8h.timeMs,
                        sample8h.tick,
                        sample8h.amount,
                        avgRate24h);
                    getOrCreate(series24h, key).add(sample24h);
                    // v1.5.17：24h 不再是终端，继续向 7d / 1M / 3M / 1Y 流入
                    counter24h.put(key, getCounter(counter24h, key) + 1);
                    if (getCounter(counter24h, key) >= 7) {
                        // 触发 7d 集：rate 取最近 7 个 24h 点的均值（嵌套均值）
                        counter24h.put(key, 0);
                        double avgRate7d = averageRate(getOrCreate(series24h, key).getLastN(7));
                        AEMonitorSample sample7d = new AEMonitorSample(
                            sample24h.timeMs,
                            sample24h.tick,
                            sample24h.amount,
                            avgRate7d);
                        getOrCreate(series7d, key).add(sample7d);
                        counter7d.put(key, getCounter(counter7d, key) + 1);
                        if (getCounter(counter7d, key) >= 4) {
                            // 触发 1M 集：rate 取最近 4 个 7d 点的均值（嵌套均值）
                            counter7d.put(key, 0);
                            double avgRate1M = averageRate(getOrCreate(series7d, key).getLastN(4));
                            AEMonitorSample sample1M = new AEMonitorSample(
                                sample7d.timeMs,
                                sample7d.tick,
                                sample7d.amount,
                                avgRate1M);
                            getOrCreate(series1M, key).add(sample1M);
                            counter1M.put(key, getCounter(counter1M, key) + 1);
                            if (getCounter(counter1M, key) >= 3) {
                                // 触发 3M 集：rate 取最近 3 个 1M 点的均值（嵌套均值）
                                counter1M.put(key, 0);
                                double avgRate3M = averageRate(getOrCreate(series1M, key).getLastN(3));
                                AEMonitorSample sample3M = new AEMonitorSample(
                                    sample1M.timeMs,
                                    sample1M.tick,
                                    sample1M.amount,
                                    avgRate3M);
                                getOrCreate(series3M, key).add(sample3M);
                                counter3M.put(key, getCounter(counter3M, key) + 1);
                                if (getCounter(counter3M, key) >= 4) {
                                    // 触发 1Y 集：rate 取最近 4 个 3M 点的均值（嵌套均值）
                                    counter3M.put(key, 0);
                                    double avgRate1Y = averageRate(getOrCreate(series3M, key).getLastN(4));
                                    AEMonitorSample sample1Y = new AEMonitorSample(
                                        sample3M.timeMs,
                                        sample3M.tick,
                                        sample3M.amount,
                                        avgRate1Y);
                                    getOrCreate(series1Y, key).add(sample1Y);
                                }
                            }
                        }
                    }
                }
            }
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
        AEMonitorWindowSeries series = series5m.get(key);
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
        AEMonitorWindowSeries series;
        switch (window) {
            case WINDOW_1_HOUR:
                series = series1h.get(key);
                break;
            case WINDOW_8_HOUR:
                series = series8h.get(key);
                break;
            case WINDOW_24_HOUR:
                series = series24h.get(key);
                break;
            case WINDOW_7_DAY:
                series = series7d.get(key);
                break;
            case WINDOW_1_MONTH:
                series = series1M.get(key);
                break;
            case WINDOW_3_MONTH:
                series = series3M.get(key);
                break;
            case WINDOW_1_YEAR:
                series = series1Y.get(key);
                break;
            case WINDOW_5_MIN:
            default:
                series = series5m.get(key);
                break;
        }
        return series == null ? new ArrayList<>() : series.copy();
    }

    /**
     * 取指定 key 的最新采样点（5m 集的最新点）。
     *
     * @param key 被监视物品/流体的字符串标识
     * @return 最新点；key 不存在或空集返回 null
     */
    public AEMonitorSample newest(String key) {
        AEMonitorWindowSeries series = series5m.get(key);
        return series == null ? null : series.newest();
    }

    /**
     * 查询指定 key 与窗口的当前样本数。
     */
    public int size(String key, int window) {
        AEMonitorWindowSeries series;
        switch (window) {
            case WINDOW_1_HOUR:
                series = series1h.get(key);
                break;
            case WINDOW_8_HOUR:
                series = series8h.get(key);
                break;
            case WINDOW_24_HOUR:
                series = series24h.get(key);
                break;
            case WINDOW_7_DAY:
                series = series7d.get(key);
                break;
            case WINDOW_1_MONTH:
                series = series1M.get(key);
                break;
            case WINDOW_3_MONTH:
                series = series3M.get(key);
                break;
            case WINDOW_1_YEAR:
                series = series1Y.get(key);
                break;
            case WINDOW_5_MIN:
            default:
                series = series5m.get(key);
                break;
        }
        return series == null ? 0 : series.size();
    }

    /**
     * 清空指定 key 的所有窗口、计数器与采样锁，释放内存。
     */
    public void clear(String key) {
        series5m.remove(key);
        series1h.remove(key);
        series8h.remove(key);
        series24h.remove(key);
        // v1.5.17：清空 4 个新增窗口 series
        series7d.remove(key);
        series1M.remove(key);
        series3M.remove(key);
        series1Y.remove(key);
        counter5m.remove(key);
        counter1h.remove(key);
        counter8h.remove(key);
        // v1.5.17：清空 4 个新增计数器
        counter24h.remove(key);
        counter7d.remove(key);
        counter1M.remove(key);
        counter3M.remove(key);
        lastSampleTick.remove(key);
    }

    /**
     * 清空全部 key。
     */
    public void clear() {
        series5m.clear();
        series1h.clear();
        series8h.clear();
        series24h.clear();
        // v1.5.17：清空 4 个新增窗口 series
        series7d.clear();
        series1M.clear();
        series3M.clear();
        series1Y.clear();
        counter5m.clear();
        counter1h.clear();
        counter8h.clear();
        // v1.5.17：清空 4 个新增计数器
        counter24h.clear();
        counter7d.clear();
        counter1M.clear();
        counter3M.clear();
        lastSampleTick.clear();
    }

    /**
     * 获取当前所有 key 的集合。
     */
    public Set<String> getKeys() {
        return new HashSet<>(series5m.keySet());
    }

    /**
     * NBT 序列化：每个 key 一个 entry，包含 8 个 series、7 个 counter 与 lastSampleTick。
     */
    public NBTTagCompound toNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (String key : getKeys()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("key", key);

            NBTTagCompound data = new NBTTagCompound();
            AEMonitorWindowSeries s5m = series5m.get(key);
            AEMonitorWindowSeries s1h = series1h.get(key);
            AEMonitorWindowSeries s8h = series8h.get(key);
            AEMonitorWindowSeries s24h = series24h.get(key);
            // v1.5.17：新增 4 个高级窗口 series
            AEMonitorWindowSeries s7d = series7d.get(key);
            AEMonitorWindowSeries s1M = series1M.get(key);
            AEMonitorWindowSeries s3M = series3M.get(key);
            AEMonitorWindowSeries s1Y = series1Y.get(key);
            if (s5m != null) data.setTag("series5m", s5m.toNBT());
            if (s1h != null) data.setTag("series1h", s1h.toNBT());
            if (s8h != null) data.setTag("series8h", s8h.toNBT());
            if (s24h != null) data.setTag("series24h", s24h.toNBT());
            if (s7d != null) data.setTag("series7d", s7d.toNBT());
            if (s1M != null) data.setTag("series1M", s1M.toNBT());
            if (s3M != null) data.setTag("series3M", s3M.toNBT());
            if (s1Y != null) data.setTag("series1Y", s1Y.toNBT());
            data.setInteger("counter5m", getCounter(counter5m, key));
            data.setInteger("counter1h", getCounter(counter1h, key));
            data.setInteger("counter8h", getCounter(counter8h, key));
            // v1.5.17：持久化 4 个新增计数器
            data.setInteger("counter24h", getCounter(counter24h, key));
            data.setInteger("counter7d", getCounter(counter7d, key));
            data.setInteger("counter1M", getCounter(counter1M, key));
            data.setInteger("counter3M", getCounter(counter3M, key));
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

            if (data.hasKey("series5m")) {
                getOrCreate(series5m, key).readFromNBT(data.getCompoundTag("series5m"));
            }
            if (data.hasKey("series1h")) {
                getOrCreate(series1h, key).readFromNBT(data.getCompoundTag("series1h"));
            }
            if (data.hasKey("series8h")) {
                getOrCreate(series8h, key).readFromNBT(data.getCompoundTag("series8h"));
            }
            if (data.hasKey("series24h")) {
                getOrCreate(series24h, key).readFromNBT(data.getCompoundTag("series24h"));
            }
            // v1.5.17：读取 4 个新增窗口 series；旧存档无此键时跳过（series 保持空）
            if (data.hasKey("series7d")) {
                getOrCreate(series7d, key).readFromNBT(data.getCompoundTag("series7d"));
            }
            if (data.hasKey("series1M")) {
                getOrCreate(series1M, key).readFromNBT(data.getCompoundTag("series1M"));
            }
            if (data.hasKey("series3M")) {
                getOrCreate(series3M, key).readFromNBT(data.getCompoundTag("series3M"));
            }
            if (data.hasKey("series1Y")) {
                getOrCreate(series1Y, key).readFromNBT(data.getCompoundTag("series1Y"));
            }
            counter5m.put(key, data.getInteger("counter5m"));
            counter1h.put(key, data.getInteger("counter1h"));
            counter8h.put(key, data.getInteger("counter8h"));
            // v1.5.17：读取 4 个新增计数器；旧存档无此键时 getInteger 返回 0（符合预期）
            counter24h.put(key, data.getInteger("counter24h"));
            counter7d.put(key, data.getInteger("counter7d"));
            counter1M.put(key, data.getInteger("counter1M"));
            counter3M.put(key, data.getInteger("counter3M"));
            lastSampleTick.put(key, data.getLong("lastSampleTick"));
        }
    }
}
