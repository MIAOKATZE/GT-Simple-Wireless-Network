package com.miaokatze.gtswn.common.panel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/**
 * AE 监视数据集（每网络信息屏一份，每个被监视物品/流体独立维护 8 窗口 × 61 点 FIFO）。
 * <p>
 * key 为数量无关的 v2 完整身份；旧无 descriptor 的 key 保留为 legacy，不猜测归属或拼接。
 * <p>
 * O2-14：每 key 的 8 窗口结构收敛为一条 {@link Chain}（{@link WindowChain} 骨架的 AE 薄壳，
 * 懒创建窗口），原 8 series Map + 7 counter Map 的并行结构改为 {@code Map<String, Chain>} 单维；
 * 流入计数链（5m→1h→8h→24h→7d→1M→3M→1Y，阈值 12/8/3/7/4/3/4，均值录入）与窗口 NBT
 * 编解码由骨架单源承载。本类保留 AE 侧自有状态：per-key 采样锁与 key 级 NBT entry 编排。
 * NBT 键名与 v1.5.17 hasKey 守卫逐字保留。
 */
public class AEMonitorDataSet {

    // 窗口常量别名（外部 AEMonitorDataSet.WINDOW_* 引用不变；单源定义在 WindowChain）
    public static final int WINDOW_5_MIN = WindowChain.WINDOW_5_MIN;
    public static final int WINDOW_1_HOUR = WindowChain.WINDOW_1_HOUR;
    public static final int WINDOW_8_HOUR = WindowChain.WINDOW_8_HOUR;
    public static final int WINDOW_24_HOUR = WindowChain.WINDOW_24_HOUR;
    // v1.5.17：拓展到 8 窗口，新增 7d / 1M / 3M / 1Y
    public static final int WINDOW_7_DAY = WindowChain.WINDOW_7_DAY;
    public static final int WINDOW_1_MONTH = WindowChain.WINDOW_1_MONTH;
    public static final int WINDOW_3_MONTH = WindowChain.WINDOW_3_MONTH;
    public static final int WINDOW_1_YEAR = WindowChain.WINDOW_1_YEAR;

    /** 窗口总数 */
    public static final int WINDOW_COUNT = WindowChain.WINDOW_COUNT;

    /** 单 key 采样链：WindowChain 骨架的 AE 薄壳（样本编解码 + 派生样本构造 + rate 聚合） */
    static final class Chain extends WindowChain<AEMonitorSample> {

        Chain() {
            // 懒创建：未触碰窗口不落容器对象，NBT 不写空 series 键（与原 if (s != null) 语义一致）
            super(false);
        }

        @Override
        protected AEMonitorSample createDerived(AEMonitorSample source, double aggregate) {
            // amount/timeMs/tick 用触发点瞬时值，rate 用上一级窗口最近 N 点均值
            return new AEMonitorSample(source.timeMs, source.tick, source.amount, aggregate);
        }

        @Override
        protected double aggregateOf(AEMonitorSample sample) {
            return sample.rate;
        }

        @Override
        protected NBTTagCompound sampleToNBT(AEMonitorSample sample) {
            return sample.toNBT();
        }

        @Override
        protected AEMonitorSample sampleFromNBT(NBTTagCompound tag) {
            return AEMonitorSample.fromNBT(tag);
        }
    }

    /** 每 key 一条独立采样链（O2-14：原 15 张并行 Map 的单维化） */
    private final Map<String, Chain> chains = new HashMap<>();

    // Projection indexes are maintained at writes/load, not rebuilt on every Web publication.
    // Keep all archived chains; the index bounds reads without deleting legacy history.
    private final TreeSet<String> sampledKeys = new TreeSet<>();
    private final TreeMap<Long, Integer> sampleTicks = new TreeMap<>();

    private void unindex(String key, Chain chain) {
        AEMonitorSample sample = chain == null ? null : chain.newest();
        if (sample == null || !sampledKeys.remove(key)) return;
        int count = sampleTicks.get(sample.tick);
        if (count == 1) sampleTicks.remove(sample.tick);
        else sampleTicks.put(sample.tick, count - 1);
    }

    private void index(String key, Chain chain) {
        AEMonitorSample sample = chain.newest();
        if (sample == null) return;
        sampledKeys.add(key);
        sampleTicks.put(sample.tick, sampleTicks.getOrDefault(sample.tick, 0) + 1);
    }

    /** Constant-time count of nonempty raw series; does not construct snapshots or histories. */
    public int sampledKeyCount() {
        return sampledKeys.size();
    }

