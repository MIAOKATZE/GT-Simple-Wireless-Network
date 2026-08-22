package com.miaokatze.gtswn.common.panel;

import java.math.BigInteger;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtswn.common.util.EUDataSet;

/**
 * 网络信息屏数据集（每玩家一份，8 窗口 × 61 点 FIFO）。
 * <p>
 * 窗口结构、流入计数链（5m→1h→8h→24h→7d→1M→3M→1Y，阈值 12/8/3/7/4/3/4，
 * 均值录入）与窗口 NBT 编解码由 {@link WindowChain} 骨架单源承载（O2-14）；
 * 本类为 EU 薄壳，仅保留 EU 侧自有状态：
 * <ul>
 * <li>EU/t 斜率测量历史（{@link EUDataSet}，v1.5.16 从 TileEntity 迁入）</li>
 * <li>数据集级采样锁（多屏共享去重，与 AE 侧 per-key 锁语义不同）</li>
 * <li>lastRequestTick 活跃判定（调度器）与 lastSampleTimeMs 跨会话活跃度</li>
 * </ul>
 * 多屏共享：同一玩家的所有信息屏共享同一份数据集（key=ownerUUID 字符串）。
 * 窗口常量（WINDOW_5_MIN..WINDOW_1_YEAR）继承自 {@link WindowChain}，
 * 外部 {@code NetworkInfoDataSet.WINDOW_*} 引用不变。
 */
public class NetworkInfoDataSet extends WindowChain<NetworkInfoSample> {

    // 全局采样锁（用 world tick）：多屏共享时去重，距离上次采样 ≥ 100 ticks (5s) 才允许新采样
    private long lastSampleTick = -1L;

    // 信息屏最近一次请求 tick（overworld tick），用于调度器判断是否活跃。
    // 不持久化：重启后为 0，overworld tick 很大，isRequestActive 返回 false，
    // 但 panel updateEntity 在 ServerTickEvent END phase 之前执行，
    // chunk 加载后首 tick 即写入 lastRequestTick，无采样空窗。
    private long lastRequestTick = 0L;

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

    public NetworkInfoDataSet() {
        // eager：构造即建全部 8 窗口，toNBT 无条件写全 8 个 series 键（与原 EU 序列化语义一致）
        super(true);
    }

    @Override
    protected NetworkInfoSample createDerived(NetworkInfoSample source, double aggregate) {
        // eu/timeMs/tick 用触发点瞬时值，eut 用上一级窗口最近 N 点均值
        return new NetworkInfoSample(source.timeMs, source.tick, source.eu, aggregate);
    }

    @Override
    protected double aggregateOf(NetworkInfoSample sample) {
        return sample.eut;
    }

    @Override
    protected NBTTagCompound sampleToNBT(NetworkInfoSample sample) {
        return sample.toNBT();
    }

    @Override
    protected NetworkInfoSample sampleFromNBT(NBTTagCompound tag) {
        return NetworkInfoSample.fromNBT(tag);
    }

    /**
     * 主入口：接收一个原始采样点（每 5s 一次），按流入计数链分发到各级窗口（均值录入）。
     * <p>
     * v1.5.16 起 eut 不再由调用方传入，改为内部通过 {@link EUDataSet} 计算瞬时斜率：
     * 先 eutDataSet.add(eu, tick) 记录原始值，再 calculateRecentEUT() 取最近两点斜率作为瞬时 EU/t。
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
        // 5m 集始终以瞬时值录入（行为不变），再沿流入计数链分发
        NetworkInfoSample sample = new NetworkInfoSample(timeMs, tick, eu, eut);
        addToWindow(WINDOW_5_MIN, sample);
        flow(sample);
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
     * 信息屏 updateEntity 时调用，更新最近请求 tick（逻辑请求，非网络包）。
     * <p>
     * 调度器通过 {@link #isRequestActive} 判断该 dataSet 是否在 5 分钟内有请求，
     * 仅对活跃 dataSet 采样。lastRequestTick 不持久化，重启后从 0 开始。
     *
     * @param tick 当前 overworld tick
     */
    public void updateRequestTick(long tick) {
        this.lastRequestTick = tick;
    }

    /**
     * 调度器判断该 dataSet 是否活跃（5 分钟 = 6000t 内有请求）。
     * <p>
     * 5 分钟超时仅停止采样，dataSet 保留（由 FIFO 自动溢出，不主动清理）。
     *
     * @param currentTick 当前 overworld tick
     * @return true 表示 6000t 内有请求，需要继续采样
     */
    public boolean isRequestActive(long currentTick) {
        return currentTick - lastRequestTick < 6000L;
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
     * 清空所有窗口与计数器、采样锁。
     */
    public void clear() {
        clearWindows();
        lastSampleTick = -1L;
        // v1.5.16：清空 EU/t 测量历史（per-player 共享，从 TileEntity 迁移至此）
        eutDataSet.clear();
    }

    /**
     * NBT 序列化：写 8 个 series compound + 7 个 counter + lastSampleTick + lastSampleTimeMs
     * + eutMeasurementHistory（键名与写入顺序与 O2-14 前逐字段版逐字一致）。
     */
    public NBTTagCompound toNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        writeWindowsToNBT(tag);
        tag.setLong("lastSampleTick", lastSampleTick);
        // v1.5.15：持久化真实时间戳，跨会话判断玩家活跃度
        tag.setLong("lastSampleTimeMs", lastSampleTimeMs);
        // v1.5.16：持久化 EU/t 测量历史（per-player 共享，从 TileEntity 迁移至此）
        // saveToNBT 向 tag 写入子键 "eutMeasurementHistory"，不会覆盖其他字段
        eutDataSet.saveToNBT(tag, "eutMeasurementHistory");
        return tag;
    }

    /**
     * NBT 反序列化：读取 8 个 series + 7 个 counter + lastSampleTick + EU 侧自有字段。
     * <p>
     * 旧格式（"samples" 键）不匹配 → 数据集保持空（架构变更较大，旧 datasetId-keyed 数据无法适配新机制，直接丢弃）。
     * v1.5.17 向后兼容：旧存档（4 窗口）无 series7d/1M/3M/1Y 与 counter24h/7d/1M/3M 键时，
     * 通过 hasKey 守卫跳过 series 读取、getInteger 缺省返回 0，确保旧存档可正常加载（新窗口集保持空，待后续采样填充）。
     *
     * @param tag 待读取 NBT；null 直接返回
     */
    public void readFromNBT(NBTTagCompound tag) {
        clear();
        if (tag == null) {
            return;
        }
        readWindowsFromNBT(tag);
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
