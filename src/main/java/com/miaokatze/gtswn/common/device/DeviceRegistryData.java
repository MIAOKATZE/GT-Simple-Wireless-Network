package com.miaokatze.gtswn.common.device;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import com.miaokatze.gtswn.common.util.SavedDataUtil;

/**
 * 全局设备登记表（每存档一份，阶段 B）。
 * <p>
 * 记录所有可监控 GT 机器（判别式：IGregTechTileEntity + MTEBasicMachine / MTEMultiBlockBase）
 * 的 owner UUID 与本地显示名。机器键为 {@code "dim:x:y:z"}（全维度唯一），
 * 由 {@link #makeKey} 统一生成，是登记表 / 终端数据仓 / 采样调度三方共用的主键。
 * <p>
 * 挂载点：overworld 的 perWorldStorage（机器键含 dim 前缀、绑定关系跟终端走而非维度，
 * 统一挂 overworld 避免同一数据在多维度分裂；调用方可传任意维度的 world，内部解析）。
 * <p>
 * 登记来源：放置事件（{@link DeviceEventHandler}，owner=放置者）与终端右击补登（owner=点击者）；
 * 出册来源：破坏事件（级联解除所有终端绑定）与采样自愈（阶段 C）。
 */
public class DeviceRegistryData extends WorldSavedData {

    /** WorldSavedData 注册名（全仓唯一，落盘文件名） */
    public static final String DATA_NAME = "gtswn_device_registry";

    /** 机器键 → 登记条目 */
    private final Map<String, DeviceEntry> devices = new HashMap<>();

    /** 登记条目：机器 owner 与本地显示名 */
    public static final class DeviceEntry {

        /** 机器 owner（放置者 / 首次补登者） */
        public final UUID owner;

        /** 机器本地显示名（放置 / 补登时快照） */
        public String localName;

        public DeviceEntry(UUID owner, String localName) {
            this.owner = owner;
            this.localName = localName;
        }
    }

    public DeviceRegistryData(String name) {
        super(name);
    }

    /**
     * 取或创建登记表（任意维度 world 均解析到 overworld 挂载）。
     */
    public static DeviceRegistryData get(World world) {
        return SavedDataUtil
            .loadOrCreate(resolveGlobalWorld(world), DATA_NAME, DeviceRegistryData.class, DeviceRegistryData::new);
    }

    /** 机器键生成（三方共用主键） */
    public static String makeKey(int dim, int x, int y, int z) {
        return dim + ":" + x + ":" + y + ":" + z;
    }

    /**
     * 登记或覆盖机器条目（放置 / 补登，幂等）。
     *
     * @return true=新增条目；false=覆盖已有条目（均 markDirty）
     */
    public boolean register(String key, UUID owner, String localName) {
        boolean isNew = devices.get(key) == null;
        devices.put(key, new DeviceEntry(owner, localName));
        markDirty();
        return isNew;
    }

    /** 查询条目（只读，不存在返回 null） */
    public DeviceEntry getEntry(String key) {
        return devices.get(key);
    }

    /**
     * 出册机器条目。
     *
     * @return true=存在且已移除（markDirty）；false=key 不存在无需移除
     */
    public boolean unregister(String key) {
        boolean removed = devices.remove(key) != null;
        if (removed) {
            markDirty();
        }
        return removed;
    }

    /** 当前登记机器数 */
    public int size() {
        return devices.size();
    }

    /**
     * 全量条目快照（阶段 C 扫描用：后台线程过滤团队机器时读副本，不持内部 Map 引用）。
     */
    public Map<String, DeviceEntry> snapshotEntries() {
        return new HashMap<>(devices);
    }

    // ==================== NBT 对称读写 ====================

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        devices.clear();
        NBTTagList list = tag.getTagList("devices", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            String key = entry.getString("key");
            String ownerStr = entry.getString("owner");
            if (key == null || key.isEmpty() || ownerStr == null || ownerStr.isEmpty()) {
                continue;
            }
            try {
                devices.put(key, new DeviceEntry(UUID.fromString(ownerStr), entry.getString("name")));
            } catch (IllegalArgumentException e) {
                // 坏 UUID 数据跳过（不阻断其余条目加载）
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<String, DeviceEntry> entry : devices.entrySet()) {
            NBTTagCompound entryTag = new NBTTagCompound();
            entryTag.setString("key", entry.getKey());
            entryTag.setString("owner", entry.getValue().owner.toString());
            entryTag.setString("name", entry.getValue().localName == null ? "" : entry.getValue().localName);
            list.appendTag(entryTag);
        }
        tag.setTag("devices", list);
    }

    /** 解析全局挂载世界（overworld；服务端外 / 异常时回退传入 world） */
    private static World resolveGlobalWorld(World world) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            World overworld = server.worldServerForDimension(0);
            if (overworld != null) {
                return overworld;
            }
        }
        return world;
    }
}
