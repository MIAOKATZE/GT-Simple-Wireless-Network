package com.miaokatze.gtswn.common.panel;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.util.SavedDataUtil;

/**
 * AE 监视数据集存储（每世界一份，perWorldStorage 落盘）。
 * <p>
 * 数据集映射与 NBT 读写（{@code "sets"} 列表 + {@code "id"/"data"} entry）由
 * {@link AbstractDataSetStore} 骨架承载（O2-15）；本类保留 AE 侧自有部分：key 语义说明与仅查询入口。
 * <p>
 * key 为 {@code dimensionId:x:y:z} 格式的坐标字符串，每个网络信息屏方块独立维护一份 AE 监视数据。
 */
public class AEMonitorDataStore extends AbstractDataSetStore<AEMonitorDataSet> {

    private static final String DATA_NAME = "gtswn_ae_monitor_data";

    public AEMonitorDataStore(String name) {
        super(name);
    }

    public static AEMonitorDataStore get(World world) {
        return SavedDataUtil.loadOrCreate(world, DATA_NAME, AEMonitorDataStore.class, AEMonitorDataStore::new);
    }

    @Override
    protected AEMonitorDataSet createDataSet() {
        return new AEMonitorDataSet();
    }

    @Override
    protected void readDataSet(AEMonitorDataSet dataSet, NBTTagCompound data) {
        dataSet.readFromNBT(data);
    }

    @Override
    protected NBTTagCompound writeDataSet(AEMonitorDataSet dataSet) {
        return dataSet.toNBT();
    }

    /**
     * 仅查询不创建：返回指定坐标 key 的数据集，不存在则返回 null（v1.5.15 新增）。
     * <p>
     * 用于 clearAEData / sendAEMonitorDataToClients 等只读场景，避免创建空数据集导致内存泄漏
     * 与脏 markDirty 写入。
     *
     * @param coordinateKey 信息屏方块的坐标字符串（{@code dimensionId:x:y:z}）
     * @return 对应的数据集；不存在返回 null
     */
    @Override
    public AEMonitorDataSet getIfPresent(String coordinateKey) {
        return super.getIfPresent(coordinateKey);
    }

    /**
     * 移除指定坐标 key 的数据集，方块破坏时调用以释放内存并避免脏数据残留。
     *
     * @param coordinateKey 信息屏方块的坐标字符串（{@code dimensionId:x:y:z}）
     */
    public void remove(String coordinateKey) {
        removeEntry(coordinateKey);
    }
}
