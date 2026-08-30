package com.miaokatze.gtswn.common.panel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.util.SavedDataUtil;

/**
 * 网络信息屏数据集存储（每世界一份，perWorldStorage 落盘）。
 * <p>
 * 数据集映射与 NBT 读写（{@code "sets"} 列表 + {@code "id"/"data"} entry）由
 * {@link AbstractDataSetStore} 骨架承载（O2-15）；本类保留 EU 侧自有部分：
 * key 语义说明、活跃条目快照与结构修订号。
 * <p>
 * key 语义变更：早期版本为每屏独立的 datasetId（UUID 字符串），
 * 现改为玩家 ownerUUID.toString()，同一玩家的所有网络信息屏共享同一份数据集
 * （getOrCreate 传入的 id 应为玩家 ownerUUID.toString()）。
 * 旧 datasetId-keyed 数据无法适配新机制 → 反序列化时直接丢弃（readFromNBT 内未匹配新格式则空集）。
 */
public class NetworkInfoDataStore extends AbstractDataSetStore<NetworkInfoDataSet> {

    private static final String DATA_NAME = "gtswn_network_info_data";

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
