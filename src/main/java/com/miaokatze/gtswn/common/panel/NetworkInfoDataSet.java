package com.miaokatze.gtswn.common.panel;

import java.math.BigInteger;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

/**
 * 网络信息屏数据集（每玩家一份，4 窗口 × 61 点 FIFO）。
 * <p>
 * 4 个独立 {@link NetworkInfoWindowSeries} 分别承载 5m / 1h / 8h / 24h 时间窗口，
 * 每个窗口固定保留 61 个采样点。原始采样点按"流入计数链"分发到各级窗口：
 * <ul>
 * <li>5m 集：每接收 1 个采样点都进入 5m 集</li>
 * <li>1h 集：每 12 个 5m 采样的第 12 个点同时进入 1h 集</li>
 * <li>8h 集：每 8 个 1h 采样的第 8 个点同时进入 8h 集</li>
 * <li>24h 集：每 3 个 8h 采样的第 3 个点同时进入 24h 集</li>
 * </ul>
 * 多屏共享：同一玩家的所有信息屏共享同一份数据集（key=ownerUUID 字符串）。
 */
public class NetworkInfoDataSet {

    public static final int WINDOW_5_MIN = 0;
    public static final int WINDOW_1_HOUR = 1;
    public static final int WINDOW_8_HOUR = 2;
    public static final int WINDOW_24_HOUR = 3;

    // 各窗口对应的时长（毫秒），仅供 GUI 显示与坐标轴标签使用，不再用于桶化压缩
    private static final long FIVE_MIN_MS = 5L * 60L * 1000L;
    private static final long ONE_HOUR_MS = 60L * 60L * 1000L;
    private static final long EIGHT_HOUR_MS = 8L * 60L * 60L * 1000L;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    // 4 个独立 61 点 FIFO 数据集
    private final NetworkInfoWindowSeries series5m = new NetworkInfoWindowSeries();
    private final NetworkInfoWindowSeries series1h = new NetworkInfoWindowSeries();
    private final NetworkInfoWindowSeries series8h = new NetworkInfoWindowSeries();
    private final NetworkInfoWindowSeries series24h = new NetworkInfoWindowSeries();

    // 流入计数链计数器（0..阈值-1）
    private int counter5m = 0; // 满 12 触发 1h 流入
    private int counter1h = 0; // 满 8 触发 8h 流入
    private int counter8h = 0; // 满 3 触发 24h 流入

    // 全局采样锁（用 world tick）：多屏共享时去重，距离上次采样 ≥ 100 ticks (5s) 才允许新采样
    private long lastSampleTick = -1L;

    /**
     * 主入口：接收一个原始采样点（每 5s 一次），按流入计数链分发到各级窗口。
     * <p>
     * 计数链规则（用户确认）：第 N 个 5m 采样的"第 12 个"同时进入 5m 集和 1h 集（5m 集不跳过此点）；
     * 同理 1h→8h 每 8 个、8h→24h 每 3 个。
     *
     * @param eu     当前 EU 总量
     * @param tick   世界 tick
     * @param timeMs 真实时间戳（毫秒）
     * @param eut    当前 EU/t 斜率
     */
    public void addSample(BigInteger eu, long tick, long timeMs, double eut) {
        NetworkInfoSample sample = new NetworkInfoSample(timeMs, tick, eu, eut);
        series5m.add(sample); // 始终进入 5m 集
        counter5m++;
        if (counter5m >= 12) { // 第 12 个 5m 采样同时进入 1h 集
            series1h.add(sample);
            counter5m = 0;
            counter1h++;
            if (counter1h >= 8) { // 第 8 个 1h 采样同时进入 8h 集
                series8h.add(sample);
                counter1h = 0;
                counter8h++;
                if (counter8h >= 3) { // 第 3 个 8h 采样同时进入 24h 集
                    series24h.add(sample);
                    counter8h = 0;
                }
            }
        }
    }

    /**
     * 采样锁：多屏共享时去重。距离上次采样 ≥ 100 ticks (5s) 才允许触发新采样。
     * <p>
     * 锁状态记录在数据集层面（同一玩家共享），保证多屏不会重复采样。
     *
     * @param currentTick 当前世界 tick
     * @return true=获得锁并已更新 lastSampleTick；false=尚未到下一次采样时间
     */
    public boolean tryAcquireSampleLock(long currentTick) {
        if (lastSampleTick < 0L || currentTick - lastSampleTick >= 100L) {
            lastSampleTick = currentTick;
            return true;
        }
        return false;
    }

