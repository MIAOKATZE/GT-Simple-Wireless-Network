package com.miaokatze.gtswn.common.api.enums;

import com.miaokatze.gtswn.config.Config;

/**
 * 元机器实体 (MTE) ID 枚举
 * 模仿 NH-Utilities 的风格，为每个机器分配全局唯一的整数 ID。
 * 最终 ID 由基准值 (BASE) + 配置偏移量 (Config.metaIdOffset) + 枚举内相对 ID 组成。
 */
public enum MetaTileEntityID {

    // 测试机器的三个等级变体：EV, IV, LuV
    MTETEST_EV(0),
    MTETEST_IV(1),
    MTETEST_LuV(2),

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