    /** Sorted prefix only: O(log N + limit), never copies/sorts the entire historical key set. */
    public List<String> sampledKeys(int limit) {
        List<String> result = new ArrayList<>();
        if (limit <= 0) return result;
        for (String key : sampledKeys) {
            result.add(key);
            if (result.size() >= limit) break;
        }
        return result;
    }

    /** Every exposed series must be fresh; unknown/future samples cannot borrow another key's time. */
    public long projectionSampleTick(long now) {
        if (clockContinuityPaused || sampleTicks.isEmpty()
            || sampleTicks.firstKey() <= 0
            || sampleTicks.lastKey() > now) return 0;
        return sampleTicks.firstKey();
    }

    /** Full identity descriptors are stored with new series; legacy history has none. */
    private final Map<String, NBTTagCompound> identities = new HashMap<>();

    public void registerIdentity(String key, NBTTagCompound descriptor) {
        if (key != null && descriptor != null && !AEMonitorIdentity.isLegacy(key)) {
            identities.putIfAbsent(key, (NBTTagCompound) descriptor.copy());
        }
    }

    public NBTTagCompound identityDescriptor(String key) {
        NBTTagCompound descriptor = identities.get(key);
        return descriptor == null ? null : (NBTTagCompound) descriptor.copy();
    }

    public boolean isLegacy(String key) {
        return AEMonitorIdentity.isLegacy(key);
    }

    // 每个 key 独立的采样锁（world tick），距离上次采样 ≥ 100 ticks 才允许新采样
    private final Map<String, Long> lastSampleTick = new HashMap<>();

    static final int MAX_CLOCK_ARCHIVES = 64;
    private NBTTagList clockArchives = new NBTTagList();
    private long observedTick = Long.MIN_VALUE;
    private boolean clockContinuityPaused;

    /** Preserve the old timeline explicitly; current windows and rollups never span a clock rollback. */
    boolean resetSampleClock(long tick) {
        if (clockContinuityPaused) return false;
        if (!chains.isEmpty()) {
            if (clockArchives.tagCount() >= MAX_CLOCK_ARCHIVES) {
                clockContinuityPaused = true;
                return false;
            }
            NBTTagCompound archive = activeNBT();
            archive.setString("boundary", "clock-rollback");
            archive.setLong("previousTick", observedTick);
            archive.setLong("resumeTick", tick);
            clockArchives.appendTag(archive);
        }
        clearActive();
        observedTick = tick;
        return true;
    }

    boolean observeClock(long tick) {
        if (tick < observedTick && !resetSampleClock(tick)) return false;
        observedTick = tick;
        return !clockContinuityPaused;
    }

    private Chain getOrCreateChain(String key) {
        Chain chain = chains.get(key);
        if (chain == null) {
            chain = new Chain();
            chains.put(key, chain);
        }
        return chain;
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
        if (!observeClock(tick)) return;
        Chain chain = getOrCreateChain(key);
        AEMonitorSample newest = chain.newest();
        double rate = 0.0D;
        if (newest != null && tick > newest.tick) {
            rate = (amount - newest.amount) / (double) (tick - newest.tick) * 1200.0D;
        }

        AEMonitorSample sample = new AEMonitorSample(timeMs, tick, amount, rate);
        unindex(key, chain);
        chain.addToWindow(WINDOW_5_MIN, sample);
        chain.flow(sample);
        index(key, chain);

        lastSampleTick.put(key, tick);
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
        if (!observeClock(tick)) return false;
        long interval = intervalTicks > 0 ? intervalTicks : 100L;
        Long last = lastSampleTick.get(key);
        if (last == null || tick - last.longValue() >= interval) {
            lastSampleTick.put(key, tick);
            return true;
        }
        return false;
    }

    /**
     * 计算指定 key 在基础采样窗口实际跨度内的平均变化率（amount / minute）。
     * <p>
     * 使用 {@code series5m} 窗口的首尾两个采样点：
     * {@code (last.amount - first.amount) / (last.tick - first.tick) * 1200.0}。
     * 其中 1200 = 20 ticks/秒 × 60 秒/分钟，将 tick 差转换为分钟。
     *
     * @param key 被监视物品/流体的字符串标识
     * @return 平均变化率（每分钟数量变化）；样本不足或 tick 差非正则返回 0.0
     */
    public double averageRate(String key) {
        Chain chain = chains.get(key);
        if (chain == null || chain.size(WINDOW_5_MIN) < 2) {
            return 0.0D;
        }
        List<AEMonitorSample> samples = chain.query(WINDOW_5_MIN);
        AEMonitorSample first = samples.get(0);
        AEMonitorSample last = samples.get(samples.size() - 1);
        long tickDiff = last.tick - first.tick;
        if (tickDiff <= 0L) {
            return 0.0D;
        }
        return (last.amount - first.amount) / (double) tickDiff * 1200.0D;
    }

