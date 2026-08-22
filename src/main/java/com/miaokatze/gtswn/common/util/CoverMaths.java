package com.miaokatze.gtswn.common.util;

/**
 * GTswn 覆盖板数学工具（SWN-BUG-06 + SWN-OPT-17/C-4 单源收敛）。
 * <p>
 * 覆盖板缓冲容量公式「电压 × 安培 × 800 tick」的唯一定义点——链路终端的聊天预告
 * 与能源覆盖板的实配计算必须共享同一实现，防止公式再次分叉漂移
 * （历史教训：终端侧曾与覆盖板各持一份实现并漏 (long) 提升，导致预告在 GT MAX 档溢出为负）。
 */
public final class CoverMaths {

    /** 缓冲等效充能时长（tick）：GT 覆盖板缓冲按 V×A 持续 800t（40s）的电量取值 */
    public static final long BUFFER_TICKS = 800L;

    private CoverMaths() {}

    /**
     * 覆盖板缓冲容量（EU）= 电压 × 安培 × 800 tick。
     * <p>
     * 全程 long 域运算（入参自动提升），消除 int×int 先行溢出；
     * 溢出上限 V×A×800 &gt; {@code Long.MAX_VALUE} 需安培达数十亿，物理不可达。
     *
     * @param voltage  电压（EU/t）
     * @param amperage 安培数（A）
     * @return 缓冲容量（EU）
     */
    public static long bufferCapacity(long voltage, long amperage) {
        return voltage * amperage * BUFFER_TICKS;
    }
}
