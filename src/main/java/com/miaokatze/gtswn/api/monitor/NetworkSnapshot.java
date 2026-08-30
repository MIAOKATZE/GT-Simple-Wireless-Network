package com.miaokatze.gtswn.api.monitor;

import java.math.BigInteger;
import java.util.UUID;

/**
 * 无线电网最新采样快照（不可变值对象，v1.7.9 引入，v1.7.14 注释口径统一）。
 * <p>
 * 对应监控数据集中 5 分钟窗口的最新采样点（即全局最新点），由调度器每 100t（5s）
 * 从 GT 无线网络读取 EU 总量并计算瞬时 EU/t 后写入。
 */
public final class NetworkSnapshot {

    /** 玩家 UUID（数据集 key 来源） */
    public final UUID ownerUuid;

    /** 采样时的世界 tick（overworld 基准） */
    public final long sampleTick;

    /** 采样的真实时间戳（System.currentTimeMillis，毫秒） */
    public final long sampleTimeMs;

    /** 采样时无线电网 EU 存量 */
    public final BigInteger euStored;

    /** 采样时瞬时 EU/t（基于最近两个采样点的斜率，正=充电，负=放电） */
    public final double eut;

    /**
     * 数据集是否处于活跃采样状态：最近 5 分钟（6000t）内有信息屏（或交互 API）请求，
     * 调度器正在持续为其采样；false 表示请求超时，历史数据保留但不再更新，
     * 直到下一次激活。注意该标志只反映监控活跃度，不等同于玩家在线或电网连通状态。
     */
    public final boolean online;

    public NetworkSnapshot(UUID ownerUuid, long sampleTick, long sampleTimeMs, BigInteger euStored, double eut,
        boolean online) {
        this.ownerUuid = ownerUuid;
        this.sampleTick = sampleTick;
        this.sampleTimeMs = sampleTimeMs;
        this.euStored = euStored == null ? BigInteger.ZERO : euStored;
        this.eut = eut;
        this.online = online;
    }
}
