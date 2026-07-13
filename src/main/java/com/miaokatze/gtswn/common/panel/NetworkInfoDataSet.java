package com.miaokatze.gtswn.common.panel;

import java.math.BigInteger;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtswn.common.util.EUDataSet;

/**
 * 网络信息屏数据集（每玩家一份，4 窗口 × 61 点 FIFO）。
 * <p>
 * 4 个独立 {@link NetworkInfoWindowSeries} 分别承载 5m / 1h / 8h / 24h 时间窗口，
 * 每个窗口固定保留 61 个采样点。原始采样点按"流入计数链"分发到各级窗口（均值录入）：
 * <ul>
 * <li>5m 集：每接收 1 个采样点都以瞬时值录入 5m 集</li>
 * <li>1h 集：每 12 个 5m 采样触发一次，eut 取最近 12 个 5m 点的算术均值，eu/timeMs/tick 用触发点瞬时值</li>
 * <li>8h 集：每 8 个 1h 采样触发一次，eut 取最近 8 个 1h 点的算术均值（嵌套均值），eu/timeMs/tick 用触发点 1h 瞬时值</li>
 * <li>24h 集：每 3 个 8h 采样触发一次，eut 取最近 3 个 8h 点的算术均值（嵌套均值），eu/timeMs/tick 用触发点 8h 瞬时值</li>
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
     * 局部 EU/t 计算数据集（per-player 共享，从 TileEntity 迁移至此）。
     * <p>
     * 用于记录原始 EU 采样值并计算瞬时 EU/t 斜率，替代原 TileEntityNetworkInfoPanel 中的局部 eutDataSet。
     * 随 NetworkInfoDataSet 一起持久化到 overworld 的 NetworkInfoDataStore，实现多屏共享。
     */
    private final EUDataSet eutDataSet = new EUDataSet();

    // 最近一次采样的真实时间戳（System.currentTimeMillis，v1.5.15 新增）。
    // 与 lastSampleTick（world tick）不同：world tick 在每次会话重置为 0，无法跨会话比较；
    // 此字段使用真实时间，用于 cleanupStale 判断玩家是否长期未活跃，从而清理其数据集释放内存。
    private long lastSampleTimeMs = 0L;

    /**
     * 主入口：接收一个原始采样点（每 5s 一次），按流入计数链分发到各级窗口（均值录入）。
     * <p>
     * v1.5.16 改造：eut 不再由调用方传入，改为内部通过 {@link EUDataSet} 计算瞬时斜率。
     * 调用流程：先 eutDataSet.add(eu, tick) 记录原始值，再 calculateRecentEUT() 取最近两点斜率作为瞬时 EU/t。
     * <p>
     * 计数链规则（均值录入）：5m 集始终以瞬时值录入；每 12 个 5m 点触发 1h 录入——eut 取最近 12 个 5m 点均值，
     * eu/timeMs/tick 用当前触发点瞬时值；同理 1h→8h 每 8 个（eut 取最近 8 个 1h 点均值）、8h→24h 每 3 个（eut 取最近 3 个 8h 点均值）。
     * 多屏共享时去重：同一玩家的所有信息屏共享同一份数据集，调度器保证每 100t 只采样一次。
     *
     * @param eu     当前 EU 总量
     * @param tick   世界 tick
     * @param timeMs 真实时间戳（毫秒）
     */
    public void addSample(BigInteger eu, long tick, long timeMs) {
        // v1.5.15：记录真实时间戳，供 cleanupStale 跨会话判断玩家活跃度
        this.lastSampleTimeMs = System.currentTimeMillis();
        // v1.5.16：先记录到 EU/t 数据集（per-player 共享，从 TileEntity 迁移至此）
        eutDataSet.add(eu, tick);
        // 计算瞬时 EU/t（基于最近两个采样点的斜率）
        double eut = eutDataSet.calculateRecentEUT();
        // 5m 集始终以瞬时值录入（行为不变）
        NetworkInfoSample sample = new NetworkInfoSample(timeMs, tick, eu, eut);
        series5m.add(sample);
        counter5m++;

        if (counter5m >= 12) {
            // 触发 1h 集：eut 取最近 12 个 5m 点的均值
            counter5m = 0;
            double avgEut1h = averageEut(series5m.getLastN(12));
            // eu/timeMs/tick 用当前触发点瞬时值，eut 用均值
            NetworkInfoSample sample1h = new NetworkInfoSample(sample.timeMs, sample.tick, sample.eu, avgEut1h);
            series1h.add(sample1h);
            counter1h++;

            if (counter1h >= 8) {
                // 触发 8h 集：eut 取最近 8 个 1h 点的均值（嵌套均值）
                counter1h = 0;
                double avgEut8h = averageEut(series1h.getLastN(8));
                // eu/timeMs/tick 用 sample1h 瞬时值，eut 用均值
                NetworkInfoSample sample8h = new NetworkInfoSample(
                    sample1h.timeMs,
                    sample1h.tick,
                    sample1h.eu,
                    avgEut8h);
                series8h.add(sample8h);
                counter8h++;

                if (counter8h >= 3) {
                    // 触发 24h 集：eut 取最近 3 个 8h 点的均值（嵌套均值）
                    counter8h = 0;
                    double avgEut24h = averageEut(series8h.getLastN(3));
                    // eu/timeMs/tick 用 sample8h 瞬时值，eut 用均值
                    NetworkInfoSample sample24h = new NetworkInfoSample(
                        sample8h.timeMs,
                        sample8h.tick,
                        sample8h.eu,
                        avgEut24h);
                    series24h.add(sample24h);
                }
            }
        }
    }

    /**
     * 计算列表中各 sample 的 eut 字段算术平均。
     * <p>
     * 用于"均值录入"：1h/8h/24h 集触发时，对上一级窗口最近 N 个点求 EU/t 均值。
     * 采用普通 for 循环累加（不使用 Stream API，与现有代码风格一致）。
     *
     * @param samples 采样点列表
     * @return eut 算术平均；samples 为 null 或空时返回 0.0（除零保护）
     */
    private double averageEut(List<NetworkInfoSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (NetworkInfoSample s : samples) {
            sum += s.eut;
        }
        return sum / samples.size();
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
     * v1.5.15：获取最近一次采样的真实时间戳（System.currentTimeMillis）。
     * <p>
     * 用于 cleanupStale 跨会话判断玩家活跃度，清理长期未采样的数据集。
     *
     * @return 真实时间戳；0=从未采样或旧存档无此字段
     */
    public long getLastSampleTimeMs() {
        return lastSampleTimeMs;
    }

    /**
     * 是否处于冷启动状态（数据点不足 2 个，无法计算斜率）。
     * <p>
     * 用于 TileEntity 格式化状态显示："冷启动中"。
     *
     * @return true 表示数据点 < 2
     */
    public boolean isColdStarting() {
        return eutDataSet.size() < 2;
    }

    /**
     * 是否处于长期静默状态（静默模式持续 ≥ 300s = 6000 ticks）。
     * <p>
     * 长期静默时数据集只保留首末两个数据点，显示标签切换为"长期静默"。
     *
     * @return true 表示长期静默
     */
    public boolean isLongTermSilent() {
        return eutDataSet.isLongTermSilent();
    }

    /**
     * 是否处于静默状态（≥2 点且所有 value 相同）。
     * <p>
     * 静默 = 所有采样点的 EU 值完全一致，但尚未达到长期静默阈值。
     *
     * @return true 表示静默（size ≥ 2 且值全部相同）
     */
    public boolean isSilent() {
        return eutDataSet.size() >= 2 && eutDataSet.isAllSameValue();
    }

    /**
     * 获取最近瞬时 EU/t（基于最近两个采样点的斜率）。
     * <p>
     * 用于 TileEntity 显示当前 EU/t 数值。
     *
     * @return 瞬时 EU/t；数据点 < 2 时返回 0.0
     */
    public double getRecentEUT() {
        return eutDataSet.calculateRecentEUT();
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
        // v1.5.16：清空 EU/t 测量历史（per-player 共享，从 TileEntity 迁移至此）
        eutDataSet.clear();
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
     * NBT 序列化：写 4 个 series compound + 3 个 counter + lastSampleTick + lastSampleTimeMs。
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
        // v1.5.15：持久化真实时间戳，跨会话判断玩家活跃度
        tag.setLong("lastSampleTimeMs", lastSampleTimeMs);
        // v1.5.16：持久化 EU/t 测量历史（per-player 共享，从 TileEntity 迁移至此）
        // saveToNBT 向 tag 写入子键 "eutMeasurementHistory"，不会覆盖其他字段
        eutDataSet.saveToNBT(tag, "eutMeasurementHistory");
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
        // v1.5.15：读取真实时间戳；旧存档无此 key 时返回 0（视为远古数据，将被 cleanupStale 清理）
        lastSampleTimeMs = tag.getLong("lastSampleTimeMs");
        // v1.5.16：读取 EU/t 测量历史（per-player 共享，从 TileEntity 迁移至此）
        // 旧存档无此 key 时跳过，eutDataSet 保持空（冷启动）
        if (tag.hasKey("eutMeasurementHistory")) {
            eutDataSet.loadFromNBT(tag, "eutMeasurementHistory");
        }
        // 旧格式（"samples" 键）忽略，数据集保持空（旧数据丢弃，不迁移）
    }
}
