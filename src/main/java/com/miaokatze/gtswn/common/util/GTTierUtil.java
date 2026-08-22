package com.miaokatze.gtswn.common.util;

import gregtech.api.enums.GTValues;

/**
 * GT 电压等级工具类
 * <p>
 * 提供 GT 电压等级（ULV / LV / MV / HV / ... / MAX）的名称/颜色表与
 * EU/t → 电流+电压等级的格式化方法。
 * <p>
 * 电压数值单源引用 GT5U {@link GTValues#V}（SWN-OPT-04：本地硬拷贝已实际漂移——
 * MAX 档差 7、缺 error tier 第 15 档；GT5U 为必选依赖，直接改引消除静默漂移）。
 * 名称/颜色表仍为本 mod 展示层定义，档位上限 14=MAX 与两表长度对齐（15 档），
 * 不进入 GT5U 的 index 15 error tier（8589934592，防数组越界的哨兵档，非真实等级）。
 * <p>
 * 来源：合并自 {@code MTEWirelessEnergyMonitor} 与 {@code WirelessMonitorHUD} 的重复实现。
 */
public class GTTierUtil {

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
     * <li>选择初始 Tier：找到第一个满足 {@code absEU < GTValues.V[i] * 5} 的等级
     * （循环上界取名称表长度 15，不进入 GTValues.V 的 index 15 error tier 哨兵档）</li>
     * <li>计算电流：{@code amperage = ceil(absEU / GTValues.V[tier])}</li>
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
        for (int i = 0; i < TIER_NAMES.length; i++) {
            if (absEU < GTValues.V[i] * 5) {
                tier = i;
                break;
            }
            if (i == TIER_NAMES.length - 1) {
                tier = i;
            }
        }

        // B2-07：amperage 保持 double——(int) 强转在 absEU ≥ 4.6×10^18 EU/t 时会回绕成负数，
        // 令「amperage > 4」过载升级误判为不过载；此处仅参与比较，无显示语义
        double amperage = Math.ceil(absEU / GTValues.V[tier]);
        if (amperage > 4 && tier < TIER_NAMES.length - 1) {
            tier++;
            amperage = Math.ceil(absEU / GTValues.V[tier]);
            while (amperage > 4 && tier < TIER_NAMES.length - 1) {
                tier++;
                amperage = Math.ceil(absEU / GTValues.V[tier]);
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
        long voltage = GTValues.V[tier];
        double absEU = Math.abs(euPerTick);
        // B2-07：显示层封顶 999A——(int) 强转在 absEU ≥ 4.6×10^18 EU/t 时回绕成负数，
        // 过载判定用未截断的 amp 先行，显示值 Math.min 封顶 999 避免回绕
        double amp = Math.ceil(absEU / voltage);
        int amperage = (int) Math.min(amp, 999.0);

        boolean isOverloaded = false;
        if (amp > 4 && tier == TIER_NAMES.length - 1) {
            // 已到 MAX 级仍超 4A，截断为 4A 并标记过载
            isOverloaded = true;
            amperage = 4;
        } else if (amp > 4) {
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
