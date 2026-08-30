package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/**
 * 设备信息终端客户端缓存（阶段 D2；写入唯一入口
 * {@code ClientProxy.handleSyncDeviceTerminalData} 经 {@code Minecraft.func_152344_a}
 * 切主线程调用，读取方为阶段 E GUI——本类不引用任何 GUI 类）。
 * <p>
 * 缓存锚点 = 终端实例 UUID（不随 GUI 关闭清空，重开 GUI 立即有上次快照可显示）。
 * <p>
 * 防撕裂 + 增量分页提交（v1.7.14 G5）：服务端每轮最多回 2 页，同 version 分页跨多轮
 * <b>累积</b>（增量页到达只覆盖本页下标，不清空同批其他已收页；批次切换判据用
 * 「已提交版本与在途批次版本的较大者」，修复旧实现在途页逐页重开批导致多页终端
 * 永远等不到到齐的问题）。快照提交两档：
 * <ul>
 * <li><b>整批到齐</b>（0..pageTotal-1 全部收到）：按页序拼装整体替换（同旧语义）；</li>
 * <li><b>连续前缀</b>（未到齐）：拼装自页 0 起的连续前缀（遇缺口即止，绝不展示带洞
 * 数据），仅当前缀不短于当前已显示列表时提交——只增长不回缩，新批次早段 / 同版本
 * 重发不会把已显示的长列表闪缩回短前缀；后续页到达追平或整批到齐再替换。</li>
 * </ul>
 * 两档提交均推进 {@code cached.version}（更高 version 到达即开启新批次、旧批次页作废；
 * 更低 version 旧包迟达整页丢弃），GUI 据版本 + 条目数变化感知前缀增长并重排。
 */
public final class DeviceTerminalClientCache {

    /** 终端实例 UUID → 缓存条目（仅客户端主线程访问：写入经 func_152344_a 调度，读取在 GUI 线程=主线程） */
    private static final Map<UUID, Cached> CACHE = new HashMap<>();

    private DeviceTerminalClientCache() {}

    /**
     * 接收单页（主线程）：同 version 分页累积，整批到齐整体替换 / 未到齐提交连续前缀。
     */
    public static void receivePage(UUID terminalId, long version, int pageIndex, int pageTotal, List<Entry> page) {
        if (terminalId == null || pageTotal <= 0 || pageIndex < 0 || pageIndex >= pageTotal) {
            return;
        }
        Cached cached = CACHE.get(terminalId);
        if (cached == null) {
            cached = new Cached();
            CACHE.put(terminalId, cached);
        }
        if (version < cached.version) {
            // 旧版本页迟达（服务端已重发更高版本）：丢弃防回退
            return;
        }
        // 批次切换判据：已提交版本与在途批次版本的较大者（在途页重复到达不重开批，只幂等覆盖）
        long knownVersion = cached.pendingPages == null ? cached.version
            : Math.max(cached.version, cached.pendingVersion);
        if (version > knownVersion || cached.pendingPages == null
            || cached.pendingVersion != version
            || cached.pageTotal != pageTotal) {
            // 新版本批次开启（或服务端重新分页）：旧批次整体作废
            cached.pendingVersion = version;
            cached.pendingPages = new HashMap<>();
            cached.pageTotal = pageTotal;
        }
        // 同批次增量页：仅覆盖本页下标，其他已收页保留（不清空）
        cached.pendingPages.put(pageIndex, new ArrayList<>(page));
        if (cached.pendingPages.size() >= pageTotal) {
            // 分页到齐：按页序拼装整体替换（防撕裂）
            List<Entry> assembled = new ArrayList<>();
            for (int i = 0; i < pageTotal; i++) {
                List<Entry> part = cached.pendingPages.get(i);
                if (part == null) {
                    // 页序缺口（理论不可达：size==pageTotal 即全部在位）：放弃本次拼装
                    return;
                }
                assembled.addAll(part);
            }
            cached.snapshot = new Snapshot(version, assembled);
            cached.version = version;
            cached.pendingPages = null;
            return;
        }
        // 未到齐：拼装自页 0 起的连续前缀（遇缺口即止）
        List<Entry> prefix = new ArrayList<>();
        for (int i = 0; i < pageTotal; i++) {
            List<Entry> part = cached.pendingPages.get(i);
            if (part == null) {
                break;
            }
            prefix.addAll(part);
        }
        if (cached.snapshot == null || prefix.size() >= cached.snapshot.entries.size()) {
            // 只增长提交：前缀更长（或首见）才替换已显示列表，防新批次早段闪缩
            cached.snapshot = new Snapshot(version, prefix);
            cached.version = version;
        }
    }

    /**
     * 查询终端当前快照（无数据返回 null；GUI 开屏读缓存先渲染旧值）。
     * <p>
     * 增量分页期可能为部分快照（连续前缀）：条目数随批次推进增长，GUI 以版本 + 条目数
     * 双判据感知变化；空列表仅出现在整批到齐的空终端（无绑定）。
     */
    public static Snapshot getSnapshot(UUID terminalId) {
        Cached cached = CACHE.get(terminalId);
        return cached == null ? null : cached.snapshot;
    }

    /**
     * 当前批次是否仍有未到齐的页（v1.7.14 G5：GUI 滚轮翻页据此限频触发追加请求，
     * 服务端自 {@code pagesSent} 接力续页；无进行中批次 / 已整批到齐返回 false）。
     */
    public static boolean hasMorePending(UUID terminalId) {
        Cached cached = CACHE.get(terminalId);
        return cached != null && cached.pendingPages != null && cached.pendingPages.size() < cached.pageTotal;
    }

    /** 整体快照（不可变视图：version + 按绑定序的条目；增量分页期可能为连续前缀） */
    public static final class Snapshot {

        /** 快照数据版本（与服务端 TerminalData.version 对应） */
        public final long version;

        /** 机器条目（整批到齐拼装或增量期连续前缀，整体替换） */
        public final List<Entry> entries;

        Snapshot(long version, List<Entry> entries) {
            this.version = version;
            this.entries = entries;
        }
    }

    /** 单终端缓存条目：已提交快照 + 进行中批次 */
    private static final class Cached {

        /** 已提交快照的最新版本（前缀或整批，取较新提交者） */
        long version = -1L;

        /** 已提交快照（null=尚未有任何完整批次；部分前缀一旦提交即非 null） */
        Snapshot snapshot;

        /** 进行中批次版本 */
        long pendingVersion = -1L;

        /** 进行中批次总页数 */
        int pageTotal;

        /** 进行中批次已收页 */
        Map<Integer, List<Entry>> pendingPages;
    }
}
