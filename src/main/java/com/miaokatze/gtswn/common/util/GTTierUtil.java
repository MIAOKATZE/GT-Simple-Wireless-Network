package com.miaokatze.gtswn.common.util;

/**
 * GT 电压等级工具类
 * <p>
 * 提供 GT 电压等级（ULV / LV / MV / HV / ... / MAX）的数组定义与
 * EU/t → 电流+电压等级的格式化方法。
 * 仅依赖 JDK，不引用本模组业务类，避免循环依赖。
 * <p>
 * 来源：合并自 {@code MTEWirelessEnergyMonitor} 与 {@code WirelessMonitorHUD} 的重复实现。
 */
public class GTTierUtil {

    /**
     * GT 电压等级定义（每安培的 EU/t）
     * <p>
     * 索引：0=ULV, 1=LV, 2=MV, 3=HV, 4=EV, 5=IV, 6=LuV, 7=ZPM, 8=UV,
     * 9=UHV, 10=UEV, 11=UIV, 12=UMV, 13=UXV, 14=MAX
     */
    public static final long[] VOLTAGES = { 8L, // ULV
        32L, // LV
        128L, // MV
        512L, // HV
        2048L, // EV
        8192L, // IV
        32768L, // LuV
        131072L, // ZPM
        524288L, // UV
        2097152L, // UHV
        8388608L, // UEV
        33554432L, // UIV
        134217728L, // UMV
        536870912L, // UXV
        2147483647L // MAX
    };

    /**
     * GT 电压等级名称
     */
    public static final String[] TIER_NAMES = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV",
        "UIV", "UMV", "UXV", "MAX" };

    /**
     * GT 电压等级颜色代码（使用 Minecraft 原生 16 色）
     */
    public static final String[] TIER_COLORS = { "§7", // ULV - 灰色
        "§7", // LV - 灰色
        "§b", // MV - 亮蓝色
        "§9", // HV - 蓝色
        "§3", // EV - 青色
        "§3", // IV - 青色
        "§a", // LuV - 绿色
        "§e", // ZPM - 黄色
        "§e", // UV - 黄色
        "§6", // UHV - 金色
        "§6", // UEV - 金色
        "§c", // UIV - 红色
        "§c", // UMV - 红色
        "§4", // UXV - 深红色
        "§4" // MAX - 深红色
    };

    /**
     * 获取 EU/t 对应的 GT 电压等级索引
     * <p>
     * 算法步骤：
     * <ol>
     * <li>选择初始 Tier：找到第一个满足 {@code absEU < VOLTAGES[i] * 5} 的等级</li>
     * <li>计算电流：{@code amperage = ceil(absEU / VOLTAGES[tier])}</li>
     * <li>过载升级：若 {@code amperage > 4} 且未到 MAX，则升一级重新计算电流，直到电流 ≤ 4 或到 MAX</li>
     * </ol>
     * 合并自 HUD 的 {@code getGTTier}（MTE 原版将此逻辑内联在 formatGTPower 中，逻辑等价）。
     *
     * @param euPerTick 每秒能量变化率
     * @return 电压等级索引（0=ULV, ..., 14=MAX）
     */
    public static int getGTTier(double euPerTick) {
        double absEU = Math.abs(euPerTick);
        int tier = 0;
        for (int i = 0; i < VOLTAGES.length; i++) {
            if (absEU < VOLTAGES[i] * 5) {
                tier = i;
                break;
            }
            if (i == VOLTAGES.length - 1) {
                tier = i;
            }
        }

        int amperage = (int) Math.ceil(absEU / VOLTAGES[tier]);
        if (amperage > 4 && tier < VOLTAGES.length - 1) {
            tier++;
            amperage = (int) Math.ceil(absEU / VOLTAGES[tier]);
            while (amperage > 4 && tier < VOLTAGES.length - 1) {
                tier++;
                amperage = (int) Math.ceil(absEU / VOLTAGES[tier]);
            }
        }
        return tier;
    }

    /**
     * 将 EU/t 转换为 GT 的电流+电压等级格式
     * <p>
     * 采用 HUD 版实现（先调用 {@link #getGTTier} 再格式化），与 MTE 原内联版逻辑等价但更清晰。
     * <p>
     * 输出示例：{@code §92A HV}（蓝色 2A HV）、{@code §64A MAX+}（金色 4A MAX+ 过载）。
     *
     * @param euPerTick 每秒能量变化率
     * @return 格式化后的字符串（带 § 颜色代码），例如 {@code §92A HV}
     */
    public static String formatGTPower(double euPerTick) {
        int tier = getGTTier(euPerTick);
        long voltage = VOLTAGES[tier];
        double absEU = Math.abs(euPerTick);
        int amperage = (int) Math.ceil(absEU / voltage);

        boolean isOverloaded = false;
        if (amperage > 4 && tier == VOLTAGES.length - 1) {
            // 已到 MAX 级仍超 4A，截断为 4A 并标记过载
            isOverloaded = true;
            amperage = 4;
        } else if (amperage > 4) {
            // 已被 getGTTier 处理过，正常情况不应再超过 4A（防御性兜底）
            isOverloaded = true;
            amperage = 4;
        }

        String color = TIER_COLORS[tier];
        String tierName = TIER_NAMES[tier];

        if (isOverloaded) {
            return color + amperage + "A " + tierName + "+";
        } else {
            return color + amperage + "A " + tierName;
        }
    }
}
