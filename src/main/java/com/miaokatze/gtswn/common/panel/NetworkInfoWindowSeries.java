package com.miaokatze.gtswn.common.panel;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

/**
 * 单窗口 61 点 FIFO 数据集。
 * <p>
 * 用于网络信息屏的 5m/1h/8h/24h 四个时间窗口，每个窗口固定保留 61 个采样点。
 * 超出容量时丢弃最旧的点（FIFO 滚动）。
 */
public class NetworkInfoWindowSeries {

    /** 固定 61 个采样点（覆盖 5m / 1h / 8h / 24h 四档窗口） */
    public static final int CAPACITY = 61;

    // 内部存储：index 0 为最旧，size-1 为最新
    private final List<NetworkInfoSample> data;

    public NetworkInfoWindowSeries() {
        this.data = new ArrayList<>(CAPACITY);
    }

    /**
     * 添加采样点，满 61 时先 remove(0) 再 add（FIFO 滚动）。
     *
     * @param sample 待添加采样点；null 静默丢弃
     */
    public void add(NetworkInfoSample sample) {
        if (sample == null) {
            return;
        }
        if (data.size() >= CAPACITY) {
            data.remove(0);
        }
        data.add(sample);
    }

    /**
     * 返回 ArrayList 副本，供渲染线程安全读取（避免并发修改）。
     */
    public List<NetworkInfoSample> copy() {
        return new ArrayList<>(data);
    }

    /**
     * 返回最后 N 个采样点的副本列表（从旧到新顺序），不影响内部 FIFO。
     * <p>
     * 用于"均值录入"：1h/8h/24h 集触发时，取上一级窗口最近 N 个点计算 EU/t 均值。
     *
     * @param n 要取的点数
     * @return 从旧到新的 N 个点副本；n <= 0 返回空列表；n >= size 返回全部点副本（等价于 copy()）
     */
    public List<NetworkInfoSample> getLastN(int n) {
        int size = data.size();
        if (n <= 0) {
            return new ArrayList<>();
        }
        if (n >= size) {
            return new ArrayList<>(data);
        }
        return new ArrayList<>(data.subList(size - n, size));
    }

    /**
     * 取最新采样点。
     *
     * @return 最新点；空集返回 null
     */
    public NetworkInfoSample newest() {
        return data.isEmpty() ? null : data.get(data.size() - 1);
    }

    public int size() {
        return data.size();
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    public void clear() {
        data.clear();
    }

    /**
     * NBT 序列化：写 "count" + "samples" 列表。
     */
    public NBTTagCompound toNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("count", data.size());
        NBTTagList list = new NBTTagList();
        for (NetworkInfoSample s : data) {
            list.appendTag(s.toNBT());
        }
        tag.setTag("samples", list);
        return tag;
    }

    /**
     * NBT 反序列化：读取 samples 列表，超过 CAPACITY 时只取最后 61 个（防止脏数据）。
     *
     * @param tag 待读取的 NBT；null 直接返回
     */
    public void readFromNBT(NBTTagCompound tag) {
        data.clear();
        if (tag == null) {
            return;
        }
        NBTTagList list = tag.getTagList("samples", Constants.NBT.TAG_COMPOUND);
        int start = Math.max(0, list.tagCount() - CAPACITY);
        for (int i = start; i < list.tagCount(); i++) {
            NetworkInfoSample s = NetworkInfoSample.fromNBT(list.getCompoundTagAt(i));
            if (s != null) {
                data.add(s);
            }
        }
    }
}