    /** Compatibility alias: the fixed-point window does not necessarily span 300 seconds. */
    @Deprecated
    public double averageRate300s(String key) {
        return averageRate(key);
    }

    public long spanTicks(String key, int window) {
        return WindowLabel.spanTicks(query(key, window));
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
        Chain chain = chains.get(key);
        return chain == null ? new ArrayList<>() : chain.query(window);
    }

    /**
     * 取指定 key 的最新采样点（5m 集的最新点）。
     *
     * @param key 被监视物品/流体的字符串标识
     * @return 最新点；key 不存在或空集返回 null
     */
    public AEMonitorSample newest(String key) {
        Chain chain = chains.get(key);
        return chain == null ? null : chain.newest();
    }

    /**
     * 查询指定 key 与窗口的当前样本数。
     */
    public int size(String key, int window) {
        Chain chain = chains.get(key);
        return chain == null ? 0 : chain.size(window);
    }

    /**
     * 清空指定 key 的所有窗口、计数器与采样锁，释放内存。
     */
    public void clear(String key) {
        unindex(key, chains.remove(key));
        identities.remove(key);
        lastSampleTick.remove(key);
    }

    /**
     * 清空全部 key。
     */
    public void clear() {
        clearActive();
        clockArchives = new NBTTagList();
        observedTick = Long.MIN_VALUE;
        clockContinuityPaused = false;
    }

    private void clearActive() {
        chains.clear();
        sampledKeys.clear();
        sampleTicks.clear();
        identities.clear();
        lastSampleTick.clear();
    }

    /**
     * 获取当前所有 key 的集合（以 5m 集为准：每个 addSample 的 key 必先入 5m 集，
     * 与原 series5m.keySet() 口径一致——readFromNBT 未带 series5m 键的 entry 不计入）。
     */
    public Set<String> getKeys() {
        Set<String> keys = new TreeSet<>();
        for (Map.Entry<String, Chain> entry : chains.entrySet()) {
            if (entry.getValue()
                .hasWindow(WINDOW_5_MIN)) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    /**
     * NBT 序列化：每个 key 一个 entry，包含 8 个 series、7 个 counter 与 lastSampleTick
     * （键名与写入顺序与 O2-14 前逐字段版逐字一致）。
     */
    public NBTTagCompound toNBT() {
        NBTTagCompound tag = activeNBT();
        tag.setTag("clockArchives", clockArchives.copy());
        tag.setLong("observedTick", observedTick);
        tag.setBoolean("clockContinuityPaused", clockContinuityPaused);
        return tag;
    }

    private NBTTagCompound activeNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (String key : getKeys()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("key", key);
            NBTTagCompound descriptor = identities.get(key);
            if (descriptor != null) entry.setTag("identity", descriptor.copy());

            NBTTagCompound data = new NBTTagCompound();
            chains.get(key)
                .writeWindowsToNBT(data);
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
        clockArchives = (NBTTagList) tag.getTagList("clockArchives", Constants.NBT.TAG_COMPOUND)
            .copy();
        clockContinuityPaused = tag.getBoolean("clockContinuityPaused");
        observedTick = tag.hasKey("observedTick") ? tag.getLong("observedTick") : Long.MIN_VALUE;
        NBTTagList list = tag.getTagList("keys", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            String key = entry.getString("key");
            if (key == null || key.isEmpty()) {
                continue;
            }
            if (entry.hasKey("identity", Constants.NBT.TAG_COMPOUND)) {
                registerIdentity(key, entry.getCompoundTag("identity"));
            }
            NBTTagCompound data = entry.getCompoundTag("data");
            Chain chain = getOrCreateChain(key);
            unindex(key, chain);
            chain.readWindowsFromNBT(data);
            index(key, chain);
            long last = data.getLong("lastSampleTick");
            lastSampleTick.put(key, last);
            observedTick = Math.max(observedTick, last);
            AEMonitorSample newest = chain.newest();
            if (newest != null) observedTick = Math.max(observedTick, newest.tick);
        }
    }
}
