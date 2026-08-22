package com.miaokatze.gtswn.common.panel;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.common.util.GTTierUtil;

/**
 * EU 缓存桥域（O2-03 = E 线 E4，四域拆分第三步）：owner→{@link NetworkInfoDataSet} 的
 * 请求 tick 更新 / 冷启动刷新 / 新数据轮询三段 + EU/EU-t/状态文本/走势样本缓存 + 状态文本格式化。
 * <p>
 * 方法体自 {@code TileEntityNetworkInfoPanel} 逐字搬迁（E4 表 B 行段整体平移），零行为变更；
 * 三段顺序（updateRequestTick→冷启动→轮询）不变，变更驱动的 markDirty + markBlockForUpdate
 * 副作用随段平移。core 引用仅用于 worldObj/ownerUUID 读取与该副作用出口（E2 ScreenStructure
 * 同构模式）；store 引用供 formatStatus 读取 displayMode（单向依赖 B→D）。
 * <p>
 * S35 的 cachedEu/cachedEut/cachedStatus/samples 四键经 {@link #readSync}/{@link #writeSync}
 * 搬运，键名逐字不动。客户端镜像说明：cachedEu/cachedEut/cachedStatus/cachedSamples 横跨双端
 * （服务端轮询写入、客户端 readSyncData 写入）——数据内聚优先，接受跨端（深查 §2.2 裁决，
 * 门面二阶段 E6 前外部消费仍走 TE getter）。
 */
public final class EUCacheBridge {

    private final TileEntityNetworkInfoPanel core;
    private final PanelConfigStore store;

    private BigInteger cachedEu = BigInteger.ZERO;
    private double cachedEut = 0.0D;
    private String cachedStatus = "No data";
    private final List<NetworkInfoSample> cachedSamples = new ArrayList<>();

    /** 标记是否需要从 NetworkInfoDataStore 刷新一次缓存（重载/放置后） */
    private boolean needsDataRefresh = true;

    /** 上次已知采样 tick（轮询时检测新数据用） */
    private long lastKnownSampleTick = -1L;

    /**
     * O2-28：owner 数据集解析缓存（ownerUUID 绑定不变时引用稳定，
     * 免 updateEntity 每 tick 2 次 UUID.toString + mapStorage 查询 + getOrCreate HashMap）。
     * store 侧 remove/cleanupStale 推进 revision，缓存据此失效重解析。
     */
    private NetworkInfoDataStore cachedOwnerDataStore = null;
    private NetworkInfoDataSet cachedOwnerDataSet = null;
    private int cachedOwnerDataSetRevision = -1;

    public EUCacheBridge(TileEntityNetworkInfoPanel core, PanelConfigStore store) {
        this.core = core;
        this.store = store;
    }

    /**
     * bindOwner / readPlacementData 的 owner 变更后置：失效 O2-28 解析缓存
     * （原 TE.invalidateOwnerDataSetCache 逐字搬迁）。
     */
    public void onOwnerBound() {
        cachedOwnerDataStore = null;
        cachedOwnerDataSet = null;
        cachedOwnerDataSetRevision = -1;
    }

    /** 请求一次冷启动刷新（重载/放置/绑定后由 TE 编排调用；原 needsDataRefresh = true 平移） */
    public void markDataRefreshNeeded() {
        needsDataRefresh = true;
    }

