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
 * 搬运，四键键名逐字不动且任何档位均随包发送。G3（v1.7.14）增量协议：每 owner 序列号
 * euSyncSeq 随包下发，writeSync 三档构包（冷启动/窗口切换/owner 变更/新连接事件推送/兜底轮换
 * =全量整窗，稳态数据变化=最新 1 点增量），readSync 按 seq 连续性判定增量追加或锁定失步等待
 * 全量自愈（D9：不新增 C→S 补齐包，读端不回发）。客户端镜像说明：
 * cachedEu/cachedEut/cachedStatus/cachedSamples 横跨双端（服务端轮询写入、客户端 readSyncData
 * 写入）——数据内聚优先，接受跨端（深查 §2.2 裁决，门面二阶段 E6 前外部消费仍走 TE getter）。
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

    // ==================== S35 增量协议状态（G3，v1.7.14）====================

    /**
     * 稳态增量构包窗口（世界 tick）：数据变化后仅该窗口内由本次 markBlockForUpdate 触发的
     * S35 构包走单点增量；窗口外的事件构包（玩家新进入监视范围重发描述包、配置变更推送等，
     * 客户端可能无状态）一律回落全量档。窗口取值只需覆盖 PlayerManager 对标记方块的转发延迟。
     */
    private static final int INCREMENTAL_PUSH_WINDOW_TICKS = 20;

    /**
     * 兜底全量轮换间隔：连续 N 次单点增量构包后强制一次全量。读端失步（客户端上次 seq+1
     * != 本次 seq，如极小概率下新连接恰落在增量构包窗口内）时无 C→S 补齐通道（D9），
     * 依靠该事件驱动的轮换在有限次推送内自愈为全量。
     */
    private static final int FULL_SYNC_BACKSTOP_INTERVAL = 8;

    /** 服务端：S35 EU 段数据版本号，四键/样本每次内容变化 +1（随包下发） */
    private long syncSeq = 0L;

    /** 服务端：单点增量构包截止世界 tick（-1=无待发增量窗口） */
    private long incrementalPushUntilTick = -1L;

    /** 服务端：强制全量标记（冷启动/窗口切换置位，下一次构包消费清除） */
    private boolean fullSyncPending = false;

    /** 服务端：连续单点增量构包计数（达到 {@link #FULL_SYNC_BACKSTOP_INTERVAL} 后强制全量并清零） */
    private int incrementalSinceFull = 0;

    /** 客户端镜像：最近一次应用的包 seq（-1=无状态） */
    private long lastSyncSeq = -1L;

    /** 客户端镜像：失步锁——增量包 seq 断档时置位，保留旧窗口仅更新四键，直至全量包整体替换解除 */
    private boolean syncDesynced = false;

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
        // G3：owner 变更后 S35 协议状态整体复位（双端各复位各自侧字段）；
        // 服务端随后由冷启动段置全量档，客户端因 lastSyncSeq=-1 对增量包一律锁定等待全量
        syncSeq = 0L;
        incrementalPushUntilTick = -1L;
        fullSyncPending = false;
        incrementalSinceFull = 0;
        lastSyncSeq = -1L;
        syncDesynced = false;
    }

    /** 请求一次冷启动刷新（重载/放置/绑定后由 TE 编排调用；原 needsDataRefresh = true 平移） */
    public void markDataRefreshNeeded() {
        needsDataRefresh = true;
    }

    /**
     * 服务端 EU 三段（原 updateEntity 中段逐字搬迁，顺序不变）：
     * 请求 tick 更新 → 冷启动刷新 → 新数据轮询；数据变更驱动 markDirty + markBlockForUpdate。
     * G3 增量协议：冷启动段置全量档（fullSyncPending），轮询段推进 seq 并开增量构包窗口——
     * 稳态变化由该 markBlockForUpdate 触发的构包走单点增量，窗口外事件构包回落全量档。
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
            // G3（冷启动全量档）：重载/放置后客户端无状态，描述包必须携带整窗
            // （refreshChartCache 内部已置位，此处显式重复置位防御 dataSet == null 分支）
            syncSeq++;
            fullSyncPending = true;
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
                    // G3（稳态单点增量档）：数据变化推进 seq 并开增量构包窗口——窗口内由本次
                    // markBlockForUpdate 触发的构包只携带最新 1 点，窗口外事件构包回落全量档
                    syncSeq++;
                    incrementalPushUntilTick = core.getWorldObj()
                        .getTotalWorldTime() + INCREMENTAL_PUSH_WINDOW_TICKS;
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
        // G3（窗口切换全量档）：trackingWindow 变化后整窗重查，客户端必须整体替换，
        // 增量追加语义不再成立（保留既有触发点：case 4 后置 + markDirtyAndSync 推送）
        syncSeq++;
        fullSyncPending = true;
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
        // G3（仅四键变化）：状态文本变化推进 seq 并复用单点增量档——包内仍携带最新 1 点，
        // 读端按 tick 去重不追加样本，四键照常应用（缓存为空时 writeSync 自动回落全量档）
        if (core.getWorldObj() != null && !core.getWorldObj().isRemote) {
            syncSeq++;
            incrementalPushUntilTick = core.getWorldObj()
                .getTotalWorldTime() + INCREMENTAL_PUSH_WINDOW_TICKS;
        }
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

    // ==================== S35 序列化段（G3：四键逐字不动 + euSyncSeq/euSyncKind 三档构包）====================

    /**
     * S35 描述包 EU 缓存段写入（原 TE.writeSyncData 的四键段；G3 三档构包）：
     * <ul>
     * <li>全量档（euSyncKind=0）：四键 + samples 整窗（≤61 点）+ seq——冷启动/窗口切换/owner
     * 变更（fullSyncPending）、数据变化构包窗口外的事件推送（玩家新进入监视范围重发描述包、
     * 配置变更推送）、连续 {@link #FULL_SYNC_BACKSTOP_INTERVAL} 次增量的兜底轮换与空缓存走此档；</li>
     * <li>增量档（euSyncKind=1）：四键 + samples 仅最新 1 点 + seq——稳态数据变化后构包窗口内
     * 由该变化触发的推送走此档，载荷由 61 点缩至 1 点；</li>
     * <li>读端失步不回发（D9 无 C→S 补齐包），由 readSync 锁定旧窗口 + 兜底全量轮换自愈。</li>
     * </ul>
     * 四键 cachedEu/cachedEut/cachedStatus/samples 键名逐字不动，任何档位均随包发送。
     */
    public void writeSync(NBTTagCompound tag) {
        tag.setString("cachedEu", cachedEu == null ? "0" : cachedEu.toString());
        tag.setDouble("cachedEut", cachedEut);
        tag.setString("cachedStatus", cachedStatus == null ? "" : cachedStatus);
        long worldTick = core.getWorldObj() == null ? -1L
            : core.getWorldObj()
                .getTotalWorldTime();
        boolean incremental = !fullSyncPending && syncSeq > 0L
            && !cachedSamples.isEmpty()
            && worldTick >= 0L
            && worldTick <= incrementalPushUntilTick;
        if (incremental && incrementalSinceFull >= FULL_SYNC_BACKSTOP_INTERVAL) {
            // 兜底轮换：给失步读端一次全量自愈（事件驱动，无周期推送）
            incremental = false;
        }
        NBTTagList list = new NBTTagList();
        if (incremental) {
            // 单点增量：仅最新 1 点，读端按 tick 去重追加
            list.appendTag(
                cachedSamples.get(cachedSamples.size() - 1)
                    .toNBT());
            incrementalSinceFull++;
        } else {
            for (NetworkInfoSample sample : cachedSamples) {
                list.appendTag(sample.toNBT());
            }
            fullSyncPending = false;
            incrementalSinceFull = 0;
        }
        tag.setTag("samples", list);
        tag.setLong("euSyncSeq", syncSeq);
        tag.setInteger("euSyncKind", incremental ? 1 : 0);
    }

    /**
     * S35 描述包 EU 缓存段读取（原 TE.readSyncData 的四键段，读取全守卫语义保持；G3 三档应用）：
     * euSyncKind=0 全量 → samples 整窗替换 + 记录 seq + 解除失步锁；
     * euSyncKind=1 增量 → 上次 seq+1 == 本次 seq 且未失步时按 tick 去重追加并回卷
     * {@link WindowChain#CAPACITY}（窗口语义不变 = 始终最新 61 点，与服务端 FIFO 一致），
     * 否则置失步锁：保留旧窗口、四键照常应用，等待下一次全量档包（冷启动/窗口切换/兜底轮换/
     * 新连接推送）整体替换自愈。
     */
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
        if (tag.hasKey("euSyncKind") && tag.hasKey("euSyncSeq")) {
            long seq = tag.getLong("euSyncSeq");
            NBTTagList list = tag.getTagList("samples", Constants.NBT.TAG_COMPOUND);
            if (tag.getInteger("euSyncKind") == 1) {
                if (!syncDesynced && lastSyncSeq == seq - 1L && !cachedSamples.isEmpty()) {
                    long newestTick = cachedSamples.get(cachedSamples.size() - 1).tick;
                    for (int i = 0; i < list.tagCount(); i++) {
                        NetworkInfoSample sample = NetworkInfoSample.fromNBT(list.getCompoundTagAt(i));
                        if (sample.tick > newestTick) {
                            cachedSamples.add(sample);
                        }
                    }
                    while (cachedSamples.size() > WindowChain.CAPACITY) {
                        cachedSamples.remove(0);
                    }
                } else {
                    // 失步：增量不可应用（旧窗口保留、四键已照常更新），锁定直至全量包
                    syncDesynced = true;
                }
                lastSyncSeq = seq;
            } else {
                cachedSamples.clear();
                for (int i = 0; i < list.tagCount(); i++) {
                    cachedSamples.add(NetworkInfoSample.fromNBT(list.getCompoundTagAt(i)));
                }
                lastSyncSeq = seq;
                syncDesynced = false;
            }
        } else if (tag.hasKey("samples")) {
            // 守卫回退：无 seq/kind 标签的 samples 按全量整窗替换
            // （同版本双端不出现该形态，B2-09 后区块 NBT 亦无四键，纯防御读取）
            cachedSamples.clear();
            NBTTagList list = tag.getTagList("samples", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < list.tagCount(); i++) {
                cachedSamples.add(NetworkInfoSample.fromNBT(list.getCompoundTagAt(i)));
            }
            lastSyncSeq = -1L;
            syncDesynced = false;
        }
    }
}
