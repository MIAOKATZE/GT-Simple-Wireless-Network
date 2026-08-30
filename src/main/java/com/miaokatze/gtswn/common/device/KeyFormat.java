package com.miaokatze.gtswn.common.device;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 机器键 {@code "dim:x:y:z"} 纯解析器（v0.8.0 G1 自 DeviceSampleScheduler.parseKey 抽取）。
 * <p>
 * 零 Minecraft 依赖（纯字符串运算，任意线程安全），供采样热点路径复用：
 * <ul>
 * <li>解析结果缓存（{@link ConcurrentHashMap}）：重复解析命中缓存直接返回，不再 split/parseInt</li>
 * <li>坏键也缓存（解析失败写入哨兵，避免坏键每轮反复 split 抛 NumberFormatException）</li>
 * <li>容量上限 {@value #CACHE_LIMIT}：超限整体清空重建（简单清理策略防膨胀；合法键全集受
 * 终端绑定数上限约束，超限只可能来自坏键风暴或会话内海量不同键，整体清空后热点键立即重建）</li>
 * <li>返回值为缓存条目的<b>克隆</b>：调用方可任意持有 / 改写返回数组，不会污染缓存内部条目</li>
 * </ul>
 * 语义与原 parseKey 逐字一致：四段 {@code split(":")} + 逐段 parseInt，负维度 / 负坐标合法，
 * 段数不为 4 或任段非整数（含溢出）返回 null；null 入参返回 null（不缓存）。
 * <p>
 * 【后续可迁移】DeviceScanManager.parseKey（:438-452）与 ScreenStructure.parseKey（:335 附近）
 * 存在同实现副本，分属其他编辑切片本切片不动，待各切片稳定后统一收敛到本类。
 */
public final class KeyFormat {

    /** 解析结果缓存容量上限（超限整体清空重建） */
    private static final int CACHE_LIMIT = 4096;

    /** 坏键缓存哨兵（ConcurrentHashMap 不容 null 值；零长度数组与合法结果 int[4] 天然区分） */
    private static final int[] INVALID = {};

    /** 解析结果缓存（合法 → int[4]；坏键 → {@link #INVALID} 哨兵） */
    private static final Map<String, int[]> CACHE = new ConcurrentHashMap<>();

    /** 实际执行 split/parseInt 的次数（缓存未命中计数，仅供单测观测缓存命中路径，业务勿读） */
    private static final AtomicInteger PARSE_COMPUTATIONS = new AtomicInteger();

    private KeyFormat() {}

    /**
     * 解析机器键 {@code dim:x:y:z} → [dim,x,y,z]（缓存结果的克隆）；格式坏返回 null（同样走缓存）。
     */
    public static int[] parse(String key) {
        if (key == null) {
            return null;
        }
        int[] cached = CACHE.get(key);
        if (cached == null) {
            cached = compute(key);
            if (CACHE.size() >= CACHE_LIMIT) {
                // 简单清理：整体清空（并发下最坏多算几次，无正确性影响）
                CACHE.clear();
            }
            CACHE.put(key, cached);
        }
        return cached == INVALID ? null : cached.clone();
    }

    /** 未命中缓存的实算（保持与原 DeviceSampleScheduler.parseKey 完全一致的语义） */
    private static int[] compute(String key) {
        PARSE_COMPUTATIONS.incrementAndGet();
        String[] parts = key.split(":");
        if (parts.length != 4) {
            return INVALID;
        }
        try {
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]) };
        } catch (NumberFormatException e) {
            return INVALID;
        }
    }

    /** 当前缓存条目数（仅供单测观测，业务勿读） */
    static int cacheSize() {
        return CACHE.size();
    }

    /** 累计实算次数（缓存未命中，仅供单测观测，业务勿读） */
    static int parseComputations() {
        return PARSE_COMPUTATIONS.get();
    }
}
