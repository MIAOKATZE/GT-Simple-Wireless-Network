package com.miaokatze.gtswn.common.api.enums;

import com.miaokatze.gtswn.config.Config;

/**
 * 元机器实体 (MTE) ID 枚举
 * <p>
 * 模仿 NH-Utilities 的风格，为每个机器分配全局唯一的整数 ID。
 * 这种集中管理的方式可以有效避免与其他模组的机器 ID 发生冲突。
 * <p>
 * 最终 ID 计算公式：BASE (14600) + Config.metaIdOffset (配置偏移量) + relativeId (枚举内相对 ID)
 */
public enum MetaTileEntityID {

    // --- 单方块测试机器 ---
    /** EV 等级测试发电机 (Tier 4) */
    MTETEST_EV(0),
    /** IV 等级测试发电机 (Tier 5) */
    MTETEST_IV(1),
    /** LuV 等级测试发电机 (Tier 6) */
    MTETEST_LuV(2),

    // --- 无线电网监控机器 ---
    /** LV 等级无线能量监视器 (Tier 1) */
    WIRELESS_ENERGY_MONITOR(20),

    // --- 多方块测试机器 ---
    /** HV 等级测试多方块机器 (Tier 5) */
    MTETEST_MULTIBLOCK_HV(10),

    ;

    // 最终计算出的全局唯一 ID
    public final int ID;

    // ID 基准值，用于避免与其他模组的机器 ID 冲突
    private static final int BASE = 14600;

    /**
     * 构造函数：根据相对 ID 计算全局 ID
     * 
     * @param relativeId 枚举项在列表中的相对索引
     */
    MetaTileEntityID(int relativeId) {
        this.ID = BASE + Config.metaIdOffset + relativeId;
    }
}
