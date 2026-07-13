package com.miaokatze.gtswn.common.panel;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.common.misc.WirelessNetworkManager;

/**
 * 无线 EU 监控调度器（per-player 全局采样）。
 *
 * <p>
 * 设计目标：
 * <ul>
 * <li>服务器校时：基于 {@link TickEvent.ServerTickEvent}，每 100t（5s）统一采样一次</li>
 * <li>按需启停：维护活跃 UID 引用计数，无信息屏的 UID 不采样</li>
 * <li>多屏共享：同一 UID 只采样一次，所有信息屏从同一 {@link NetworkInfoDataSet} 读取</li>
 * <li>跨维度一致：统一使用 overworld (dimension 0) 的 {@link NetworkInfoDataStore}</li>
 * </ul>
 *
 * <p>
 * 注册位置：CommonProxy.init() 中调用
 * {@code FMLCommonHandler.instance().bus().register(new NetworkInfoMonitorScheduler())}
 *
 * <p>
 * 线程安全：activeUIDs 使用 {@link ConcurrentHashMap}，register/unregister 可在不同维度线程调用。
 * 采样逻辑在 ServerTickEvent END phase 执行（主线程），无需额外同步。
 */
public class NetworkInfoMonitorScheduler {

    /** 采样间隔（100t = 5s，与原 SAMPLE_INTERVAL_TICKS 一致） */
    private static final long SAMPLE_INTERVAL_TICKS = 100L;

    /** 安全网清理间隔（6000t = 5min） */
    private static final long CLEANUP_INTERVAL_TICKS = 6000L;

    /**
     * 活跃 UID 引用计数。
     * <p>
     * key: ownerUUID.toString()
     * <br>
     * value: 引用该 UID 的信息屏数量（chunk loaded + valid）
     */
    private final Map<String, Integer> activeUIDs = new ConcurrentHashMap<>();

    /** 上次采样的世界 tick（用于 100t 间隔判定） */
    private long lastSampleTick = -1L;

    /** 上次安全网清理的世界 tick */
    private long lastCleanupTick = 0L;

    /**
     * 注册活跃 UID（信息屏 updateEntity 首次执行时调用）。
     * <p>
     * 使用 merge 原子操作递增计数，线程安全。
     *
     * @param uid ownerUUID.toString()；null 或空字符串安全跳过
     */
    public void register(String uid) {
        if (uid == null || uid.isEmpty()) return;
        activeUIDs.merge(uid, 1, Integer::sum);
    }

    /**
     * 注销活跃 UID（信息屏 invalidate/onChunkUnload 时调用）。
     * <p>
     * 使用 computeIfPresent 原子操作递减计数，计数归 0 时自动移除键。
     *
     * @param uid ownerUUID.toString()；null 或空字符串安全跳过
     */
    public void unregister(String uid) {
        if (uid == null || uid.isEmpty()) return;
        activeUIDs.computeIfPresent(uid, (k, v) -> v <= 1 ? null : v - 1);
    }