    /**
     * 查询指定窗口的数据集副本。
     *
     * @param window 窗口常量（WINDOW_5_MIN / WINDOW_1_HOUR / WINDOW_8_HOUR / WINDOW_24_HOUR）
     * @return 该窗口的 ArrayList 副本（渲染线程安全读取）
     */
    public List<NetworkInfoSample> query(int window) {
        switch (window) {
            case WINDOW_1_HOUR:
                return series1h.copy();
            case WINDOW_8_HOUR:
                return series8h.copy();
            case WINDOW_24_HOUR:
                return series24h.copy();
            case WINDOW_5_MIN:
            default:
                return series5m.copy();
        }
    }

    /**
     * 取最新采样点（5m 集的最新点 = 全局最新点）。
     * <p>
     * 用于多屏共享时反写 cachedEu/cachedEut，确保多屏显示完全一致。
     *
     * @return 最新点；空集返回 null
     */
    public NetworkInfoSample newest() {
        return series5m.newest();
    }

    /**
     * 查询指定窗口的当前样本数。
     */
    public int size(int window) {
        switch (window) {
            case WINDOW_1_HOUR:
                return series1h.size();
            case WINDOW_8_HOUR:
                return series8h.size();
            case WINDOW_24_HOUR:
                return series24h.size();
            case WINDOW_5_MIN:
            default:
                return series5m.size();
        }
    }

    /**
     * 清空所有窗口与计数器、采样锁。
     */
    public void clear() {
        series5m.clear();
        series1h.clear();
        series8h.clear();
        series24h.clear();
        counter5m = 0;
        counter1h = 0;
        counter8h = 0;
        lastSampleTick = -1L;
    }

    /**
     * 窗口常量转毫秒（仅供 GUI 显示与坐标轴标签使用）。
     */
    public static long windowToMillis(int window) {
        switch (window) {
            case WINDOW_1_HOUR:
                return ONE_HOUR_MS;
            case WINDOW_8_HOUR:
                return EIGHT_HOUR_MS;
            case WINDOW_24_HOUR:
                return DAY_MS;
            case WINDOW_5_MIN:
            default:
                return FIVE_MIN_MS;
        }
    }

    /**
     * NBT 序列化：写 4 个 series compound + 3 个 counter + lastSampleTick。
     */
    public NBTTagCompound toNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("series5m", series5m.toNBT());
        tag.setTag("series1h", series1h.toNBT());
        tag.setTag("series8h", series8h.toNBT());
        tag.setTag("series24h", series24h.toNBT());
        tag.setInteger("counter5m", counter5m);
        tag.setInteger("counter1h", counter1h);
        tag.setInteger("counter8h", counter8h);
        tag.setLong("lastSampleTick", lastSampleTick);
        return tag;
    }

    /**
     * NBT 反序列化：读取 4 个 series + 3 个 counter + lastSampleTick。
     * <p>
     * 旧格式（"samples" 键）不匹配 → 数据集保持空（架构变更较大，旧 datasetId-keyed 数据无法适配新机制，直接丢弃）。
     *
     * @param tag 待读取 NBT；null 直接返回
     */
    public void readFromNBT(NBTTagCompound tag) {
        clear();
        if (tag == null) {
            return;
        }
        // 新格式：4 个 series compound
        if (tag.hasKey("series5m")) {
            series5m.readFromNBT(tag.getCompoundTag("series5m"));
        }
        if (tag.hasKey("series1h")) {
            series1h.readFromNBT(tag.getCompoundTag("series1h"));
        }
        if (tag.hasKey("series8h")) {
            series8h.readFromNBT(tag.getCompoundTag("series8h"));
        }
        if (tag.hasKey("series24h")) {
            series24h.readFromNBT(tag.getCompoundTag("series24h"));
        }
        counter5m = tag.getInteger("counter5m");
        counter1h = tag.getInteger("counter1h");
        counter8h = tag.getInteger("counter8h");
        lastSampleTick = tag.getLong("lastSampleTick");
        // 旧格式（"samples" 键）忽略，数据集保持空（旧数据丢弃，不迁移）
    }
}