    /**
     * 服务端 EU 三段（原 updateEntity 中段逐字搬迁，顺序不变）：
     * 请求 tick 更新 → 冷启动刷新 → 新数据轮询；数据变更驱动 markDirty + markBlockForUpdate。
     *
     * @param overworldTick 主世界 tick（调度器采样基准，TE 编排头获取后传入）
     */
    public void tickServer(long overworldTick) {
        // ===== 更新请求 tick（调度器据此判断是否活跃采样） =====
        // 信息屏每次 updateEntity 都更新 lastRequestTick，调度器仅对 5 分钟内有请求的 dataSet 采样
        if (core.getOwnerUUID() != null && overworldTick >= 0L) {
            NetworkInfoDataSet dataSet = getOwnerDataSet();
            if (dataSet != null) {
                dataSet.updateRequestTick(overworldTick);
            }
        }

        // ===== 冷启动数据刷新（重进存档/放置方块后执行一次） =====
        if (needsDataRefresh && core.getOwnerUUID() != null) {
            needsDataRefresh = false;
            // 从全局数据集拉取该玩家已有数据填入缓存
            refreshChartCache();
            NetworkInfoDataSet dataSet = getOwnerDataSet();
            if (dataSet != null) {
                NetworkInfoSample newest = dataSet.newest();
                if (newest != null) {
                    cachedEu = newest.eu;
                    cachedEut = newest.eut;
                    lastKnownSampleTick = newest.tick;
                }
                boolean cold = dataSet.isColdStarting();
                boolean longSilent = dataSet.isLongTermSilent();
                cachedStatus = formatStatus(cachedEut, cold, longSilent);
            }
            core.markDirty();
            core.getWorldObj()
                .markBlockForUpdate(core.xCoord, core.yCoord, core.zCoord);
        }

        // ===== 轮询全局数据集，检测新采样数据 =====
        if (core.getOwnerUUID() != null) {
            NetworkInfoDataSet dataSet = getOwnerDataSet();
            if (dataSet != null) {
                NetworkInfoSample newest = dataSet.newest();
                if (newest != null && newest.tick != lastKnownSampleTick) {
                    cachedEu = newest.eu;
                    cachedEut = newest.eut;
                    boolean cold = dataSet.isColdStarting();
                    boolean longSilent = dataSet.isLongTermSilent();
                    cachedStatus = formatStatus(cachedEut, cold, longSilent);
                    refreshFrom(dataSet);
                    lastKnownSampleTick = newest.tick;
                    core.markDirty();
                    core.getWorldObj()
                        .markBlockForUpdate(core.xCoord, core.yCoord, core.zCoord);
                }
            }
        }
    }

    /**
     * 刷新本屏走势图缓存（从全局数据集查询当前 trackingWindow；原 refreshCachedSamples() 无参版，
     * applyConfigAction case 4 后置）。
     */
    public void refreshChartCache() {
        if (core.getWorldObj() == null || core.getWorldObj().isRemote || core.getOwnerUUID() == null) {
            return;
        }
        NetworkInfoDataSet dataSet = getOwnerDataSet();
        if (dataSet != null) {
            refreshFrom(dataSet);
        }
    }

    /**
     * 立即重算 cachedStatus（applyConfigAction case 7 后置：displayMode 切换即时反映新格式，
     * 无需等下次采样）。
     */
    public void refreshStatusText() {
        NetworkInfoDataSet dataSet = getOwnerDataSet();
        boolean cold = (dataSet == null) || dataSet.isColdStarting();
        cachedStatus = formatStatus(cachedEut, cold, dataSet != null && dataSet.isLongTermSilent());
    }

    public BigInteger getCachedEu() {
        return cachedEu;
    }

    public String getCachedStatus() {
        return cachedStatus;
    }

    public List<NetworkInfoSample> getCachedSamples() {
        return cachedSamples;
    }

