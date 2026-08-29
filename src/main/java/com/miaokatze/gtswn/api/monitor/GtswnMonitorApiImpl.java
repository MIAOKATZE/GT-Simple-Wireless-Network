package com.miaokatze.gtswn.api.monitor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;
import com.miaokatze.gtswn.common.panel.AEMonitorDataStore;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoDataSet;
import com.miaokatze.gtswn.common.panel.NetworkInfoDataStore;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;

import cpw.mods.fml.common.FMLCommonHandler;
import gregtech.common.misc.WirelessNetworkManager;

/**
 * 监控 API 默认实现（包私有，不属于公共 javadoc 契约，外部勿直接引用）。
 * <p>
 * 只读查询走 {@code getIfPresent} 防 getOrCreate 副作用；激活/即时采样走
 * {@code getOrCreate}（与信息屏语义一致）。全部方法可安全在客户端逻辑侧调用
 * （返回 empty/false），服务器/overworld 未就绪时同样安全返回。
 */
class GtswnMonitorApiImpl implements IGtswnMonitorApi, IGtswnMonitorControlApi {

    /** 窗口编号合法上限（0-7 共 8 级窗口，常量单源在 WindowChain） */
    private static final int WINDOW_ID_MAX = 7;

    @Override
    public Optional<NetworkSnapshot> getNetworkSnapshot(UUID owner) {
        World overworld = serverOverworld();
        if (overworld == null || owner == null) return Optional.empty();

        NetworkInfoDataSet dataSet = NetworkInfoDataStore.get(overworld)
            .getIfPresent(owner.toString());
        if (dataSet == null) return Optional.empty();

        NetworkInfoSample newest = dataSet.newest();
        if (newest == null) return Optional.empty();

        boolean online = dataSet.isRequestActive(overworld.getTotalWorldTime());
        return Optional.of(new NetworkSnapshot(owner, newest.tick, newest.timeMs, newest.eu, newest.eut, online));
    }

    @Override
    public Optional<HistoryWindow> getNetworkHistory(UUID owner, int windowId) {
        World overworld = serverOverworld();
        if (overworld == null || owner == null || !isValidWindow(windowId)) return Optional.empty();

        NetworkInfoDataSet dataSet = NetworkInfoDataStore.get(overworld)
            .getIfPresent(owner.toString());
        if (dataSet == null) return Optional.empty();

        List<NetworkInfoSample> raw = dataSet.query(windowId);
        List<HistorySample> samples = new ArrayList<>(raw.size());
        for (NetworkInfoSample sample : raw) {
            // 电网侧换算：value=EU 存量，rate=瞬时 EU/t
            samples.add(new HistorySample(sample.tick, sample.timeMs, sample.eu, sample.eut));
        }
        return Optional.of(new HistoryWindow(windowId, samples));
    }

    @Override
    public Optional<AEMonitorSnapshot> getAEMonitorSnapshot(World panelWorld, String panelKey) {
        AEMonitorDataSet dataSet = aeDataSet(panelWorld, panelKey);
        if (dataSet == null) return Optional.empty();

        Map<String, BigInteger> amounts = new LinkedHashMap<>();
        Map<String, Double> rates = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (String key : dataSet.getKeys()) {
            AEMonitorSample newest = dataSet.newest(key);
            if (newest == null) continue;
            keys.add(key);
            amounts.put(key, BigInteger.valueOf(newest.amount));
            rates.put(key, dataSet.averageRate300s(key));
        }
        if (keys.isEmpty()) return Optional.empty();
        return Optional.of(new AEMonitorSnapshot(panelKey, amounts, rates, keys));
    }

    @Override
    public Optional<HistoryWindow> getAEMonitorHistory(World panelWorld, String panelKey, String subKey, int windowId) {
        if (subKey == null || subKey.isEmpty() || !isValidWindow(windowId)) return Optional.empty();
        AEMonitorDataSet dataSet = aeDataSet(panelWorld, panelKey);
        if (dataSet == null) return Optional.empty();

        List<AEMonitorSample> raw = dataSet.query(subKey, windowId);
        List<HistorySample> samples = new ArrayList<>(raw.size());
        for (AEMonitorSample sample : raw) {
            // AE 侧换算：value=物品/流体数量，rate=每 1200 tick（每分钟）变化量
            samples.add(new HistorySample(sample.tick, sample.timeMs, BigInteger.valueOf(sample.amount), sample.rate));
        }
        return Optional.of(new HistoryWindow(windowId, samples));
    }

    @Override
    public boolean activateNetworkDataset(UUID owner) {
        World overworld = serverOverworld();
        if (overworld == null || owner == null) return false;

        // 激活语义对齐信息屏每 tick 请求刷新（EUCacheBridge.tickServer 首段）
        NetworkInfoDataStore.get(overworld)
            .getOrCreate(owner.toString())
            .updateRequestTick(overworld.getTotalWorldTime());
        return true;
    }

    @Override
    public boolean requestNetworkSample(UUID owner) {
        World overworld = serverOverworld();
        if (overworld == null || owner == null) return false;

        NetworkInfoDataStore store = NetworkInfoDataStore.get(overworld);
        NetworkInfoDataSet dataSet = store.getOrCreate(owner.toString());
        long tick = overworld.getTotalWorldTime();
        // 采样锁：100t 内已有采样则去重（多屏共享语义一致）
        if (!dataSet.tryAcquireSampleLock(tick)) return false;

        // 与调度器单条采样体一致：读 GT 无线网络 EU → addSample（内部计算瞬时 EU/t）→ markDirty
        BigInteger eu = WirelessNetworkManager.getUserEU(owner);
        if (eu == null) eu = BigInteger.ZERO;
        dataSet.addSample(eu, tick, System.currentTimeMillis());
        store.markDirty();
        return true;
    }

    /** 取 AE 监视数据集（只读查询，panelWorld/panelKey 非法或数据集不存在返回 null） */
    private static AEMonitorDataSet aeDataSet(World panelWorld, String panelKey) {
        if (panelWorld == null || panelKey == null || panelKey.isEmpty()) return null;
        return AEMonitorDataStore.get(panelWorld)
            .getIfPresent(panelKey);
    }

    /** 窗口编号合法性（0-7） */
    private static boolean isValidWindow(int windowId) {
        return windowId >= 0 && windowId <= WINDOW_ID_MAX;
    }

    /** 当前是否处于客户端逻辑侧（无服务器数据可查） */
    private static boolean isLogicalClient() {
        return FMLCommonHandler.instance()
            .getEffectiveSide()
            .isClient();
    }

    /** 取服务器 overworld；客户端逻辑侧或服务器未启动返回 null */
    private static World serverOverworld() {
        if (isLogicalClient()) return null;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return null;
        return server.worldServerForDimension(0);
    }
}
