package com.miaokatze.gtswn.common.util;

import java.math.BigInteger;
import java.util.Locale;

/**
 * 公共格式化工具类
 * <p>
 * 提供无线电网监视器（MTE / HUD / 便携物品）共用的数值格式化方法与测量记录类。
 * 仅依赖 JDK，不引用本模组业务类，避免循环依赖。
 * <p>
 * 测量历史管理（窗口老化、EU/t 斜率计算、NBT 序列化）已迁移至 {@link EUDataSet}，
 * 本类仅保留 {@link Measurement} 数据结构与一组纯格式化静态方法。
 * <p>
 * 来源：合并自 {@code MTEWirelessEnergyMonitor} 与 {@code WirelessMonitorHUD} 的重复实现，
 * 以及 {@code PortableWirelessNetworkMonitor#formatBigInteger}。
 */
public class FormatUtil {

    /**
     * 测量记录类（MTE 与 HUD 共用）
     * <p>
     * 字段为 public 以兼容原 MTE/HUD 中直接访问 {@code m.tick} / {@code m.value} 的 NBT 持久化代码，
     * 以及 {@link EUDataSet} 内部直接构造与读取。
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
     * 格式化为常规计数（带逗号分隔）
     * <p>
     * 合并自 MTE 与 HUD 的同名方法（实现完全一致）。
     *
     * @param value 要格式化的 BigInteger 值
     * @return 格式化后的字符串（例如：269,835,880），null 返回 "0"
     */
    public static String formatNormal(BigInteger value) {
        if (value == null) return "0";
        return insertThousandSeparators(value.toString());
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
        return formatNormalDouble(value, 2);
    }

    /**
     * 格式化 double 为常规计数（带逗号分隔，可指定小数位数）
     * <p>
     * decimals=0 时不输出小数点与末尾零，适用于 AE 走势图等无小数需求。
     *
     * @param value    要格式化的 double 值
     * @param decimals 保留的小数位数（必须 >= 0；为 0 时不显示小数点）
     * @return 格式化后的字符串（例如：decimals=2 时 1,234.56；decimals=0 时 1,235）
     */
    public static String formatNormalDouble(double value, int decimals) {
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals must be non-negative");
        }

        // 取绝对值计算，负号最后补（与旧实现一致）
        double absValue = Math.abs(value);
        String formatted = String.format(Locale.ROOT, "%.*f", decimals, absValue);

        if (decimals == 0) {
            // 无小数点，仅需千位分隔
            return (value < 0 ? "-" : "") + insertThousandSeparators(formatted);
        }

        // 分离整数部分和小数部分
        String[] parts = formatted.split("\\.");
        String integerPart = parts[0];
        String decimalPart = parts.length > 1 ? parts[1] : "0".repeat(decimals);

        String finalResult = insertThousandSeparators(integerPart) + "." + decimalPart;

        // 如果是负数，添加负号
        if (value < 0) {
            finalResult = "-" + finalResult;
        }

