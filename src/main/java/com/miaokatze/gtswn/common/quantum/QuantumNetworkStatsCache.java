package com.miaokatze.gtswn.common.quantum;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.World;

import com.miaokatze.gtswn.common.performance.PerformanceAudit;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;

/**
 * 主线程量子网络频道统计缓存。
 *
 * <p>
 * 量子节点的维护周期彼此不一定对齐。按锚点、AE 网格实例、注册表 revision
 * 和 100 tick 时间桶缓存后，同一网络的多个节点不会重复洪泛控制器结构或遍历所有
 * 量子节点。此类只允许在服务器主线程调用；缓存内容为不可变快照。
 * </p>
 */
public final class QuantumNetworkStatsCache {

    /**
     * 统计时间桶宽（tick）。v1.6.20：由 20t 放宽到 100t——过载/预警统计时效从 ≤1s
     * 放宽到 ≤5s，3 分钟爆炸倒计时语义不变；结构变更仍走 revision 即时失效。
     */
    private static final long CACHE_INTERVAL_TICKS = 100L;
    private static final long RETAIN_BUCKETS = 4L;

    private static final Map<AnchorKey, CacheEntry> CACHE = new HashMap<>();
    private static long lastPruneBucket = Long.MIN_VALUE;

    private static long cacheHits;
    private static long cacheMisses;
    private static long rawComputations;
    private static long rawComputationNanos;

    private QuantumNetworkStatsCache() {}

    /**
     * 获取锚点网络统计；同一 100 tick 桶内只计算一次。
     *
     * @param key         锚点坐标键（调用方持有常驻实例复用，避免高频分配）
     * @param anchorWorld 锚点所在维度世界（仅用于取世界时间算桶）
     * @param grid        锚点控制器所属 ME 网格
     * @return 不可变快照；锚点结构不存在或网格暂不可用时返回 null
     */
    public static Snapshot getOrCompute(AnchorKey key, World anchorWorld, IGrid grid) {
        if (anchorWorld == null || grid == null) {
            return null;
        }

        long bucket = anchorWorld.getTotalWorldTime() / CACHE_INTERVAL_TICKS;
        prune(bucket);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(anchorWorld);
        long revision = registry.getRevision();
        CacheEntry cached = CACHE.get(key);
        if (cached != null && cached.bucket == bucket && cached.revision == revision && cached.grid == grid) {
            cacheHits++;
            // v1.6.19：性能审计——缓存命中计数
            PerformanceAudit.recordStatsHit();
            cached.lastAccessBucket = bucket;
            return cached.snapshot;
        }

        cacheMisses++;
        // v1.6.19：性能审计——缓存未命中计数
        PerformanceAudit.recordStatsMiss();
        long started = System.nanoTime();
        Set<Long> structure = QuantumControllerRegistry
            .floodControllers(anchorWorld, key.getX(), key.getY(), key.getZ());
        if (structure.isEmpty()) {
            return null;
        }

        int totalChannels = QuantumControllerRegistry.computeTotalChannels(structure);
        IMachineSet nodes = grid.getMachines(TileEntityNetworkQuantumNode.class);
        int usedChannels = 0;
        int quantumNodeCount = nodes.size();
        for (IGridNode node : nodes) {
            int nodeMax = 0;
            for (IGridConnection connection : node.getConnections()) {
                nodeMax = Math.max(nodeMax, connection.getUsedChannels());
            }
            usedChannels += nodeMax;
        }

        Snapshot snapshot = new Snapshot(totalChannels, usedChannels, quantumNodeCount, structure);
        CACHE.put(key, new CacheEntry(grid, bucket, revision, snapshot));
        rawComputations++;
        rawComputationNanos += System.nanoTime() - started;
        return snapshot;
    }

    /** 清理服务器切换或测试之间的运行时缓存。 */
    public static void clear() {
        CACHE.clear();
        lastPruneBucket = Long.MIN_VALUE;
        cacheHits = 0L;
        cacheMisses = 0L;
        rawComputations = 0L;
        rawComputationNanos = 0L;
    }

    /** 每秒由 ServerTick 低频输出一次 DEBUG 统计并归零。 */
    public static String consumeDebugStats() {
        String result = "statsHit=" + cacheHits
            + ", statsMiss="
            + cacheMisses
            + ", rawCompute="
            + rawComputations
            + ", rawComputeMs="
            + (rawComputationNanos / 1000000L);
        cacheHits = 0L;
        cacheMisses = 0L;
        rawComputations = 0L;
        rawComputationNanos = 0L;
        return result;
    }

    private static void prune(long currentBucket) {
        if (lastPruneBucket == currentBucket) {
            return;
        }
        lastPruneBucket = currentBucket;
        Iterator<Map.Entry<AnchorKey, CacheEntry>> iterator = CACHE.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            CacheEntry entry = iterator.next()
                .getValue();
            if (entry.lastAccessBucket < currentBucket - RETAIN_BUCKETS) {
                iterator.remove();
            }
        }
    }

    /** 不可变服务端统计快照。 */
    public static final class Snapshot {

        public final int totalChannels;
        public final int usedChannels;
        public final int quantumNodeCount;
        private final Set<Long> structure;

        private Snapshot(int totalChannels, int usedChannels, int quantumNodeCount, Set<Long> structure) {
            this.totalChannels = totalChannels;
            this.usedChannels = usedChannels;
            this.quantumNodeCount = quantumNodeCount;
            // v1.6.20：直接包装洪泛返回的集合（构造后无人修改，消费者只读）免拷贝
            this.structure = Collections.unmodifiableSet(structure);
        }

        public Set<Long> getStructure() {
            return this.structure;
        }
    }

    private static final class CacheEntry {

        private final IGrid grid;
        private final long bucket;
        private final long revision;
        private final Snapshot snapshot;
        private long lastAccessBucket;

        private CacheEntry(IGrid grid, long bucket, long revision, Snapshot snapshot) {
            this.grid = grid;
            this.bucket = bucket;
            this.revision = revision;
            this.snapshot = snapshot;
            this.lastAccessBucket = bucket;
        }
    }
}
