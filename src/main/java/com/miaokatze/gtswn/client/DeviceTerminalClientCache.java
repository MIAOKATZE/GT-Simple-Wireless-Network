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
 * 防撕裂：同一 version 的分页到齐（0..pageTotal-1 全部收到且连续）才整体替换快照；
 * 更高 version 到达即开启新批次（旧批次页作废）；更低 version（旧包迟达）整页丢弃。
 * 数据版本跳变由服务端 LAST_SENT 版本跳过机制保证不会频繁重发。
 */
public final class DeviceTerminalClientCache {

    /** 终端实例 UUID → 缓存条目（仅客户端主线程访问：写入经 func_152344_a 调度，读取在 GUI 线程=主线程） */
    private static final Map<UUID, Cached> CACHE = new HashMap<>();

    private DeviceTerminalClientCache() {}

    /**
     * 接收单页（主线程）：同 version 分页累积，到齐整体替换；跨 version 自动切批。
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
        if (version > cached.version || cached.pendingPages == null
            || cached.pendingVersion != version
            || cached.pageTotal != pageTotal) {
            // 新版本批次开启（或服务端重新分页）：旧批次整体作废
            cached.pendingVersion = version;
            cached.pendingPages = new HashMap<>();
            cached.pageTotal = pageTotal;
        }
        cached.pendingPages.put(pageIndex, new ArrayList<>(page));
        if (cached.pendingPages.size() < pageTotal) {
            return;
        }
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
    }

    /**
     * 查询终端当前快照（无数据返回 null；GUI 开屏读缓存先渲染旧值）。
     */
    public static Snapshot getSnapshot(UUID terminalId) {
        Cached cached = CACHE.get(terminalId);
        return cached == null ? null : cached.snapshot;
    }

    /** 整体快照（不可变视图：version + 按绑定序的条目） */
    public static final class Snapshot {

        /** 快照数据版本（与服务端 TerminalData.version 对应） */
        public final long version;

        /** 机器条目（分页到齐拼装，整体替换） */
        public final List<Entry> entries;

        Snapshot(long version, List<Entry> entries) {
            this.version = version;
            this.entries = entries;
        }
    }

    /** 单终端缓存条目：已提交快照 + 进行中批次 */
    private static final class Cached {

        /** 已整体替换的最新版本 */
        long version = -1L;

        /** 已提交快照（null=尚未有任何完整批次） */
        Snapshot snapshot;

        /** 进行中批次版本 */
        long pendingVersion = -1L;

        /** 进行中批次总页数 */
        int pageTotal;

        /** 进行中批次已收页 */
        Map<Integer, List<Entry>> pendingPages;
    }
}