        return finalResult;
    }

    /**
     * 格式化为科学计数法字符串（保留两位小数）
     * <p>
     * 统一采用 HUD 版本的零值处理（{@code value.equals(BigInteger.ZERO)}），
     * 比 MTE 原版的 {@code d == 0}（double 比较）更稳健。
     * <p>
     * 格式差异说明：MTE 原版使用 {@code %.3f}（三位小数），HUD 原版使用 {@code %.2f}（两位小数）。
     * 此处统一采用 {@code %.2f}（两位小数），与任务描述示例 {@code 1.23E6} 一致。
     *
     * @param value 要格式化的 BigInteger 值
     * @return 格式化后的字符串（例如：2.70E8），零值或 null 返回 "0"
     */
    public static String formatScientific(BigInteger value) {
        return formatScientific(value, 2);
    }

    /**
     * 格式化为科学计数法字符串（可指定系数小数位数）
     * <p>
     * decimals 控制系数的小数位数。例如 decimals=0 输出 "1E6"，decimals=2 输出 "1.00E6"。
     *
     * @param value    要格式化的 BigInteger 值
     * @param decimals 系数保留的小数位数（必须 >= 0）
     * @return 格式化后的字符串（例如：decimals=2 时 2.70E8），零值或 null 返回 "0"
     */
    public static String formatScientific(BigInteger value, int decimals) {
        if (value == null || value.equals(BigInteger.ZERO)) {
            return "0";
        }
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals must be non-negative");
        }

        // 转换为 double 进行科学计数法格式化
        double doubleValue = value.doubleValue();

        // 获取指数部分
        int exponent = (int) Math.floor(Math.log10(Math.abs(doubleValue)));

        // 计算系数
        double coefficient = doubleValue / Math.pow(10, exponent);

        // 使用 E 记法（Locale.ROOT 保证小数点与格式稳定）替代 ×10^，避免非 ASCII 字符显示异常，
        // 并与国际通用科学计数法格式保持一致。
        return String.format(Locale.ROOT, "%.*fE%d", decimals, coefficient, exponent);
    }

    /**
     * 格式化 double 为科学计数法字符串（保留两位小数）
     * <p>
     * 与 {@link #formatScientific(BigInteger)} 对应，用于 EU/t（变化率，double 类型）的科学计数显示。
     * 处理负数（保留负号）、零值、NaN/Infinity（兜底返回 "0"）。
     * <p>
     * 业务前提：调用方已通过 {@code absEut < 1.0} 分支拦截近似零值，故本方法输入 |value| >= 1.0，
     * 不会出现极小值 log10 精度问题；但仍做零值/NaN 防御以保证方法通用性。
     *
     * @param value 要格式化的 double 值（可正可负）
     * @return 格式化后的字符串（例如：2.70E8 或 -1.50E3），零值/NaN/Infinity 返回 "0"
     */
    public static String formatScientificDouble(double value) {
        return formatScientificDouble(value, 2);
    }

    /**
     * 格式化 double 为科学计数法字符串（可指定系数小数位数）
     * <p>
     * decimals 控制系数的小数位数。例如 decimals=0 输出 "1E6"，decimals=2 输出 "1.00E6"。
     * 处理负数（保留负号）、零值、NaN/Infinity（兜底返回 "0"）。
     *
     * @param value    要格式化的 double 值（可正可负）
     * @param decimals 系数保留的小数位数（必须 >= 0）
     * @return 格式化后的字符串（例如：decimals=2 时 2.70E8 或 -1.50E3），零值/NaN/Infinity 返回 "0"
     */
    public static String formatScientificDouble(double value, int decimals) {
        // 零值、NaN、Infinity 兜底（与 formatScientific(BigInteger) 的零值处理一致）
        if (value == 0.0 || Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        if (decimals < 0) {
            throw new IllegalArgumentException("decimals must be non-negative");
        }

        // 取绝对值计算指数（负号最后补，与 formatNormalDouble 的负数处理方式一致）
        double absValue = Math.abs(value);

        // 获取指数部分（floor 保证系数在 [1, 10) 区间）
        int exponent = (int) Math.floor(Math.log10(absValue));

        // 计算系数
        double coefficient = absValue / Math.pow(10, exponent);

        // 使用 E 记法（Locale.ROOT 保证小数点与格式稳定）替代 ×10^，避免非 ASCII 字符显示异常，
        // 并与国际通用科学计数法格式保持一致。
        String result = String.format(Locale.ROOT, "%.*fE%d", decimals, coefficient, exponent);

        // 负数补负号
        if (value < 0) {
            result = "-" + result;
        }

        return result;
    }

    /**
     * 千位模式格式化 BigInteger（K/M/G/T/P）
     * <p>
     * 使用 1,000 为底（国际单位制词头）对数值进行压缩，不保留小数。
     * 例如：1,500 格式化为 "1K"，2,700,000 格式化为 "2M"。
     *
     * @param value 要格式化的 BigInteger 值
     * @return 格式化后的字符串（例如：1K / 2M / 3G / 4T / 5P），null 或 0 返回 "0"
     */
    public static String formatMetric(BigInteger value) {
        if (value == null || value.equals(BigInteger.ZERO)) {
            return "0";
        }

        // 使用绝对值判断量级，除法仍对原值执行，保留符号（例如 -1500 -> "-1K"）
        BigInteger absValue = value.abs();

        if (absValue.compareTo(BigInteger.valueOf(1_000_000_000_000_000L)) >= 0) {
            return value.divide(BigInteger.valueOf(1_000_000_000_000_000L)) + "P";
        }
        if (absValue.compareTo(BigInteger.valueOf(1_000_000_000_000L)) >= 0) {
            return value.divide(BigInteger.valueOf(1_000_000_000_000L)) + "T";
        }
        if (absValue.compareTo(BigInteger.valueOf(1_000_000_000L)) >= 0) {
            return value.divide(BigInteger.valueOf(1_000_000_000L)) + "G";
        }
        if (absValue.compareTo(BigInteger.valueOf(1_000_000L)) >= 0) {
            return value.divide(BigInteger.valueOf(1_000_000L)) + "M";
        }
        if (absValue.compareTo(BigInteger.valueOf(1_000L)) >= 0) {
            return value.divide(BigInteger.valueOf(1_000L)) + "K";
        }

        return value.toString();
    }

    /**
     * 千位模式格式化 double（K/M/G/T/P）
     * <p>
     * 使用 1,000 为底（国际单位制词头）对数值进行压缩，不保留小数。
     * 负数采用后缀负号（例如 -1500 格式化为 "1K-"），与 GTNH 相关显示习惯保持一致。
     *
     * @param value 要格式化的 double 值
     * @return 格式化后的字符串（例如：1K / 2M / 3G / 4T / 5P 或 1K-），0/NaN/Infinity 返回 "0"
     */
    public static String formatMetricDouble(double value) {
        // 零值、NaN、Infinity 兜底
        if (value == 0.0 || Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }

        boolean negative = value < 0;
        double absValue = Math.abs(value);

        // 小于 1000 时直接取整显示
        if (absValue < 1000.0) {
            String result = String.valueOf(Math.round(absValue));
            return negative ? result + "-" : result;
        }

        // 确定单位与除数
        String unit;
        double divisor;
        if (absValue >= 1_000_000_000_000_000.0) {
            unit = "P";
            divisor = 1_000_000_000_000_000.0;
        } else if (absValue >= 1_000_000_000_000.0) {
            unit = "T";
            divisor = 1_000_000_000_000.0;
        } else if (absValue >= 1_000_000_000.0) {
            unit = "G";
            divisor = 1_000_000_000.0;
        } else if (absValue >= 1_000_000.0) {
            unit = "M";
            divisor = 1_000_000.0;
        } else {
            unit = "K";
            divisor = 1_000.0;
        }

        // 取整后拼接单位，负数后缀负号
        String result = String.valueOf(Math.round(absValue / divisor)) + unit;
        return negative ? result + "-" : result;
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

    /**
     * 为无符号整数字符串插入千位分隔符（逗号）
     * <p>
     * 从右向左每三位插入一个逗号。输入必须是纯数字字符串，不含符号或小数点。
     *
     * @param number 无符号整数数字字符串
     * @return 带逗号分隔的字符串
     */
    private static String insertThousandSeparators(String number) {
        StringBuilder result = new StringBuilder();
        int length = number.length();
        for (int i = 0; i < length; i++) {
            if (i > 0 && (length - i) % 3 == 0) {
                result.append(",");
            }
            result.append(number.charAt(i));
        }
        return result.toString();
    }
}
