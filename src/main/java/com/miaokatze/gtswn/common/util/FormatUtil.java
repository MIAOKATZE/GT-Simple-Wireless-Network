package com.miaokatze.gtswn.common.util;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;

/**
 * 公共格式化工具类
 * <p>
 * 提供无线电网监视器（MTE / HUD / 便携物品）共用的数值格式化与测量历史管理方法。
 * 仅依赖 JDK，不引用本模组业务类，避免循环依赖。
 * <p>
 * 来源：合并自 {@code MTEWirelessEnergyMonitor} 与 {@code WirelessMonitorHUD} 的重复实现，
 * 以及 {@code PortableWirelessNetworkMonitor#formatBigInteger}。
 */
public class FormatUtil {

    /** EU/t 均值计算窗口（ticks），6000 ticks = 300 秒。MTE 与 HUD 原值一致，统一迁移至此。 */
    public static final long WINDOW_TICKS = 6000L;

    /**
     * 测量记录类（MTE 与 HUD 共用）
     * <p>
     * 字段为 public 以兼容原 MTE/HUD 中直接访问 {@code m.tick} / {@code m.value} 的 NBT 持久化代码。
     */
    public static class Measurement {

        /** 测量时的游戏 tick */
        public long tick;

        /** 测量值（无线电网能量） */
        public BigInteger value;

        /**
         * 构造测量记录
         *
         * @param tick  游戏 tick
         * @param value 测量值
         */
        public Measurement(long tick, BigInteger value) {
            this.tick = tick;
            this.value = value;
        }
    }

    /**
     * 记录测量历史（每次检测都记录，不再判断是否变化）
     * <p>
     * 保证窗口内有足够样本支撑首末两点斜率算法。每次记录后立即调用
     * {@link #purgeExpired(List, long, long)} 清理窗口外样本。
     *
     * @param history     测量历史列表（调用方持有，方法会就地修改）
     * @param value       当前测量值
     * @param tick        当前游戏 tick
     * @param windowTicks 窗口大小（ticks），通常传 {@link #WINDOW_TICKS}
     */
    public static void recordMeasurement(List<Measurement> history, BigInteger value, long tick, long windowTicks) {
        if (value == null) return;
        history.add(new Measurement(tick, value));
        // 老化：清理窗口外样本（tick < currentTick - windowTicks）
        purgeExpired(history, tick, windowTicks);
    }

    /**
     * 老化过期样本：淘汰 tick &lt; currentTick - windowTicks 的样本
     * <p>
     * 列表按时间顺序追加，遇到第一个未过期样本即可停止遍历。
     *
     * @param history     测量历史列表
     * @param currentTick 当前游戏 tick
     * @param windowTicks 窗口大小（ticks）
     */
    public static void purgeExpired(List<Measurement> history, long currentTick, long windowTicks) {
        long cutoff = currentTick - windowTicks;
        Iterator<Measurement> it = history.iterator();
        while (it.hasNext()) {
            if (it.next().tick < cutoff) {
                it.remove();
            } else {
                break; // 列表按时间顺序，遇到第一个未过期即可停止
            }
        }
    }

    /**
     * 格式化为常规计数（带逗号分隔）
     * <p>
     * 合并自 MTE 与 HUD 的同名方法（实现完全一致）。
     *
     * @param value 要格式化的 BigInteger 值
     * @return 格式化后的字符串（例如：269,835,880），null 返回 "0"
     */
    public static String formatNormal(BigInteger value) {
        if (value == null) return "0";
        String str = value.toString();
        StringBuilder result = new StringBuilder();
        int length = str.length();
        for (int i = 0; i < length; i++) {
            if (i > 0 && (length - i) % 3 == 0) {
                result.append(",");
            }
            result.append(str.charAt(i));
        }
        return result.toString();
    }

    /**
     * 格式化 double 为常规计数（带逗号分隔，保留两位小数）
     * <p>
     * 合并自 MTE 与 HUD 的同名方法（实现完全一致）。处理负数、千位分隔符。
     *
     * @param value 要格式化的 double 值
     * @return 格式化后的字符串（例如：1,234.56 或 -1,234.56）
     */
    public static String formatNormalDouble(double value) {
        // 先格式化为两位小数（取绝对值，负号最后补）
        String formatted = String.format("%.2f", Math.abs(value));

        // 分离整数部分和小数部分
        String[] parts = formatted.split("\\.");
        String integerPart = parts[0];
        String decimalPart = parts.length > 1 ? parts[1] : "00";

        // 为整数部分添加逗号分隔
        StringBuilder result = new StringBuilder();
        int length = integerPart.length();
        for (int i = 0; i < length; i++) {
            if (i > 0 && (length - i) % 3 == 0) {
                result.append(",");
            }
            result.append(integerPart.charAt(i));
        }

        // 组合结果
        String finalResult = result.toString() + "." + decimalPart;

        // 如果是负数，添加负号
        if (value < 0) {
            finalResult = "-" + finalResult;
        }

        return finalResult;
    }

    /**
     * 格式化为科学计数法字符串
     * <p>
     * 统一采用 HUD 版本的零值处理（{@code value.equals(BigInteger.ZERO)}），
     * 比 MTE 原版的 {@code d == 0}（double 比较）更稳健。
     * <p>
     * 格式差异说明：MTE 原版使用 {@code %.3f}（三位小数），HUD 原版使用 {@code %.2f}（两位小数）。
     * 此处统一采用 {@code %.2f}（两位小数），与任务描述示例 {@code 1.23×10^6} 一致。
     *
     * @param value 要格式化的 BigInteger 值
     * @return 格式化后的字符串（例如：2.70×10^8），零值或 null 返回 "0"
     */
    public static String formatScientific(BigInteger value) {
        if (value == null || value.equals(BigInteger.ZERO)) {
            return "0";
        }

        // 转换为 double 进行科学计数法格式化
        double doubleValue = value.doubleValue();

        // 获取指数部分
        int exponent = (int) Math.floor(Math.log10(Math.abs(doubleValue)));

        // 计算系数（保留两位小数，即三位有效数字）
        double coefficient = doubleValue / Math.pow(10, exponent);

        // 格式化为 "系数×10^指数" 的形式
        return String.format("%.2f×10^%d", coefficient, exponent);
    }

    /**
     * 格式化 BigInteger 为易读字符串
     * <p>
     * 来自 {@code PortableWirelessNetworkMonitor#formatBigInteger}。
     * 与 {@link #formatNormal(BigInteger)} 的差异：小数值（&lt; 1,000,000）直接 toString 不加分隔符，
     * 大数值才使用逗号分隔。
     * <p>
     * 注：原方法在 PortableWirelessNetworkMonitor 中未被调用（死代码），提取至此以备复用。
     *
     * @param value 要格式化的 BigInteger 值
     * @return 小数值直接 toString，大数值带逗号分隔；null 返回 "0"
     */
    public static String formatBigInteger(BigInteger value) {
        if (value == null) return "0";

        // 如果数值较小，直接显示
        if (value.compareTo(BigInteger.valueOf(1_000_000L)) < 0) {
            return value.toString();
        }

        // 对于大数值，使用逗号分隔（复用 formatNormal）
        return formatNormal(value);
    }
}
