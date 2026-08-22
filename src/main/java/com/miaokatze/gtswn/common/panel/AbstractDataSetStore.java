package com.miaokatze.gtswn.common.panel;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.Constants;

/**
 * 面板数据集存储骨架（O2-15，原 SWN-OPT-09 方案 Q2-2）。
 * <p>
 * {@code NetworkInfoDataStore} / {@code AEMonitorDataStore} 此前整类同构：
 * dataSets Map + getOrCreate + remove + NBT 读写（{@code "sets"} 列表内
 * {@code {"id": ..., "data": ...}} entry，字节级同格式）——本骨架承载公共部分，
 * 子类只保留自有方法（活跃判定 / 过期清理 / 修订号 / 仅查询等）。NBT 键名、entry
 * 结构与读写语义逐字不变，旧存档直接兼容。
 */
public abstract class AbstractDataSetStore<T> extends WorldSavedData {

    /** 数据集映射表（子类直接访问，迭代场景见 getActiveEntries/cleanupStale 等） */
    protected final Map<String, T> dataSets = new HashMap<>();

    protected AbstractDataSetStore(String name) {
        super(name);
    }

    /** 新建一个空数据集 */
    protected abstract T createDataSet();

    /** 反序列化数据集内容（读 entry 的 "data" 子键） */
    protected abstract void readDataSet(T dataSet, NBTTagCompound data);

    /** 序列化数据集内容（写 entry 的 "data" 子键） */
    protected abstract NBTTagCompound writeDataSet(T dataSet);

    /**
     * 取或创建数据集（不存在则新建并标记 dirty）。
     *
     * @param id 数据集 key
     * @return 对应的数据集（不存在则新建）
     */
    public T getOrCreate(String id) {
        T dataSet = dataSets.get(id);
        if (dataSet == null) {
            dataSet = createDataSet();
            dataSets.put(id, dataSet);
            markDirty();
        }
        return dataSet;
    }

    /**
     * 仅查询不创建：返回指定 key 的数据集，不存在则返回 null。
     * <p>
     * 用于只读场景，避免创建空数据集导致内存泄漏与脏 markDirty 写入。
     *
     * @param id 数据集 key
     * @return 对应的数据集；不存在返回 null
     */
    protected T getIfPresent(String id) {
        return dataSets.get(id);
    }

    /**
     * 移除指定 key 的数据集条目并标记 dirty，释放内存。
     *
     * @param id 数据集 key
     * @return true=已移除；false=key 不存在无需移除
     */
    protected boolean removeEntry(String id) {
        boolean removed = dataSets.remove(id) != null;
        if (removed) {
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
            T dataSet = createDataSet();
            readDataSet(dataSet, entry.getCompoundTag("data"));
            dataSets.put(id, dataSet);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<String, T> entry : dataSets.entrySet()) {
            NBTTagCompound setTag = new NBTTagCompound();
            setTag.setString("id", entry.getKey());
            setTag.setTag("data", writeDataSet(entry.getValue()));
            list.appendTag(setTag);
        }
        tag.setTag("sets", list);
    }
}
