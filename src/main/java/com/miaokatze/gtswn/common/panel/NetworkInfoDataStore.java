package com.miaokatze.gtswn.common.panel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.util.SavedDataUtil;

import gregtech.common.misc.spaceprojects.SpaceProjectManager;

/**
 * 网络信息屏数据集存储（每世界一份，perWorldStorage 落盘）。
 * <p>
 * 数据集映射与 NBT 读写（{@code "sets"} 列表 + {@code "id"/"data"} entry）由
 * {@link AbstractDataSetStore} 骨架承载（O2-15）；本类保留 EU 侧自有部分：
 * key 语义说明、活跃条目快照与结构修订号。
 * <p>
 * Live keys are {@code eu:account:<leader UUID>} for the actual GT wireless account. Same-team owners
 * share a sample lock and history. Old owner/dataset UUID entries remain untouched legacy archives;
 * a current team membership cannot prove which native account produced an old per-owner series.
 */
public class NetworkInfoDataStore extends AbstractDataSetStore<NetworkInfoDataSet> {

    private static final String DATA_NAME = "gtswn_network_info_data";
    private static final String NATIVE_PREFIX = "eu:account:";

    /**
     * 数据集结构修订号（O2-28）：{@link #remove} / {@link #cleanupStale} 实际移除条目时 +1。
     * <p>
     * 供信息屏 TE 侧的 owner 数据集引用缓存校验——revision 变化说明缓存引用可能已从
     * 本映射剥离，需重新走 {@link #getOrCreate} 取活引用，保证命令清理语义不变。
     */
    private int revision = 0;

    public NetworkInfoDataStore(String name) {
        super(name);
    }

    public static NetworkInfoDataStore get(World world) {
        return SavedDataUtil.loadOrCreate(world, DATA_NAME, NetworkInfoDataStore.class, NetworkInfoDataStore::new);
    }

    /**
     * 仅查询不创建：返回指定 ownerUUID 的数据集，不存在则返回 null（v1.7.9 引入，v1.7.14 注释口径统一）。
     * <p>
     * 供监控 API（api.monitor 包）等只读场景使用，避免创建空数据集导致内存泄漏
     * 与脏 markDirty 写入（写法对齐 {@link AEMonitorDataStore#getIfPresent}）。
     *
     * @param id 玩家 ownerUUID 字符串
     * @return 对应的数据集；不存在返回 null
     */
    @Override
    public NetworkInfoDataSet getIfPresent(String id) {
        return super.getIfPresent(id);
    }

    /** Resolve the actual native wireless account, never substitute a member UUID on failure. */
    public static UUID resolveAccount(UUID owner) {
        return resolveAccount(owner, SpaceProjectManager::getLeader);
    }

    /** Read-only counterpart: GT's getLeader initializes missing teams and marks its world data dirty. */
    public static UUID resolveAccountReadOnly(UUID owner) {
        return resolveAccount(owner, id -> SpaceProjectManager.spaceTeams.getOrDefault(id, id));
    }

    static UUID resolveAccount(UUID owner, Function<UUID, UUID> resolver) {
        if (owner == null) return null;
        try {
            return resolver.apply(owner);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    public static String nativeKey(UUID account) {
        if (account == null) throw new IllegalArgumentException("Native EU account required");
        return NATIVE_PREFIX + account;
    }

    /** Legacy owner UUIDs are deliberately not accepted as proven native sampling identities. */
    public static UUID nativeAccount(String key) {
        if (key == null || !key.startsWith(NATIVE_PREFIX)) return null;
        try {
            String id = key.substring(NATIVE_PREFIX.length());
            UUID account = UUID.fromString(id);
            return account.toString()
                .equals(id) ? account : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public NetworkInfoDataSet getOrCreateNative(UUID account) {
        return account == null ? null : getOrCreate(nativeKey(account));
    }

    public NetworkInfoDataSet getNativeIfPresent(UUID account) {
        return account == null ? null : getIfPresent(nativeKey(account));
    }

    @Override
    protected NetworkInfoDataSet createDataSet() {
        return new NetworkInfoDataSet();
    }

    @Override
    protected void readDataSet(NetworkInfoDataSet dataSet, NBTTagCompound data) {
        dataSet.readFromNBT(data);
    }

    @Override
    protected NBTTagCompound writeDataSet(NetworkInfoDataSet dataSet) {
        return dataSet.toNBT();
    }

    /**
     * 获取当前活跃的 dataSet 条目（5 分钟内有请求的）。
     * <p>
     * 返回快照 List 避免 ConcurrentModificationException，供调度器遍历活跃数据集采样。
     * 调度器不再维护引用计数，改为依赖信息屏 updateEntity 主动更新 lastRequestTick，
     * 5 分钟（6000t）内无请求的 dataSet 视为不活跃，跳过采样。
     *
     * @param currentTick 当前 overworld tick
     * @return 活跃条目列表（快照，修改不影响内部映射）
     */
    public List<Map.Entry<String, NetworkInfoDataSet>> getActiveEntries(long currentTick) {
        List<Map.Entry<String, NetworkInfoDataSet>> result = new ArrayList<>();
        for (Map.Entry<String, NetworkInfoDataSet> entry : dataSets.entrySet()) {
            if (entry.getValue()
                .isRequestActive(currentTick)) {
                result.add(entry);
            }
        }
        return result;
    }

    /** A clock epoch applies even to currently inactive history; this never activates a data set. */
    void resetSampleClocks(long tick) {
        for (NetworkInfoDataSet dataSet : dataSets.values()) dataSet.resetSampleClock(tick);
        if (!dataSets.isEmpty()) markDirty();
    }

    /**
     * 移除指定 ownerUUID 的数据集，释放内存。
     * 调用前应确认该玩家已无任何网络信息屏方块。
     *
     * @param id 玩家 ownerUUID 字符串
     * @return true=已移除并标记 dirty；false=key 不存在无需移除
     */
    public boolean remove(String id) {
        if (removeEntry(id)) {
            revision++;
            return true;
        }
        return false;
    }

    /** 获取数据集结构修订号（O2-28：remove/cleanupStale 移除条目时推进，供 TE 缓存校验）。 */
    public int getRevision() {
        return revision;
    }

    /**
     * 清理超过指定时间未采样的数据集（v1.5.15 新增）。
     * <p>
     * 用于服务器启动时或运维命令清理长期未活跃的玩家数据集，避免内存无限增长。
     * 判定依据为 {@link NetworkInfoDataSet#getLastSampleTimeMs()}，早于 cutoffMs 的数据集将被移除。
     *
     * @param cutoffMs 时间阈值（毫秒），早于此时间的数据集将被移除
     * @return 清理的数据集数量
     */
    public int cleanupStale(long cutoffMs) {
        int removed = 0;
        Iterator<Map.Entry<String, NetworkInfoDataSet>> it = dataSets.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<String, NetworkInfoDataSet> entry = it.next();
            if (entry.getValue()
                .getLastSampleTimeMs() < cutoffMs) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            revision++;
            markDirty();
        }
        return removed;
    }
}
