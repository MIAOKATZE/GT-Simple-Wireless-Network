package com.miaokatze.gtswn.crossmod.bq;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

/**
 * BetterQuesting 可选集成探测类（任务注入 PoC）。
 * <p>
 * 仅负责 preInit 阶段的 BQ 存在性探测与标志位维护。
 * 本类不得 import 或以任何形式静态引用 betterquesting 类型：
 * BQ 缺席时本类必须能安全加载（探测走 {@link Class#forName} 反射），
 * 所有 BQ 类型引用一律收敛到 {@link BqQuestInjector}，且只在
 * {@link #isBqLoaded()} 为 true 时才触发其类加载。
 * <p>
 * 探测模式对齐 GTIT 先例（NekoBqBridge.init 的 Class.forName 探测）。
 */
public final class BqCompat {

    /** BQ 是否已加载（preInit 探测结果） */
    private static boolean bqLoaded = false;

    private BqCompat() {}

    /**
     * preInit 探测 BQ 是否存在。
     * <p>
     * 通过反射检查 BQ API 标志类 {@code betterquesting.api.questing.IQuest}：
     * 类存在即视为 BQ 已加载；缺席时静默降级（标志保持 false）。
     */
    public static void detect() {
        try {
            Class.forName("betterquesting.api.questing.IQuest");
            bqLoaded = true;
            GTSimpleWirelessNetwork.LOG.info("[BQ] 检测到 BetterQuesting，任务注入集成启用");
        } catch (ClassNotFoundException e) {
            bqLoaded = false;
            GTSimpleWirelessNetwork.LOG.info("[BQ] 未检测到 BetterQuesting，任务注入集成静默停用");
        }
    }

    /**
     * @return BQ 是否已加载（preInit 探测结果）
     */
    public static boolean isBqLoaded() {
        return bqLoaded;
    }
}
