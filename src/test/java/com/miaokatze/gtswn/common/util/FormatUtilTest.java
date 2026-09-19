package com.miaokatze.gtswn.common.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;

import org.junit.Test;

/**
 * {@link FormatUtil} 的 BigInteger 常规计数 / 千位计数负数显示单测（纯逻辑，零 Minecraft 类加载）。
 * <p>
 * 缺陷口径（设备信息终端"瞬时 EU"，唯一可为负的显示值，见
 * {@code GuiDeviceInfoTerminal#formatEUt(long)} 的 MODE_THOUSANDS / MODE_NORMAL 分支）：
 * <ul>
 * <li>{@code formatMetric(BigInteger, int)} 用带符号 doubleValue 生成 result（本身已含 "-"），
 * 末尾又对负值前置 "-" → "--1.23M"；</li>
 * <li>{@code formatNormal(BigInteger)} 把带符号串直传 {@code insertThousandSeparators}
 * （该方法契约要求无符号数字串）→ "-,123,456"。</li>
 * </ul>
 * 断言值取自"正确显示"语义（负号恰好一个、数字分组不含符号），不迎合修复前的错误输出。
 * 其余调用点（RenderNetworkInfoPanel / GuiAEMonitorList / GuiNetworkInfoPanel /
 * MTEWirelessEnergyMonitor / HudController / TestCoin）均为非负储量，
 * 由本类的正数与零回归用例钉住字节级不变。
 */
public class FormatUtilTest {

    /** 2^63（Long.MIN_VALUE 的绝对值），BigInteger 可精确承载，long 不可。 */
    private static final BigInteger TWO_POW_63 = BigInteger.ONE.shiftLeft(63);

