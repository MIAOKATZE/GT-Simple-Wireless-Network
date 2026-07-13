package com.miaokatze.gtswn.common.panel;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.util.Constants;

public class NetworkInfoDataStore extends WorldSavedData {

    private static final String DATA_NAME = "gtswn_network_info_data";

    /**
     * 数据集映射表。
     * <p>
     * key 语义变更：早期版本为每屏独立的 datasetId（UUID 字符串），
     * 现改为玩家 ownerUUID.toString()，同一玩家的所有网络信息屏共享同一份数据集。
     * 旧 datasetId-keyed 数据无法适配新机制 → 反序列化时直接丢弃（readFromNBT 内未匹配新格式则空集）。
     */
    private final Map<String, NetworkInfoDataSet> dataSets = new HashMap<>();

    public NetworkInfoDataStore(String name) {
        super(name);
    }

    public static NetworkInfoDataStore get(World world) {
        MapStorage storage = world.perWorldStorage;
        NetworkInfoDataStore data = (NetworkInfoDataStore) storage.loadData(NetworkInfoDataStore.class, DATA_NAME);
        if (data == null) {
            data = new NetworkInfoDataStore(DATA_NAME);
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    /**
     * 取或创建数据集。
     * <p>
     * 注意：传入的 id 应为玩家 ownerUUID.toString()（不再是旧的 datasetId）。
     * 同一玩家的所有信息屏共享同一份 {@link NetworkInfoDataSet}，从而实现多屏数据一致。
     *
     * @param id 玩家 ownerUUID 字符串
     * @return 对应的数据集（不存在则新建）
     */
    public NetworkInfoDataSet getOrCreate(String id) {
        NetworkInfoDataSet set = dataSets.get(id);
        if (set == null) {
            set = new NetworkInfoDataSet();
            dataSets.put(id, set);
            markDirty();
        }
        return set;
    }

    /**
     * 移除指定 ownerUUID 的数据集，释放内存。
     * 调用前应确认该玩家已无任何网络信息屏方块。
     *
     * @param id 玩家 ownerUUID 字符串
     * @return true=已移除并标记 dirty；false=key 不存在无需移除
     */
    public boolean remove(String id) {
        if (dataSets.remove(id) != null) {
            markDirty();
            return true;
        }
        return false;
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
            markDirty();
        }
        return removed;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        dataSets.clear();
        NBTTagList list = tag.getTagList("sets", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            String id = entry.getString("id");
            if (id == null || id.isEmpty()) {
                continue;
            }
            NetworkInfoDataSet set = new NetworkInfoDataSet();
            set.readFromNBT(entry.getCompoundTag("data"));
            dataSets.put(id, set);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<String, NetworkInfoDataSet> entry : dataSets.entrySet()) {
            NBTTagCompound setTag = new NBTTagCompound();
            setTag.setString("id", entry.getKey());
            setTag.setTag(
                "data",
                entry.getValue()
                    .toNBT());
            list.appendTag(setTag);
        }
        tag.setTag("sets", list);
    }
}
