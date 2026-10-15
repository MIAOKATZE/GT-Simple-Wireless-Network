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
 * 名称表保留本 mod 的 MAX 展示名称；颜色直接引用 GT5U 标准，包含高等级的格式修饰。
 * 档位上限 14=MAX 与两表长度对齐（15 档），
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
     * GT5U 电压等级格式色，保留原生下划线、粗体等高等级修饰。
     */
    public static final String[] TIER_COLORS = java.util.Arrays.copyOf(GTValues.TIER_COLORS, TIER_NAMES.length);

    /** Standard Minecraft text RGB for a GT tier; unknown tiers have no voltage tint. */
    public static int tierRgb(int tier) {
        if (tier < 0 || tier >= TIER_COLORS.length) return -1;
        String formatting = TIER_COLORS[tier];
        for (int i = 0; i + 1 < formatting.length(); i++) {
            if (formatting.charAt(i) != '§') continue;
            int code = Character.digit(formatting.charAt(++i), 16);
            if (code < 0) continue;
            int bright = (code >> 3 & 1) * 85;
            int red = (code >> 2 & 1) * 170 + bright;
            int green = (code >> 1 & 1) * 170 + bright;
            int blue = (code & 1) * 170 + bright;
            if (code == 6) red += 85;
            return red << 16 | green << 8 | blue;
        }
        return -1;
    }

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
     * 将 EU/t 转换为 GT 的电流+电压等级格式（统一方法的整数调用侧薄委托）。
     * <p>
     * v1.7.4 起与设备终端「电压等级」计数模式统一为同一实现（均 1 位小数）；
     * 本方法取绝对值委托 {@link #formatGTPowerDecimal}，括号内保持无符号——
     * 统计/HUD/MTE 调用方的方向语义由 ↑↓/正负文案承担。
     * <p>
     * 输出示例：{@code §e2.0A HV}（黄色 2.0A HV），MAX 使用白色粗体下划线。
     *
     * @param euPerTick 每秒能量变化率
     * @return 格式化后的字符串（带 § 颜色代码），例如 {@code §e2.0A HV}
     */
    public static String formatGTPower(double euPerTick) {
        return formatGTPowerDecimal(Math.abs(euPerTick));
    }

    /**
     * 将 EU/t 转换为 GT 的电流+电压等级格式（「电流*电压」统一格式化方法）。
     * <p>
     * 电流按 {@code absEU / V[tier]} 真实值四舍五入保留 1 位小数（例 1024EU/t HV →
     * {@code §e2.0A HV}）；档位与进位逻辑复用 {@link #getGTTier}（&gt;4A 自动升档）。
     * MAX 档已是最高档、无更高级可升：超过 4A 后电流值按真实比值继续增大、不封顶
     * （例 10737418200EU/t → 白色粗体下划线的 {@code 10.0A MAX}）；负值（发电）加 {@code "-"} 前缀
     * （例 -1024EU/t → {@code §e-2.0A HV}）；档位颜色与整数版一致。
     * <p>
     * v1.7.4：整数变体 {@link #formatGTPower} 改为本方法的绝对值薄委托，两处表现
     * （无线电网统计括号后缀、设备信息终端「电压等级」计数模式）共用同一实现。
     *
     * @param euPerTick 每秒能量变化率（正=耗电 / 负=发电）
     * @return 格式化后的字符串（带 § 颜色代码），例如 {@code §e2.0A HV}
     */
    public static String formatGTPowerDecimal(double euPerTick) {
        int tier = getGTTier(euPerTick);
        double voltage = GTValues.V[tier];
        double absEU = Math.abs(euPerTick);
        // MAX 档无更高级可升：电流不封顶，按真实比值继续增大（仍保留 1 位小数）
        double amp = absEU / voltage;
        // 四舍五入保留 1 位小数（先乘 10 取整再除回，防二进制浮点 String.format 边界抖动）
        double rounded = Math.round(amp * 10.0D) / 10.0D;
        String color = TIER_COLORS[tier];
        String tierName = TIER_NAMES[tier];
        String sign = euPerTick < 0.0D ? "-" : "";
        return color + sign + String.format(java.util.Locale.ROOT, "%.1f", rounded) + "A " + tierName;
    }
}
