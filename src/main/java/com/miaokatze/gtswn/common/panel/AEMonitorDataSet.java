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
 * AE 监视数据集（每网络信息屏一份，每个被监视物品/流体独立维护 4 窗口 × 61 点 FIFO）。
 * <p>
 * key 为字符串标识（{@code item:<registryName>:<meta>} 或 {@code fluid:<fluidName>}）。
 * 4 个独立 {@link AEMonitorWindowSeries} 分别承载 5m / 1h / 8h / 24h 时间窗口，
 * 原始采样点按"流入计数链"分发到各级窗口（均值录入）：
 * <ul>
 * <li>5m 集：每接收 1 个采样点都以瞬时值录入 5m 集</li>
 * <li>1h 集：每 12 个 5m 采样触发一次，rate 取最近 12 个 5m 点的算术均值，amount/timeMs/tick 用触发点瞬时值</li>
 * <li>8h 集：每 8 个 1h 采样触发一次，rate 取最近 8 个 1h 点的算术均值（嵌套均值），amount/timeMs/tick 用触发点 1h 瞬时值</li>
 * <li>24h 集：每 3 个 8h 采样触发一次，rate 取最近 3 个 8h 点的算术均值（嵌套均值），amount/timeMs/tick 用触发点 8h 瞬时值</li>
 * </ul>
 */
public class AEMonitorDataSet {

    public static final int WINDOW_5_MIN = 0;
    public static final int WINDOW_1_HOUR = 1;
    public static final int WINDOW_8_HOUR = 2;
    public static final int WINDOW_24_HOUR = 3;

    private final Map<String, AEMonitorWindowSeries> series5m = new HashMap<>();
    private final Map<String, AEMonitorWindowSeries> series1h = new HashMap<>();
    private final Map<String, AEMonitorWindowSeries> series8h = new HashMap<>();
    private final Map<String, AEMonitorWindowSeries> series24h = new HashMap<>();

    private final Map<String, Integer> counter5m = new HashMap<>();
    private final Map<String, Integer> counter1h = new HashMap<>();
    private final Map<String, Integer> counter8h = new HashMap<>();

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
     * 采样锁：每个 key 独立。距离上次采样 ≥ 100 ticks 才允许触发新采样。
     *
     * @param key  被监视物品/流体的字符串标识
     * @param tick 当前世界 tick
     * @return true=获得锁并已更新 lastSampleTick；false=尚未到下一次采样时间
     */
    public boolean tryAcquireSampleLock(String key, long tick) {
        Long last = lastSampleTick.get(key);
        if (last == null || tick - last.longValue() >= 100L) {
            lastSampleTick.put(key, tick);
            return true;
        }
        return false;
    }

    /**
     * 查询指定 key 与窗口的数据集副本。
     *
     * @param key    被监视物品/流体的字符串标识
     * @param window 窗口常量（WINDOW_5_MIN / WINDOW_1_HOUR / WINDOW_8_HOUR / WINDOW_24_HOUR）
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
        counter5m.remove(key);
        counter1h.remove(key);
        counter8h.remove(key);
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
        counter5m.clear();
        counter1h.clear();
        counter8h.clear();
        lastSampleTick.clear();
    }

    /**
     * 获取当前所有 key 的集合。
     */
    public Set<String> getKeys() {
        return new HashSet<>(series5m.keySet());
    }

    /**
     * NBT 序列化：每个 key 一个 entry，包含 4 个 series、3 个 counter 与 lastSampleTick。
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
            if (s5m != null) data.setTag("series5m", s5m.toNBT());
            if (s1h != null) data.setTag("series1h", s1h.toNBT());
            if (s8h != null) data.setTag("series8h", s8h.toNBT());
            if (s24h != null) data.setTag("series24h", s24h.toNBT());
            data.setInteger("counter5m", getCounter(counter5m, key));
            data.setInteger("counter1h", getCounter(counter1h, key));
            data.setInteger("counter8h", getCounter(counter8h, key));
            Long lastTick = lastSampleTick.get(key);
            data.setLong("lastSampleTick", lastTick == null ? -1L : lastTick.longValue());

            entry.setTag("data", data);
            list.appendTag(entry);
        }
        tag.setTag("keys", list);
        return tag;
    }

    /**
     * NBT 反序列化：读取 keys 列表，恢复每个 key 的 4 个 series、3 个 counter 与 lastSampleTick。
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
            counter5m.put(key, data.getInteger("counter5m"));
            counter1h.put(key, data.getInteger("counter1h"));
            counter8h.put(key, data.getInteger("counter8h"));
            lastSampleTick.put(key, data.getLong("lastSampleTick"));
        }
    }
}