    /**
     * 服务器 tick 事件处理（END phase 执行，避免与方块 updateEntity 冲突）。
     * <p>
     * END phase 确保所有方块的 updateEntity 已完成，调度器读取的数据是最新的。
     *
     * @param event 服务器 tick 事件
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // 仅在 END phase 执行，确保方块 updateEntity 已完成
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;

        // 使用 overworld 的 getTotalWorldTime() 作为 tick 基准，
        // 与 TileEntity.updateEntity() 中的 worldObj.getTotalWorldTime() 保持一致，
        // 确保 NetworkInfoDataSet.tryAcquireSampleLock 的 100t 间隔判定正确。
        // 不使用 MinecraftServer.getTickCounter()，因为：
        // 1) getTickCounter() 返回 int（约 3.4 年后溢出），getTotalWorldTime() 返回 long
        // 2) 两者基准不同，混用会导致 tryAcquireSampleLock 失效
        World overworld = server.worldServerForDimension(0);
        if (overworld == null) return;
        long tick = overworld.getTotalWorldTime();

        // 每 100t 采样一次
        if (lastSampleTick < 0L || tick - lastSampleTick >= SAMPLE_INTERVAL_TICKS) {
            lastSampleTick = tick;
            sampleAllActive(overworld, tick);
        }

        // 每 6000t 安全网清理一次（重建引用计数）
        if (tick - lastCleanupTick >= CLEANUP_INTERVAL_TICKS) {
            lastCleanupTick = tick;
            safetyNetCleanup(server);
        }
    }

    /**
     * 采样所有活跃 UID（每 100t 执行一次）。
     * <p>
     * 遍历 activeUIDs 中的所有 UID，从 WirelessNetworkManager 读取 EU 总量，
     * 写入 overworld 的 NetworkInfoDataStore。
     *
     * @param overworld overworld 世界实例（dimension 0），保证跨维度一致
     * @param tick      当前世界 tick
     */
    private void sampleAllActive(World overworld, long tick) {
        if (activeUIDs.isEmpty()) return;

        NetworkInfoDataStore store = NetworkInfoDataStore.get(overworld);
        long timeMs = System.currentTimeMillis();

        // 遍历活跃 UID，逐个采样
        // ConcurrentHashMap 迭代器弱一致，无 ConcurrentModificationException
        for (String uid : activeUIDs.keySet()) {
            try {
                UUID uuid = UUID.fromString(uid);
                BigInteger eu = WirelessNetworkManager.getUserEU(uuid);
                if (eu == null) eu = BigInteger.ZERO;

                NetworkInfoDataSet dataSet = store.getOrCreate(uid);
                // 全局采样锁（防御性，调度器单线程但保留锁以防异常重入）
                // 与 TileEntity.sampleNetwork 共享同一 lastSampleTick，保证不重复采样
                if (!dataSet.tryAcquireSampleLock(tick)) continue;

                // v1.5.16：addSample 内部通过 eutDataSet 计算瞬时 EU/t，不再需要外部传入 eut
                dataSet.addSample(eu, tick, timeMs);
                store.markDirty();
            } catch (Exception e) {
                // 单个 UID 采样异常不影响其他 UID
                e.printStackTrace();
            }
        }
    }

    /**
     * 安全网清理：重建 activeUIDs 引用计数。
     *
     * <p>
     * 目的：防止 chunk unload 异常导致计数泄漏（计数 > 0 但实际无屏）。
     * 触发场景：信息屏 onChunkUnload 未被调用、维度卸载异常等。
     *
     * <p>
     * 实现：遍历所有 loaded world 的 loaded TileEntities，统计每个 UID 的实际屏数，
     * 用实际计数替换原计数（原子操作）。
     *
     * @param server MinecraftServer 实例
     */
    private void safetyNetCleanup(MinecraftServer server) {
        Map<String, Integer> recount = new ConcurrentHashMap<>();

        // 遍历所有维度的已加载 TileEntity，统计每个 UID 的实际信息屏数量
        for (World world : server.worldServers) {
            if (world == null) continue;
            // world.loadedTileEntityList 是 List<TileEntity>，迭代时弱一致
            for (TileEntity te : world.loadedTileEntityList) {
                if (!(te instanceof TileEntityNetworkInfoPanel)) continue;
                TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) te;
                // 跳过已 invalidate 的方块（无效方块不计入引用计数）
                if (panel.isInvalid()) continue;
                UUID ownerUUID = panel.getOwnerUUID();
                if (ownerUUID == null) continue;
                String uid = ownerUUID.toString();
                recount.merge(uid, 1, Integer::sum);
            }
        }

        // 用实际计数替换原计数（原子操作）
        // 注意：clear + putAll 不是原子的，但安全网清理本身是 best-effort 的防御性操作，
        // 短暂的不一致不影响正确性（最多导致一次多采样或少采样）
        activeUIDs.clear();
        activeUIDs.putAll(recount);
    }
}
