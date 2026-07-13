package com.miaokatze.gtswn.common.panel;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

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
 * <li>请求驱动：信息屏 updateEntity 每次执行时更新 dataSet.lastRequestTick，
 * 调度器仅对 5 分钟（6000t）内有请求的 dataSet 采样</li>
 * <li>多屏共享：同一 ownerUUID 只采样一次，所有信息屏从同一 {@link NetworkInfoDataSet} 读取</li>
 * <li>跨维度一致：统一使用 overworld (dimension 0) 的 {@link NetworkInfoDataStore}</li>
 * </ul>
 *
 * <p>
 * 注册位置：CommonProxy.init() 中调用
 * {@code FMLCommonHandler.instance().bus().register(new NetworkInfoMonitorScheduler())}
 *
 * <p>
 * 超时机制：chunk unload 时不主动 unregister，5 分钟内无请求的 dataSet 自然超时停止采样。
 * dataSet 本身保留（由 FIFO 自动溢出，不主动清理），玩家重新加载 chunk 后首 tick 即写入
 * lastRequestTick，恢复采样。
 *
 * <p>
 * 线程安全：采样逻辑在 ServerTickEvent END phase 执行（主线程），无需额外同步。
 */
public class NetworkInfoMonitorScheduler {

    /** 采样间隔（100t = 5s） */
    private static final long SAMPLE_INTERVAL_TICKS = 100L;

    /** 上次采样的世界 tick（用于 100t 间隔判定） */
    private long lastSampleTick = -1L;

    /**
     * 服务器 tick 事件处理（END phase 执行，避免与方块 updateEntity 冲突）。
     * <p>
     * END phase 确保所有方块的 updateEntity 已完成，调度器读取的数据是最新的。
     * 信息屏 updateEntity 在 END phase 之前执行，已更新 lastRequestTick，
     * 调度器据此判断哪些 dataSet 活跃。
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
    }

    /**
     * 采样所有活跃 dataSet（每 100t 执行一次）。
     * <p>
     * 遍历 {@link NetworkInfoDataStore#getActiveEntries} 返回的活跃条目（5 分钟内有请求的），
     * 从 WirelessNetworkManager 读取 EU 总量，写入 overworld 的 NetworkInfoDataStore。
     * <p>
     * 不再维护引用计数，活跃判定完全依赖信息屏 updateEntity 主动更新的 lastRequestTick。
     *
     * @param overworld overworld 世界实例（dimension 0），保证跨维度一致
     * @param tick      当前世界 tick
     */
    private void sampleAllActive(World overworld, long tick) {
        NetworkInfoDataStore store = NetworkInfoDataStore.get(overworld);
        long timeMs = System.currentTimeMillis();

        // 遍历活跃 dataSet（5 分钟内有请求的）
        // getActiveEntries 返回快照 List，避免 ConcurrentModificationException
        for (Map.Entry<String, NetworkInfoDataSet> entry : store.getActiveEntries(tick)) {
            try {
                String uid = entry.getKey();
                UUID uuid = UUID.fromString(uid);
                BigInteger eu = WirelessNetworkManager.getUserEU(uuid);
                if (eu == null) eu = BigInteger.ZERO;

                NetworkInfoDataSet dataSet = entry.getValue();
                // 全局采样锁（多屏共享去重，距离上次采样 ≥ 100t 才允许新采样）
                if (!dataSet.tryAcquireSampleLock(tick)) continue;

                // addSample 内部通过 eutDataSet 计算瞬时 EU/t
                dataSet.addSample(eu, tick, timeMs);
                store.markDirty();
            } catch (Exception e) {
                // 单个 dataSet 采样异常不影响其他 dataSet
                e.printStackTrace();
            }
        }
    }
}
