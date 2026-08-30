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
 * 汇总一个信息屏方块（坐标 key）当前全部被监视 key 的最新数量与 5 分钟平均速率，
 * 所有集合在构建时做防御拷贝并以不可变包装暴露，调用方不可修改。
 */
public final class AEMonitorSnapshot {

    /** 信息屏坐标字符串（{@code dimensionId:x:y:z}） */
    public final String panelKey;

    /** 每个被监视 key 的最新数量（key → 数量，不可变） */
    public final Map<String, BigInteger> currentAmounts;

    /**
     * 每个被监视 key 在 5 分钟窗口内的平均变化率（key → 每 1200 tick 即每分钟变化量，不可变），
     * 基于 5 分钟窗口首末两个采样点计算；样本不足或数据静止时为 0.0。
     */
    public final Map<String, Double> rates300s;

    /** 当前被监视的 key 集合（以 5 分钟窗口口径为准，不可变） */
    public final List<String> monitoredKeys;

    public AEMonitorSnapshot(String panelKey, Map<String, BigInteger> currentAmounts, Map<String, Double> rates300s,
        List<String> monitoredKeys) {
        this.panelKey = panelKey;
        this.currentAmounts = Collections.unmodifiableMap(new LinkedHashMap<>(currentAmounts));
        this.rates300s = Collections.unmodifiableMap(new LinkedHashMap<>(rates300s));
        this.monitoredKeys = Collections.unmodifiableList(new ArrayList<>(monitoredKeys));
    }
}
