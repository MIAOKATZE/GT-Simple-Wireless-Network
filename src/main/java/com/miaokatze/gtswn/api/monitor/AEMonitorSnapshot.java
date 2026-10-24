package com.miaokatze.gtswn.api.monitor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络信息屏 AE 监视快照（不可变值对象，v1.7.9 引入，v1.7.14 注释口径统一）。
 * <p>
 * 汇总一个信息屏方块（坐标 key）全部已存系列的最新数量与实际采样窗口平均速率，
 * 所有集合在构建时做防御拷贝并以不可变包装暴露，调用方不可修改。
 */
public final class AEMonitorSnapshot {

    /** 信息屏坐标字符串（{@code dimensionId:x:y:z}） */
    public final String panelKey;

    /** 每个被监视 key 的最新数量（key → 数量，不可变） */
    public final Map<String, BigInteger> currentAmounts;

    /**
     * 兼容旧调用方的别名，等同 {@link #averageRates}，不保证覆盖 300 秒。
     */
    @Deprecated
    public final Map<String, Double> rates300s;

    /** 每个系列首末实际采样 tick 算出的每分钟变化量（1200 tick/分钟）。 */
    public final Map<String, Double> averageRates;

    /** 平均值覆盖的实际游戏 tick 数；不足两点时为 0。旧构造器无跨度信息时为空。 */
    public final Map<String, Long> spanTicks;

    /** 无完整身份的旧历史 key，不能推测其 NBT 归属，且不会自动续接到新系列。 */
    public final List<String> legacyKeys;

    /** 基础窗口中已有历史的 key 集合，包含可由 legacyKeys 识别的旧系列（不可变）。 */
    public final List<String> monitoredKeys;

    public AEMonitorSnapshot(String panelKey, Map<String, BigInteger> currentAmounts, Map<String, Double> rates300s,
        List<String> monitoredKeys) {
        this(panelKey, currentAmounts, rates300s, monitoredKeys, Collections.emptyMap(), Collections.emptyList());
    }

    public AEMonitorSnapshot(String panelKey, Map<String, BigInteger> currentAmounts, Map<String, Double> averageRates,
        List<String> monitoredKeys, Map<String, Long> spanTicks, List<String> legacyKeys) {
        this.panelKey = panelKey;
        this.currentAmounts = Collections.unmodifiableMap(new LinkedHashMap<>(currentAmounts));
        this.averageRates = Collections.unmodifiableMap(new LinkedHashMap<>(averageRates));
        this.rates300s = this.averageRates;
        this.spanTicks = Collections.unmodifiableMap(new LinkedHashMap<>(spanTicks));
        this.legacyKeys = Collections.unmodifiableList(new ArrayList<>(legacyKeys));
        this.monitoredKeys = Collections.unmodifiableList(new ArrayList<>(monitoredKeys));
    }
}
