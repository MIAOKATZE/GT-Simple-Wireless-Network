package com.miaokatze.gtswn.api.monitor;

import java.math.BigInteger;

/**
 * 历史采样点（不可变值对象，v1.7.9 新增）。
 * <p>
 * 统一承载电网与 AE 两侧的历史样本，字段换算语义按数据来源区分：
 * <ul>
 * <li>电网（{@code getNetworkHistory}）：{@code value} = 采样时 EU 存量，
 * {@code rate} = 瞬时 EU/t（基于最近两个采样点的斜率，正=充电，负=放电）</li>
 * <li>AE 监视（{@code getAEMonitorHistory}）：{@code value} = 采样时物品/流体数量，
 * {@code rate} = 每 1200 tick（= 1 分钟，20t/s × 60s）的变化量，
 * 派生层窗口（1h 及以上）的 rate 为上一级窗口最近 N 点均值</li>
 * </ul>
 */
public final class HistorySample {

    /** 采样时的世界 tick */
    public final long tick;

    /** 采样的真实时间戳（System.currentTimeMillis，毫秒） */
    public final long timeMs;

    /** 采样值：电网=EU 存量 / AE=物品或流体数量 */
    public final BigInteger value;

    /** 变化率：电网=EU/t / AE=每 1200 tick（每分钟）变化量 */
    public final double rate;

    public HistorySample(long tick, long timeMs, BigInteger value, double rate) {
        this.tick = tick;
        this.timeMs = timeMs;
        this.value = value == null ? BigInteger.ZERO : value;
        this.rate = rate;
    }
}