    /**
     * 获取该信息屏所属玩家的全局数据集（统一使用 overworld 数据存储）
     * 仅服务端调用，客户端返回 null
     * <p>
     * O2-28：ownerUUID 绑定不变时数据集引用稳定，缓存解析结果；
     * store 侧 remove/cleanupStale 推进 revision 时缓存失效，重新 getOrCreate
     * 取活引用（cleanup_info_data 命令清理后从空数据集重启的语义不变）。
     *
     * @return NetworkInfoDataSet 或 null
     */
    private NetworkInfoDataSet getOwnerDataSet() {
        if (core.getWorldObj() == null || core.getWorldObj().isRemote || core.getOwnerUUID() == null) return null;
        if (cachedOwnerDataSet != null && cachedOwnerDataStore != null
            && cachedOwnerDataSetRevision == cachedOwnerDataStore.getRevision()) {
            return cachedOwnerDataSet;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return null;
        World overworld = server.worldServerForDimension(0);
        if (overworld == null) return null;
        cachedOwnerDataStore = NetworkInfoDataStore.get(overworld);
        cachedOwnerDataSet = cachedOwnerDataStore.getOrCreate(
            core.getOwnerUUID()
                .toString());
        cachedOwnerDataSetRevision = cachedOwnerDataStore.getRevision();
        return cachedOwnerDataSet;
    }

    /** 从指定数据集刷新本屏走势图缓存（原 refreshCachedSamples(dataSet)） */
    private void refreshFrom(NetworkInfoDataSet dataSet) {
        cachedSamples.clear();
        cachedSamples.addAll(dataSet.query(store.getTrackingWindow()));
    }

    /** EU/t 状态文本（原 TE.formatStatus 逐字搬迁；displayMode 经 store 读取） */
    public String formatStatus(double eut, boolean coldStarting, boolean longTermSilent) {
        if (coldStarting) {
            return tr("gtswn.network_info.status.cold");
        }
        if (longTermSilent) {
            return tr("gtswn.network_info.status.longtermsilent");
        }
        if (Math.abs(eut) < 0.000001D) {
            return tr("gtswn.network_info.status.silent");
        }
        if (Math.abs(eut) < 1.0D) {
            return tr("gtswn.network_info.status.lessthan1");
        }
        String key = eut > 0 ? "gtswn.network_info.status.up" : "gtswn.network_info.status.down";
        // EU/t 数值根据 displayMode 切换常规/科学/千位计数
        String eutText;
        switch (store.getDisplayMode()) {
            case 1:
                eutText = FormatUtil.formatScientificDouble(Math.abs(eut));
                break;
            case 2:
                eutText = FormatUtil.formatMetricDouble(Math.abs(eut), 2);
                break;
            case 0:
            default:
                eutText = FormatUtil.formatNormalDouble(Math.abs(eut));
                break;
        }
        return StatCollector.translateToLocalFormatted(key, eutText, GTTierUtil.formatGTPower(eut));
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    // ==================== S35 序列化段（cachedEu/cachedEut/cachedStatus/samples 四键逐字不动）====================

    /** S35 描述包 EU 缓存段写入（原 TE.writeSyncData 的四键段） */
    public void writeSync(NBTTagCompound tag) {
        tag.setString("cachedEu", cachedEu == null ? "0" : cachedEu.toString());
        tag.setDouble("cachedEut", cachedEut);
        tag.setString("cachedStatus", cachedStatus == null ? "" : cachedStatus);
        NBTTagList list = new NBTTagList();
        for (NetworkInfoSample sample : cachedSamples) {
            list.appendTag(sample.toNBT());
        }
        tag.setTag("samples", list);
    }

    /** S35 描述包 EU 缓存段读取（原 TE.readSyncData 的四键段，读取全守卫语义保持） */
    public void readSync(NBTTagCompound tag) {
        if (tag.hasKey("cachedEu")) {
            try {
                cachedEu = new BigInteger(tag.getString("cachedEu"));
            } catch (NumberFormatException e) {
                cachedEu = BigInteger.ZERO;
            }
        }
        cachedEut = tag.getDouble("cachedEut");
        if (tag.hasKey("cachedStatus")) {
            cachedStatus = tag.getString("cachedStatus");
        }
        cachedSamples.clear();
        NBTTagList list = tag.getTagList("samples", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            cachedSamples.add(NetworkInfoSample.fromNBT(list.getCompoundTagAt(i)));
        }
    }
}
