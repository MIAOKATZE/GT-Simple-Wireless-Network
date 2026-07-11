package com.miaokatze.gtswn.common.panel;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.util.Constants;

public class AEMonitorDataStore extends WorldSavedData {

    private static final String DATA_NAME = "gtswn_ae_monitor_data";

    /**
     * 数据集映射表。
     * <p>
     * key 为 {@code dimensionId:x:y:z} 格式的坐标字符串，每个网络信息屏方块独立维护一份 AE 监视数据。
     */
    private final Map<String, AEMonitorDataSet> dataSets = new HashMap<>();

    public AEMonitorDataStore(String name) {
        super(name);
    }

    public static AEMonitorDataStore get(World world) {
        MapStorage storage = world.perWorldStorage;
        AEMonitorDataStore data = (AEMonitorDataStore) storage.loadData(AEMonitorDataStore.class, DATA_NAME);
        if (data == null) {
            data = new AEMonitorDataStore(DATA_NAME);
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    /**
     * 取或创建数据集。
     *
     * @param coordinateKey 信息屏方块的坐标字符串（{@code dimensionId:x:y:z}）
     * @return 对应的数据集（不存在则新建）
     */
    public AEMonitorDataSet getOrCreate(String coordinateKey) {
        AEMonitorDataSet set = dataSets.get(coordinateKey);
        if (set == null) {
            set = new AEMonitorDataSet();
            dataSets.put(coordinateKey, set);
            markDirty();
        }
        return set;
    }

    /**
     * 移除指定坐标 key 的数据集，方块破坏时调用以释放内存并避免脏数据残留。
     *
     * @param coordinateKey 信息屏方块的坐标字符串（{@code dimensionId:x:y:z}）
     */
    public void remove(String coordinateKey) {
        if (dataSets.remove(coordinateKey) != null) {
            markDirty();
        }
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
            AEMonitorDataSet set = new AEMonitorDataSet();
            set.readFromNBT(entry.getCompoundTag("data"));
            dataSets.put(id, set);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<String, AEMonitorDataSet> entry : dataSets.entrySet()) {
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
