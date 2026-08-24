package com.miaokatze.gtswn.common.util;

import com.miaokatze.gtswn.config.Config;

/**
 * GTswn 覆盖板数学工具（SWN-BUG-06 + SWN-OPT-17/C-4 单源收敛）。
 * <p>
 * 覆盖板缓冲容量公式「电压 × 安培 ×（基础值 + 冗余值）tick」的唯一定义点
 * （Config.interactionRateTicks + Config.bufferRedundancyTicks，默认 600+200=800）——
 * 链路终端的聊天预告与能源覆盖板的实配计算必须共享同一实现，防止公式再次分叉漂移
 * （历史教训：终端侧曾与覆盖板各持一份实现并漏 (long) 提升，导致预告在 GT MAX 档溢出为负）。
 */
public final class CoverMaths {

    private CoverMaths() {}

    /**
     * 覆盖板缓冲容量（EU）= 电压 × 安培 ×（基础值 + 冗余值）
     * （Config.interactionRateTicks + Config.bufferRedundancyTicks，默认 600+200=800 tick 当量）。
     * <p>
     * 全程 long 域运算（入参自动提升），消除 int×int 先行溢出；
     * 溢出上限 V×A×（基础值+冗余值）&gt; {@code Long.MAX_VALUE} 需安培达数十亿，物理不可达。
     *
     * @param voltage  电压（EU/t）
     * @param amperage 安培数（A）
     * @return 缓冲容量（EU）
     */
    public static long bufferCapacity(long voltage, long amperage) {
        return voltage * amperage * (Config.interactionRateTicks + Config.bufferRedundancyTicks);
    }
}