    /** 字符串中 '-' 出现次数（用于断言"符号只有一个"）。 */
    private static int countMinus(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '-') {
                n++;
            }
        }
        return n;
    }

    /**
     * 千位计数负数主用例：-1,234,567 在 decimals=2 下显示 "-1.23M"，
     * 且结果不含 "--"、负号恰好一个、不以 "-" 开头之外再无符号。
     */
    @Test
    public void metricNegativeKeepsSingleSign() {
        String result = FormatUtil.formatMetric(new BigInteger("-1234567"), 2);
        assertEquals("-1.23M", result);
        assertFalse("不得出现重复负号: " + result, result.contains("--"));
        assertEquals("负号恰好一个", 1, countMinus(result));
    }

    /** 常规计数负数主用例：-123,456 不得变成 "-,123,456"（开头多逗号）。 */
    @Test
    public void normalNegativeGroupsDigitsOnly() {
        String result = FormatUtil.formatNormal(new BigInteger("-123456"));
        assertEquals("-123,456", result);
        assertFalse("不得以逗号开头: " + result, result.startsWith(","));
        assertFalse("不得出现重复负号: " + result, result.contains("--"));
        assertEquals("负号恰好一个", 1, countMinus(result));
    }

    /** 常规计数负数位数边界：1 位 / 3 位（不足分组）/ 7 位（分组跨越符号）。 */
    @Test
    public void normalNegativeSignBoundaries() {
        assertEquals("-1", FormatUtil.formatNormal(new BigInteger("-1")));
        assertEquals("-999", FormatUtil.formatNormal(new BigInteger("-999")));
        assertEquals("-1,000", FormatUtil.formatNormal(new BigInteger("-1000")));
        assertEquals("-1,234,567", FormatUtil.formatNormal(new BigInteger("-1234567")));
        assertEquals("-10,000,000", FormatUtil.formatNormal(new BigInteger("-10000000")));
    }

    /** 千位计数负数词头边界：恰好进位到 K 与不足 K 的直显分支。 */
    @Test
    public void metricNegativeUnitBoundaries() {
        assertEquals("-1.00K", FormatUtil.formatMetric(BigInteger.valueOf(-1000L), 2));
        assertEquals("-999.00", FormatUtil.formatMetric(BigInteger.valueOf(-999L), 2));
        assertEquals("-1.50K", FormatUtil.formatMetric(new BigInteger("-1500"), 2));
        assertEquals("-1.23M", FormatUtil.formatMetric(new BigInteger("-1234567"), 2));
        assertEquals("-1.23G", FormatUtil.formatMetric(new BigInteger("-1234567890"), 2));
        for (String result : new String[] { FormatUtil.formatMetric(BigInteger.valueOf(-1000L), 2),
            FormatUtil.formatMetric(BigInteger.valueOf(-999L), 2),
            FormatUtil.formatMetric(new BigInteger("-12345678901234"), 2) }) {
            assertFalse("不得出现重复负号: " + result, result.contains("--"));
            assertEquals("负号恰好一个: " + result, 1, countMinus(result));
        }
    }

    /**
     * 正数与零回归钉值（字节级不变依据）：修复只把带符号入参改为绝对值 + 末尾前置符号，
     * 正数/零的 abs 与原值恒等，故这些用例等价于修复前的真实输出。
     */
    @Test
    public void positiveAndZeroOutputsUnchanged() {
        // 千位计数
        assertEquals("1.23M", FormatUtil.formatMetric(new BigInteger("1234567"), 2));
        assertEquals("1.50K", FormatUtil.formatMetric(new BigInteger("1500"), 2));
        assertEquals("999.00", FormatUtil.formatMetric(new BigInteger("999"), 2));
        assertEquals("1.23G", FormatUtil.formatMetric(new BigInteger("1234567890"), 2));
        assertEquals("0", FormatUtil.formatMetric(BigInteger.ZERO, 2));
        assertEquals("0", FormatUtil.formatMetric(null, 2));
        // 常规计数
        assertEquals("123,456", FormatUtil.formatNormal(new BigInteger("123456")));
        assertEquals("269,835,880", FormatUtil.formatNormal(new BigInteger("269835880")));
        assertEquals("999", FormatUtil.formatNormal(new BigInteger("999")));
        assertEquals("0", FormatUtil.formatNormal(BigInteger.ZERO));
        assertEquals("0", FormatUtil.formatNormal(null));
        // 无符号（正数与零）输出中不得出现 '-'
        assertFalse(
            FormatUtil.formatMetric(new BigInteger("1234567"), 2)
                .contains("-"));
        assertFalse(
            FormatUtil.formatNormal(new BigInteger("123456"))
                .contains("-"));
        assertFalse(
            FormatUtil.formatNormal(BigInteger.ZERO)
                .contains("-"));
    }

    /**
     * Long.MIN_VALUE 拓宽为 BigInteger 入参（游戏内 EU/t 可能的最小值）：
     * 两条路径都不抛异常、不产出单独的 "-"、符号恰好一个，且按绝对值分组/取词头。
     */
    @Test
    public void longMinValueAsBigIntegerIsSafe() {
        BigInteger min = BigInteger.valueOf(Long.MIN_VALUE);
        // 常规计数：9,223,372,036,854,775,808 分组后前置单个负号
        assertEquals("-9,223,372,036,854,775,808", FormatUtil.formatNormal(min));
        // 千位计数：走 P 词头分支（2^63 / 1e15 ≈ 9223.37）
        String metric = FormatUtil.formatMetric(min, 2);
        assertEquals("-9223.37P", metric);
        assertFalse("不得出现重复负号: " + metric, metric.contains("--"));
        assertEquals("负号恰好一个", 1, countMinus(metric));
        // 对照：同量级正数只少了负号
        assertEquals(metric.substring(1), FormatUtil.formatMetric(TWO_POW_63, 2));
        // 不抛异常即已到达此处；再确认极值不会被格式化成裸符号串
        assertTrue(metric.length() > 1);
        assertFalse("-".equals(FormatUtil.formatNormal(min)));
    }
}
