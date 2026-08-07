package com.miaokatze.gtswn.common.performance;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.sun.management.ThreadMXBean;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 性能审计系统（v1.6.20）。
 * <p>
 * 全局开关 {@link #setEnabled(boolean)} 默认关闭；关闭时所有入口零开销且完全静默。
 * 开启后默认每 12000 tick（10 分钟，周期可配置）由 {@link ServerTickListener} 在 ServerTickEvent（END phase）
 * 触发一次 {@link #tickEnd()} 结算，输出 TPS / 本 mod 每 tick 耗时（MSTP）/ 量子节点与终端
 * 交互计数 / C→S 网络包计数 / 延迟分布 / 堆水位 / 线程分配 / 服务器快照 / JVM 堆与 GC 增量报告。
 * <p>
 * 用法：耗时采样 {@code long t0 = PerformanceAudit.start(); ... PerformanceAudit.record(t0);}，
 * 交互计数直接调用各 {@code record*} 方法；所有计数与计时仅在开关开启时生效。
 */
public final class PerformanceAudit {

    /** 报告周期：默认 12000 tick = 10 分钟（由 {@link #setReportIntervalMinutes} 配置，重启生效） */
    private static long REPORT_INTERVAL_TICKS = 12000L;

    /** 全局开关（preInit 配置读取后设置，重启生效） */
    private static volatile boolean enabled = false;

    // ==================== 本 tick 累计 ====================

    /** 本 tick 内本 mod 累计耗时（纳秒） */
    private static long perTickNanos = 0L;

    // ==================== 性能窗口统计 ====================

    private static long windowSumNanos = 0L;
    private static long windowMaxNanos = 0L;
    private static long windowTicks = 0L;

    // ==================== TPS 统计 ====================

    private static long tpsSumNs = 0L;
    private static long tpsMaxNs = 0L;
    private static long tpsCount = 0L;

    // ==================== 量子节点计数器 ====================

    private static long quantumMaintenance = 0L;
    private static long quantumTryConnect = 0L;
    private static long quantumBridgeSuccess = 0L;
    private static long quantumOverloadCheck = 0L;
    private static long quantumSyncPacket = 0L;
    private static long bridgeDestroyed = 0L;

    // ==================== 量子终端计数器 ====================

    private static long terminalRequest = 0L;
    private static long terminalAssembled = 0L;
    private static long terminalReply = 0L;

    // ==================== 量子化控制器巡检计数器 ====================

    private static long controllerSweep = 0L;
    private static long controllerFilterUpdate = 0L;
    private static long controllerMerge = 0L;

    // ==================== 统计缓存计数器 ====================

    private static long statsHit = 0L;
    private static long statsMiss = 0L;

    // ==================== 无线能源覆盖板计数器 ====================

    private static long wirelessTick = 0L;
    private static long wirelessDraw = 0L;
    private static long wirelessUpload = 0L;

    // ==================== 网络包计数器（C→S） ====================

    private static long packetTotal = 0L;
    private static long packetTerminalRequest = 0L;
    private static long packetWirelessEU = 0L;
    private static long packetInfoPanelConfig = 0L;
    private static long packetAETab = 0L;

    // ==================== JVM GC 基线 ====================

    /** 各收集器上次报告的累计 collectionCount/collectionTime（按收集器名区分 young/full） */
    private static final Map<String, long[]> GC_BASELINES = new HashMap<>();

    /** 上次报告时所有收集器的聚合基线（collectionCount/collectionTime，报告后更新） */
    private static long lastGcCount = 0L;
    private static long lastGcTime = 0L;

    // ==================== Tick 延迟分布统计 ====================

    private static long latencyLe20 = 0L;
    private static long latency20To50 = 0L;
    private static long latency50To100 = 0L;
    private static long latency100To200 = 0L;
    private static long latency200Plus = 0L;
    /** 窗口内最卡 tick 序号（1-based；0 = 本窗口尚无采样） */
    private static long worstTickIndex = 0L;

    // ==================== 堆水位统计（窗口内 min/avg/max 占用率） ====================

    private static double heapMinPct = 100.0D;
    private static double heapSumPct = 0.0D;
    private static double heapMaxPct = 0.0D;
    private static long heapSampleCount = 0L;

    // ==================== 线程分配统计（ThreadMXBean） ====================

    /** com.sun.management 扩展 bean；非 HotSpot 时为 null（指标输出 N/A） */
    private static ThreadMXBean threadMxBean = null;
    /** 服务端 tick 线程 id（首次 tickEnd 记录） */
    private static long serverThreadId = -1L;
    /** 上次采样累计分配字节（跨窗口保留的基线） */
    private static long lastAllocatedBytes = 0L;
    /** 窗口内分配字节增量 */
    private static long windowAllocatedBytes = 0L;
    /** 初始化/采样失败标记（失败后永久降级为 N/A） */
    private static boolean threadMxBeanFailed = false;

    private PerformanceAudit() {}

    /** 设置全局开关（preInit 配置读取后调用，重启生效） */
    public static void setEnabled(boolean auditEnabled) {
        enabled = auditEnabled;
    }

    /** 设置报告周期（分钟，1~60 钳制；preInit 配置读取后调用，重启生效） */
    public static void setReportIntervalMinutes(int minutes) {
        int clamped = Math.max(1, Math.min(60, minutes));
        REPORT_INTERVAL_TICKS = clamped * 1200L;
    }

    /** 全局开关是否开启（关闭时完全静默且零开销） */
    public static boolean enabled() {
        return enabled;
    }

    /** 开始一次耗时采样；关闭时返回 0（{@link #record} 自动跳过） */
    public static long start() {
        return enabled ? System.nanoTime() : 0L;
    }

    /** 结束一次耗时采样，把耗时累计进本 tick */
    public static void record(long startNanos) {
        if (startNanos != 0L) {
            perTickNanos += System.nanoTime() - startNanos;
        }
    }

    /**
     * 每 tick 结算（ServerTickEvent END 调用）。
     * <p>
     * 累计本 tick 耗时并采样服务器 TPS（tickTimeArray）与延迟分布、堆水位、线程分配；
     * 窗口满 REPORT_INTERVAL_TICKS 输出一次报告并归零。
     */
    public static void tickEnd() {
        if (!enabled) {
            return;
        }
        windowSumNanos += perTickNanos;
        windowMaxNanos = Math.max(windowMaxNanos, perTickNanos);
        perTickNanos = 0L;
        windowTicks++;
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            long mspt = server.tickTimeArray[server.getTickCounter() % 100];
            tpsSumNs += mspt;
            if (mspt > tpsMaxNs) {
                tpsMaxNs = mspt;
                worstTickIndex = windowTicks;
            }
            tpsCount++;
            // 延迟分布分桶（阈值单位 ms，按纳秒比较）
            if (mspt <= 20_000_000L) {
                latencyLe20++;
            } else if (mspt <= 50_000_000L) {
                latency20To50++;
            } else if (mspt <= 100_000_000L) {
                latency50To100++;
            } else if (mspt <= 200_000_000L) {
                latency100To200++;
            } else {
                latency200Plus++;
            }
        }
        // 线程分配采样（ThreadMXBean）：首次 tickEnd 初始化，失败即永久降级为 N/A
        if (threadMxBean == null && !threadMxBeanFailed) {
            try {
                java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
                if (base instanceof ThreadMXBean) {
                    threadMxBean = (ThreadMXBean) base;
                    serverThreadId = Thread.currentThread()
                        .getId();
                    lastAllocatedBytes = threadMxBean.getThreadAllocatedBytes(serverThreadId);
                }
            } catch (Throwable t) {
                threadMxBeanFailed = true; // 非 HotSpot / 不支持：置失败标记，输出 N/A
            }
        }
        if (threadMxBean != null) {
            try {
                long cur = threadMxBean.getThreadAllocatedBytes(serverThreadId);
                if (cur >= lastAllocatedBytes) {
                    windowAllocatedBytes += cur - lastAllocatedBytes;
                }
                lastAllocatedBytes = cur;
            } catch (Throwable t) {
                threadMxBean = null;
                threadMxBeanFailed = true;
            }
        }
        // 堆水位采样（窗口内 min/avg/max 占用率）
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        double heapPct = 100.0D * usedBytes / runtime.maxMemory();
        heapMinPct = Math.min(heapMinPct, heapPct);
        heapSumPct += heapPct;
        heapMaxPct = Math.max(heapMaxPct, heapPct);
        heapSampleCount++;
        if (windowTicks >= REPORT_INTERVAL_TICKS) {
            report();
            resetWindow();
        }
    }

    // ==================== 量子节点计数 ====================

    /** 量子节点连接维护次数（maintainConnection） */
    public static void recordQuantumMaintenance() {
        if (!enabled) return;
        quantumMaintenance++;
    }

    /** 量子节点建连尝试次数（tryConnect） */
    public static void recordQuantumTryConnect() {
        if (!enabled) return;
        quantumTryConnect++;
    }

    /** 量子节点桥接成功次数（createGridConnection 与收养既有直连） */
    public static void recordQuantumBridgeSuccess() {
        if (!enabled) return;
        quantumBridgeSuccess++;
    }

    /** 量子节点过载检查次数（checkNetworkOverload） */
    public static void recordQuantumOverloadCheck() {
        if (!enabled) return;
        quantumOverloadCheck++;
    }

    /** 量子节点在线状态同步包次数（markBlockForUpdate） */
    public static void recordQuantumSyncPacket() {
        if (!enabled) return;
        quantumSyncPacket++;
    }

    /** 桥接连接销毁次数（destroyBridgeConnection） */
    public static void recordBridgeDestroyed() {
        if (!enabled) return;
        bridgeDestroyed++;
    }

    // ==================== 量子终端计数 ====================

    /** 量子终端数据请求入队次数 */
    public static void recordTerminalRequest() {
        if (!enabled) return;
        terminalRequest++;
    }

    /** 量子终端网络数据装配成功次数 */
    public static void recordTerminalAssembled() {
        if (!enabled) return;
        terminalAssembled++;
    }

    /** 量子终端回包次数（正常回包 + 兜底回包） */
    public static void recordTerminalReply() {
        if (!enabled) return;
        terminalReply++;
    }

    // ==================== 量子化控制器巡检计数 ====================

    /** 量子化控制器世界巡检次数（sweepWorld） */
    public static void recordControllerSweep() {
        if (!enabled) return;
        controllerSweep++;
    }

    /** 连接过滤实际触发 AE2 updateState 的次数 */
    public static void recordControllerFilterUpdate() {
        if (!enabled) return;
        controllerFilterUpdate++;
    }

    /** D8 自动合并新控制器结构次数（mergeNewStructure） */
    public static void recordControllerMerge() {
        if (!enabled) return;
        controllerMerge++;
    }

    // ==================== 统计缓存计数 ====================

    /** 量子网络统计缓存命中次数 */
    public static void recordStatsHit() {
        if (!enabled) return;
        statsHit++;
    }

    /** 量子网络统计缓存未命中次数 */
    public static void recordStatsMiss() {
        if (!enabled) return;
        statsMiss++;
    }

    // ==================== 无线能源覆盖板计数 ====================

    /** 无线能源/动力覆盖板实际 tick 次数（doCoverThings） */
    public static void recordWirelessTick() {
        if (!enabled) return;
        wirelessTick++;
    }

    /** 下行补满成功次数（能源覆盖板从无线电网取电） */
    public static void recordWirelessDraw() {
        if (!enabled) return;
        wirelessDraw++;
    }

    /** 上行上传成功次数（动力覆盖板向无线电网送电） */
    public static void recordWirelessUpload() {
        if (!enabled) return;
        wirelessUpload++;
    }

    /**
     * 网络包接收计数（仅 C→S 请求包，按 discriminator 分类，其余归入合计）。
     *
     * @param discriminator 包 discriminator（0=无线EU请求 / 2=信息屏配置 / 3=AE标签状态 / 5=量子终端数据请求）
     */
    public static void recordPacketReceived(int discriminator) {
        if (!enabled) return;
        packetTotal++;
        switch (discriminator) {
            case 0:
                packetWirelessEU++;
                break;
            case 2:
                packetInfoPanelConfig++;
                break;
            case 3:
                packetAETab++;
                break;
            case 5:
                packetTerminalRequest++;
                break;
            default:
                break;
        }
    }

    // ==================== 报告结算 ====================

    /**
     * 输出性能窗口报告（默认 10 分钟，周期可配置；单次多行 INFO 日志）并更新 GC 基线。
     * <p>
     * TPS 口径：avgMspt = tpsSumNs / tpsCount / 1e6，平均 TPS = min(20, 1000 / avgMspt)，
     * avgMspt <= 0 时记 20.0；峰值每 tick 延迟 = tpsMaxNs / 1e6 ms。
     * 本 mod MSTP：窗口平均 = windowSumNanos / windowTicks / 1e6 ms，峰值 = windowMaxNanos / 1e6 ms。
     */
    private static void report() {
        double avgMspt = tpsCount > 0L ? (double) tpsSumNs / tpsCount / 1e6 : 0.0D;
        double avgTps = avgMspt <= 0.0D ? 20.0D : Math.min(20.0D, 1000.0D / avgMspt);
        double peakMspt = (double) tpsMaxNs / 1e6;
        double avgModMspt = windowTicks > 0L ? (double) windowSumNanos / windowTicks / 1e6 : 0.0D;
        double peakModMspt = (double) windowMaxNanos / 1e6;
        Runtime runtime = Runtime.getRuntime();
        long heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long heapMaxMb = runtime.maxMemory() / (1024L * 1024L);
        // GC 增量：各收集器当前累计值与上次报告基线相减（young/full 分别累计）
        long youngCount = 0L;
        long youngTime = 0L;
        long fullCount = 0L;
        long fullTime = 0L;
        long totalCount = 0L;
        long totalTime = 0L;
        // 有增量的收集器明细（名称=次数/耗时ms），供 JVM 行末尾展示；无增量时为空串
        StringBuilder gcDetailSb = new StringBuilder();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = bean.getName();
            long count = bean.getCollectionCount();
            long time = bean.getCollectionTime();
            totalCount += count;
            totalTime += time;
            long[] prev = GC_BASELINES.get(name);
            long deltaCount = 0L;
            long deltaTime = 0L;
            if (prev == null) {
                // 新收集器：建立基线，本次无增量
                GC_BASELINES.put(name, new long[] { count, time });
            } else if (count >= prev[0] && time >= prev[1]) {
                deltaCount = count - prev[0];
                deltaTime = time - prev[1];
                prev[0] = count;
                prev[1] = time;
            } else {
                // 收集器重建（统计清零）：重置该收集器基线，本次无增量
                GC_BASELINES.put(name, new long[] { count, time });
            }
            if (deltaCount > 0L || deltaTime > 0L) {
                if (gcDetailSb.length() > 0) {
                    gcDetailSb.append(' ');
                }
                gcDetailSb.append(name)
                    .append('=')
                    .append(deltaCount)
                    .append('/')
                    .append(deltaTime)
                    .append("ms");
            }
            if (isYoungCollector(name)) {
                youngCount += deltaCount;
                youngTime += deltaTime;
            } else {
                fullCount += deltaCount;
                fullTime += deltaTime;
            }
        }
        // 聚合基线随报告更新
        lastGcCount = totalCount;
        lastGcTime = totalTime;
        // GC 明细后缀（无增量收集器时为空串，不追加）
        String gcDetailSuffix = gcDetailSb.length() > 0 ? " [" + gcDetailSb + "]" : "";
        // 服务器快照（报告时刻：玩家 / 实体 / 区块；server 为 null 时输出 0）
        MinecraftServer server = MinecraftServer.getServer();
        int playerCount = 0;
        int entityCount = 0;
        int chunkCount = 0;
        if (server != null) {
            playerCount = server.getConfigurationManager()
                .getCurrentPlayerCount();
            for (WorldServer world : server.worldServers) {
                if (world != null) {
                    entityCount += world.loadedEntityList.size();
                    chunkCount += world.getChunkProvider()
                        .getLoadedChunkCount();
                }
            }
        }
        // 延迟分布 / 堆水位 / 线程分配（格式化字符串；无采样时输出 N/A）
        String heapMinStr = heapSampleCount > 0L ? String.format("%.1f", heapMinPct) : "N/A";
        String heapAvgStr = heapSampleCount > 0L ? String.format("%.1f", heapSumPct / heapSampleCount) : "N/A";
        String heapMaxStr = heapSampleCount > 0L ? String.format("%.1f", heapMaxPct) : "N/A";
        String threadAllocStr;
        if (threadMxBean != null) {
            double allocMb = windowAllocatedBytes / (1024.0D * 1024.0D);
            double allocKbPerTick = windowTicks > 0L ? windowAllocatedBytes / 1024.0D / windowTicks : 0.0D;
            threadAllocStr = String.format("%.1f MB (均 %.1f KB/t)", allocMb, allocKbPerTick);
        } else {
            threadAllocStr = "N/A";
        }
        GTSimpleWirelessNetwork.LOG.info(
            "[性能审计] ===== {} 分钟性能窗口 (共 {} tick) =====\n" + "[性能审计] TPS: 平均 {} (峰值每tick延迟 {} ms)\n"
                + "[性能审计] 本mod MSTP: 平均 {} ms/t | 峰值 {} ms/t\n"
                + "[性能审计] 量子节点: 维护={} 建连尝试={} 桥接成功={} 过载检查={} 同步包={} 桥接销毁={}\n"
                + "[性能审计] 终端: 请求={} 装配={} 回包={}\n"
                + "[性能审计] 巡检: sweep={} 过滤更新={} 合并={} | 统计缓存: 命中={} 未命中={}\n"
                + "[性能审计] 无线能源: 覆盖板tick={} 下行补满={} 上行上传={}\n"
                + "[性能审计] 网络包(C→S): 合计={} [终端请求={} 无线EU={} 信息屏配置={} AE标签={}]\n"
                + "[性能审计] 延迟分布: <=20ms={} 20-50ms={} 50-100ms={} 100-200ms={} >200ms={} | 最卡tick=#{}\n"
                + "[性能审计] 堆水位: min={}% avg={}% max={}%\n"
                + "[性能审计] 线程分配: {}\n"
                + "[性能审计] 服务器: 玩家={} 实体={} 区块={}\n"
                + "[性能审计] JVM: 堆={}MB/{}-MB | GC增量: young={}次/{}ms full={}次/{}ms{}",
            REPORT_INTERVAL_TICKS / 1200L,
            windowTicks,
            String.format("%.2f", avgTps),
            String.format("%.2f", peakMspt),
            String.format("%.3f", avgModMspt),
            String.format("%.3f", peakModMspt),
            quantumMaintenance,
            quantumTryConnect,
            quantumBridgeSuccess,
            quantumOverloadCheck,
            quantumSyncPacket,
            bridgeDestroyed,
            terminalRequest,
            terminalAssembled,
            terminalReply,
            controllerSweep,
            controllerFilterUpdate,
            controllerMerge,
            statsHit,
            statsMiss,
            wirelessTick,
            wirelessDraw,
            wirelessUpload,
            packetTotal,
            packetTerminalRequest,
            packetWirelessEU,
            packetInfoPanelConfig,
            packetAETab,
            latencyLe20,
            latency20To50,
            latency50To100,
            latency100To200,
            latency200Plus,
            worstTickIndex,
            heapMinStr,
            heapAvgStr,
            heapMaxStr,
            threadAllocStr,
            playerCount,
            entityCount,
            chunkCount,
            heapUsedMb,
            heapMaxMb,
            youngCount,
            youngTime,
            fullCount,
            fullTime,
            gcDetailSuffix);
    }

    /** 报告后归零窗口统计与所有计数器及 tps/延迟分布/堆水位/线程分配窗口统计（GC 基线、线程分配基线保留） */
    private static void resetWindow() {
        windowSumNanos = 0L;
        windowMaxNanos = 0L;
        windowTicks = 0L;
        tpsSumNs = 0L;
        tpsMaxNs = 0L;
        tpsCount = 0L;
        quantumMaintenance = 0L;
        quantumTryConnect = 0L;
        quantumBridgeSuccess = 0L;
        quantumOverloadCheck = 0L;
        quantumSyncPacket = 0L;
        bridgeDestroyed = 0L;
        terminalRequest = 0L;
        terminalAssembled = 0L;
        terminalReply = 0L;
        controllerSweep = 0L;
        controllerFilterUpdate = 0L;
        controllerMerge = 0L;
        statsHit = 0L;
        statsMiss = 0L;
        wirelessTick = 0L;
        wirelessDraw = 0L;
        wirelessUpload = 0L;
        packetTotal = 0L;
        packetTerminalRequest = 0L;
        packetWirelessEU = 0L;
        packetInfoPanelConfig = 0L;
        packetAETab = 0L;
        latencyLe20 = 0L;
        latency20To50 = 0L;
        latency50To100 = 0L;
        latency100To200 = 0L;
        latency200Plus = 0L;
        worstTickIndex = 0L;
        heapMinPct = 100.0D;
        heapSumPct = 0.0D;
        heapMaxPct = 0.0D;
        heapSampleCount = 0L;
        windowAllocatedBytes = 0L;
    }

    /** 判定收集器是否属于年轻代；其余归入 full 兜底 */
    private static boolean isYoungCollector(String name) {
        if (name == null) {
            return false;
        }
        return name.contains("Young") || name.contains("Copy")
            || name.contains("PS Scavenge")
            || name.contains("G1 Young");
    }

    /** ServerTickEvent（END phase）每 tick 结算一次性能审计窗口统计 */
    public static class ServerTickListener {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                PerformanceAudit.tickEnd();
            }
        }
    }
}
