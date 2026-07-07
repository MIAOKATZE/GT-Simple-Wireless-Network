package com.miaokatze.gtswn.common.panel;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.util.Constants;

public class NetworkInfoDataStore extends WorldSavedData {

    private static final String DATA_NAME = "gtswn_network_info_data";

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

    public NetworkInfoDataSet getOrCreate(String id) {
        NetworkInfoDataSet set = dataSets.get(id);
        if (set == null) {
            set = new NetworkInfoDataSet();
            dataSets.put(id, set);
            markDirty();
        }
        return set;
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
